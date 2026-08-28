import AVFoundation
import ComposeApp
import Foundation
import WebRTC

/// WebRTC for iOS, behind the `NativeRtcPeer` seam Kotlin defines.
///
/// Kotlin owns the signaling and the call state machine; this file owns
/// nothing but media. The bridge is callback-shaped because Swift can
/// neither implement a Kotlin `suspend` function nor return a
/// `StateFlow` — `IosPeerLink` / `IosCallEngine` adapt it back.
///
/// Threading: libwebrtc calls its delegate on internal signaling
/// threads. Every callback here is hopped to the main queue before it
/// crosses into Kotlin, so the Kotlin side sees one consistent thread.
final class TalonRtcPeer: NSObject, NativeRtcPeer, RTCPeerConnectionDelegate {

    private static let factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        return RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory()
        )
    }()

    private let trickle: Bool
    private var pc: RTCPeerConnection?
    private var micTrack: RTCAudioTrack?
    private var stateListener: ((MediaState) -> Void)?
    private var candidateListener: ((IceCandidate) -> Void)?
    /// Non-trickle peers hold their SDP until gathering completes: the
    /// SDP is the only chance their candidates get to reach the peer.
    private var pendingGather: ((String?, String?) -> Void)?
    private var closed = false
    private var lastLevel: Double = -1
    private var statsInFlight = false
    private var lastLocalLevel: Double = -1
    private var localStatsInFlight = false

    init(iceServers: [IceServer], sendAudio: Bool, trickle: Bool) {
        self.trickle = trickle
        super.init()

        let config = RTCConfiguration()
        config.sdpSemantics = .unifiedPlan
        config.iceServers = iceServers.map { server in
            server.user.isEmpty
                ? RTCIceServer(urlStrings: [server.url])
                : RTCIceServer(
                    urlStrings: [server.url],
                    username: server.user,
                    credential: server.cred
                )
        }
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: nil,
            optionalConstraints: nil
        )
        pc = TalonRtcPeer.factory.peerConnection(
            with: config,
            constraints: constraints,
            delegate: self
        )

        if sendAudio {
            configureAudioSession()
            let source = TalonRtcPeer.factory.audioSource(with: constraints)
            let track = TalonRtcPeer.factory.audioTrack(with: source, trackId: "talon-mic")
            micTrack = track
            // Galène requires every stream to be one-directional: the
            // offerer is always the sender. The 1:1 path is happy with
            // send-only too, since each side offers its own mic.
            let params = RTCRtpTransceiverInit()
            params.direction = .sendOnly
            pc?.addTransceiver(with: track, init: params)
        }
    }

    /// Voice-chat routing: earpiece/speaker handling and echo
    /// cancellation come from the session category, not from us.
    private func configureAudioSession() {
        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        do {
            try session.setCategory(
                .playAndRecord,
                with: [.allowBluetooth, .defaultToSpeaker]
            )
            try session.setMode(.voiceChat)
        } catch {
            NSLog("talon: audio session config failed: \(error)")
        }
        session.unlockForConfiguration()
    }

    // MARK: - NativeRtcPeer

    func onStateChange(listener: @escaping (MediaState) -> Void) {
        stateListener = listener
    }

    func onIceCandidate(listener: @escaping (IceCandidate) -> Void) {
        candidateListener = listener
    }

    func createOffer(done: @escaping (String?, String?) -> Void) {
        guard let pc = pc else { return done(nil, "peer connection closed") }
        pc.offer(for: mediaConstraints()) { [weak self] sdp, err in
            self?.handleLocal(sdp: sdp, err: err, done: done)
        }
    }

    func createAnswer(remoteSdp: String, done: @escaping (String?, String?) -> Void) {
        guard let pc = pc else { return done(nil, "peer connection closed") }
        let offer = RTCSessionDescription(type: .offer, sdp: remoteSdp)
        pc.setRemoteDescription(offer) { [weak self] err in
            if let err = err { return done(nil, "setRemoteDescription: \(err)") }
            guard let self = self, let pc = self.pc else {
                return done(nil, "peer connection closed")
            }
            pc.answer(for: self.mediaConstraints()) { sdp, err in
                self.handleLocal(sdp: sdp, err: err, done: done)
            }
        }
    }

    func applyAnswer(remoteSdp: String, done: @escaping (String?) -> Void) {
        guard let pc = pc else { return done("peer connection closed") }
        pc.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: remoteSdp)) { err in
            done(err.map { "setRemoteDescription: \($0)" })
        }
    }

    func addIceCandidate(candidate: IceCandidate) {
        pc?.add(
            RTCIceCandidate(
                sdp: candidate.candidate,
                sdpMLineIndex: candidate.sdpMLineIndex,
                sdpMid: candidate.sdpMid
            )
        ) { err in
            if let err = err { NSLog("talon: addIceCandidate failed: \(err)") }
        }
    }

    /// Remote audio level, or -1 when unknown.
    ///
    /// libwebrtc's iOS API has no synchronization-source accessor, so
    /// this reads inbound-rtp statistics instead. They arrive
    /// asynchronously, so a call kicks off a refresh and returns what
    /// the last one saw — the caller polls anyway.
    /// Our own microphone, from the media-source statistic.
    ///
    /// Not outbound-rtp: that reports what was sent, which is
    /// silence-suppressed, so a quiet talker reads as nothing at all.
    func localAudioLevel() -> Double {
        guard let pc = pc, !localStatsInFlight else { return lastLocalLevel }
        localStatsInFlight = true
        pc.statistics { [weak self] report in
            var level = -1.0
            for (_, stat) in report.statistics where stat.type == "media-source" {
                if let v = stat.values["audioLevel"] as? NSNumber {
                    level = v.doubleValue
                }
            }
            self?.lastLocalLevel = level
            self?.localStatsInFlight = false
        }
        return lastLocalLevel
    }

    func remoteAudioLevel() -> Double {
        guard let pc = pc, !statsInFlight else { return lastLevel }
        statsInFlight = true
        pc.statistics { [weak self] report in
            var level = -1.0
            for (_, stat) in report.statistics where stat.type == "inbound-rtp" {
                if let v = stat.values["audioLevel"] as? NSNumber {
                    level = v.doubleValue
                }
            }
            self?.lastLevel = level
            self?.statsInFlight = false
        }
        return lastLevel
    }

    func setMuted(muted: Bool) {
        micTrack?.isEnabled = !muted
    }

    func close() {
        guard !closed else { return }
        closed = true
        micTrack?.isEnabled = false
        micTrack = nil
        pc?.close()
        pc = nil
        pendingGather = nil
    }

    // MARK: - internals

    private func mediaConstraints() -> RTCMediaConstraints {
        RTCMediaConstraints(
            mandatoryConstraints: ["OfferToReceiveAudio": "true"],
            optionalConstraints: nil
        )
    }

    /// Set the local description, then either return the SDP straight
    /// away (trickle) or park the completion until gathering finishes.
    private func handleLocal(
        sdp: RTCSessionDescription?,
        err: Error?,
        done: @escaping (String?, String?) -> Void
    ) {
        if let err = err { return done(nil, "createSdp: \(err)") }
        guard let sdp = sdp, let pc = pc else {
            return done(nil, "no session description")
        }
        pc.setLocalDescription(sdp) { [weak self] err in
            if let err = err { return done(nil, "setLocalDescription: \(err)") }
            guard let self = self else { return done(nil, "peer released") }
            if self.trickle {
                self.main { done(sdp.sdp, nil) }
            } else if pc.iceGatheringState == .complete {
                self.main { done(self.localSdp() ?? sdp.sdp, nil) }
            } else {
                self.pendingGather = done
            }
        }
    }

    private func localSdp() -> String? { pc?.localDescription?.sdp }

    private func main(_ block: @escaping () -> Void) {
        if Thread.isMainThread { block() } else { DispatchQueue.main.async(execute: block) }
    }

    // MARK: - RTCPeerConnectionDelegate

    func peerConnection(
        _ peerConnection: RTCPeerConnection,
        didChange newState: RTCPeerConnectionState
    ) {
        let mapped: MediaState
        switch newState {
        case .connected: mapped = MediaState.live
        case .connecting: mapped = MediaState.connecting
        case .failed: mapped = MediaState.failed
        case .closed: mapped = MediaState.closed
        default: return
        }
        main { [weak self] in self?.stateListener?(mapped) }
    }

    func peerConnection(
        _ peerConnection: RTCPeerConnection,
        didGenerate candidate: RTCIceCandidate
    ) {
        guard trickle else { return }
        let mapped = IceCandidate(
            candidate: candidate.sdp,
            sdpMid: candidate.sdpMid,
            sdpMLineIndex: candidate.sdpMLineIndex
        )
        main { [weak self] in self?.candidateListener?(mapped) }
    }

    func peerConnection(
        _ peerConnection: RTCPeerConnection,
        didChange newState: RTCIceGatheringState
    ) {
        guard newState == .complete, let done = pendingGather else { return }
        pendingGather = nil
        main { [weak self] in done(self?.localSdp(), nil) }
    }

    // Unused delegate requirements.
    func peerConnection(_ pc: RTCPeerConnection, didChange s: RTCSignalingState) {}
    func peerConnection(_ pc: RTCPeerConnection, didAdd s: RTCMediaStream) {}
    func peerConnection(_ pc: RTCPeerConnection, didRemove s: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ pc: RTCPeerConnection) {}
    func peerConnection(_ pc: RTCPeerConnection, didChange s: RTCIceConnectionState) {}
    func peerConnection(_ pc: RTCPeerConnection, didRemove c: [RTCIceCandidate]) {}
    func peerConnection(_ pc: RTCPeerConnection, didOpen d: RTCDataChannel) {}
}

/// Handed to `MainViewController(rtc:)`.
final class TalonRtcFactory: NSObject, NativeRtcFactory {
    func create(
        iceServers: [IceServer],
        sendAudio: Bool,
        trickle: Bool
    ) -> NativeRtcPeer {
        TalonRtcPeer(iceServers: iceServers, sendAudio: sendAudio, trickle: trickle)
    }
}
