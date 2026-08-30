import AVFoundation
import CoreMedia
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

    // MARK: - video
    /// The sendrecv video sender, created up-front with no track, so
    /// turning the camera on later needs no renegotiation.
    private var videoSender: RTCRtpSender?
    private var capturer: RTCCameraVideoCapturer?
    private var localVideoTrack: RTCVideoTrack?
    private var remoteVideoTrack: RTCVideoTrack?
    private var videoListener: ((VideoState) -> Void)?
    private var videoState = VideoState(localOn: false, remoteOn: false)
    private lazy var localView: RTCMTLVideoView = RTCMTLVideoView(frame: .zero)
    private lazy var remoteView: RTCMTLVideoView = RTCMTLVideoView(frame: .zero)

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

        // Video on 1:1 calls only. A party line's links are
        // one-directional by Galène's rules and carry audio; adding a
        // video m-line to each of N down links would negotiate N
        // streams nobody asked for.
        if !trickle {
            let vparams = RTCRtpTransceiverInit()
            vparams.direction = .sendRecv
            videoSender = pc?.addTransceiver(of: .video, init: vparams)?.sender
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
    func micLevel() -> Double {
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

    func onVideoChange(listener: @escaping (VideoState) -> Void) {
        videoListener = listener
    }

    func localVideoView() -> Any? { localView }
    func remoteVideoView() -> Any? { remoteView }

    /// Open or close the camera and hang it on the pre-negotiated
    /// sender. Front camera by preference, ~640x480 to match the other
    /// platforms — Talon has no simulcast, so one modest stream is the
    /// entire budget.
    func setCameraEnabled(enabled: Bool, done: @escaping (String?) -> Void) {
        guard let sender = videoSender else {
            return done("this call negotiated no video")
        }
        if !enabled {
            // No capturer means nothing to stop — and stopCapture's
            // completion would never fire, leaving `done` uncalled and
            // the caller awaiting forever.
            guard let cap = capturer else {
                sender.track = nil
                localVideoTrack = nil
                publishVideo(local: false, remote: videoState.remoteOn)
                return done(nil)
            }
            cap.stopCapture { [weak self] in
                self?.main {
                    guard let self = self else { return }
                    self.localVideoTrack?.remove(self.localView)
                    sender.track = nil
                    self.capturer = nil
                    self.localVideoTrack = nil
                    self.publishVideo(local: false, remote: self.videoState.remoteOn)
                    done(nil)
                }
            }
            return
        }
        if localVideoTrack != nil { return done(nil) }

        let devices = RTCCameraVideoCapturer.captureDevices()
        guard let device = devices.first(where: { $0.position == .front }) ?? devices.first else {
            return done("no camera on this device")
        }
        let formats = RTCCameraVideoCapturer.supportedFormats(for: device)
        // Closest to 640x480 rather than largest: the biggest format a
        // modern iPhone offers is 4K, which would saturate the link.
        guard let format = formats.min(by: { a, b in
            let da = CMVideoFormatDescriptionGetDimensions(a.formatDescription)
            let db = CMVideoFormatDescriptionGetDimensions(b.formatDescription)
            return abs(Int(da.width) - 640) < abs(Int(db.width) - 640)
        }) else {
            return done("this camera offers no usable format")
        }
        let fps = min(
            30,
            Int(format.videoSupportedFrameRateRanges.map { $0.maxFrameRate }.max() ?? 30)
        )

        let source = TalonRtcPeer.factory.videoSource()
        let cap = RTCCameraVideoCapturer(delegate: source)
        let track = TalonRtcPeer.factory.videoTrack(with: source, trackId: "talon-cam")
        cap.startCapture(with: device, format: format, fps: fps) { [weak self] error in
            self?.main {
                guard let self = self else { return }
                if let error = error {
                    return done("camera failed to start: \(error.localizedDescription)")
                }
                track.add(self.localView)
                sender.track = track
                self.capturer = cap
                self.localVideoTrack = track
                self.publishVideo(local: true, remote: self.videoState.remoteOn)
                done(nil)
            }
        }
    }

    private func publishVideo(local: Bool, remote: Bool) {
        videoState = VideoState(localOn: local, remoteOn: remote)
        videoListener?(videoState)
    }

    func close() {
        guard !closed else { return }
        closed = true
        micTrack?.isEnabled = false
        micTrack = nil
        // Stop capture before the peer connection goes: a running
        // capturer feeding a closed source is a callback into freed
        // memory, the same hazard as tearing down the factory mid-gather.
        capturer?.stopCapture()
        capturer = nil
        localVideoTrack = nil
        remoteVideoTrack = nil
        videoSender = nil
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
    func peerConnection(
        _ pc: RTCPeerConnection,
        didStartReceivingOn transceiver: RTCRtpTransceiver
    ) {
        guard let track = transceiver.receiver.track as? RTCVideoTrack else { return }
        main { [weak self] in
            guard let self = self else { return }
            self.remoteVideoTrack = track
            track.add(self.remoteView)
            self.publishVideo(local: self.videoState.localOn, remote: true)
        }
    }

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
