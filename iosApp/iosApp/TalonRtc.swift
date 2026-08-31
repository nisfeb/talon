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
/// threads and Kotlin calls in from coroutine dispatchers, so every
/// touch of this class's mutable state — public entry points, delegate
/// bodies, async completions — hops to the main queue first (`main`
/// runs inline when already there). State lives on one thread, and
/// callbacks cross into Kotlin on main. The one exception: the two
/// level getters return their cached value synchronously, so an
/// off-main caller can read one poll stale — harmless for a speaking
/// dot.
final class TalonRtcPeer: NSObject, NativeRtcPeer, RTCPeerConnectionDelegate {

    private static let factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        return RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory()
        )
    }()

    /// Live peers holding the microphone. The shared audio session is
    /// configured by the first and restored by the last, so closing a
    /// party-line up link mid-retry (old link closes while the new one
    /// lives) doesn't yank the session out from under the call.
    /// NSLock rather than main-confinement because init runs on the
    /// caller's thread, not main.
    private static let sessionLock = NSLock()
    private static var audioPeers = 0

    private let trickle: Bool
    private var pc: RTCPeerConnection?
    private var micTrack: RTCAudioTrack?
    private var ownsAudioSession = false
    private var stateListener: ((MediaState) -> Void)?
    private var candidateListener: ((IceCandidate) -> Void)?
    /// Non-trickle peers hold their SDP until gathering completes: the
    /// SDP is the only chance their candidates get to reach the peer.
    /// Main-queue confined, like all state here.
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
            // offerer is always the sender, so an up link sends only.
            // A 1:1 call must stay sendRecv: per JSEP the answer takes
            // the transceiver's own direction, so answering from a
            // send-only transceiver would emit a sendonly answer and
            // leave the caller's audio with no negotiated path.
            let params = RTCRtpTransceiverInit()
            params.direction = trickle ? .sendOnly : .sendRecv
            pc?.addTransceiver(with: track, init: params)
        }
    }

    /// Voice-chat routing: earpiece/speaker handling and echo
    /// cancellation come from the session category, not from us.
    ///
    /// Party lines default to the loudspeaker; a 1:1 call defaults to
    /// the earpiece like every other phone app. Speakerphone on demand
    /// comes from IosAudioDevices' explicit output override.
    private func configureAudioSession() {
        // sessionLock covers the session mutation, not just the count:
        // a last-peer restore deciding under the lock but configuring
        // outside it could interleave with this configure and leave the
        // session .ambient while a fresh peer holds the mic.
        TalonRtcPeer.sessionLock.lock()
        defer { TalonRtcPeer.sessionLock.unlock() }
        TalonRtcPeer.audioPeers += 1
        ownsAudioSession = true

        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        do {
            try session.setCategory(
                .playAndRecord,
                with: trickle ? [.allowBluetooth, .defaultToSpeaker] : [.allowBluetooth]
            )
            try session.setMode(.voiceChat)
        } catch {
            NSLog("talon: audio session config failed: \(error)")
        }
        session.unlockForConfiguration()
    }

    /// The last mic-holding peer hands the shared session back:
    /// category first (the part that outlives deactivation — leaving
    /// .playAndRecord/.voiceChat would keep all app audio
    /// voice-processed and tones ignoring the silent switch), then
    /// deactivate. RTCAudioSession notifies other apps on deactivation
    /// itself, so no options parameter exists or is needed.
    private static func restoreAudioSession() {
        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        do {
            try session.setCategory(.ambient, with: [])
            try session.setMode(.default)
            try session.setActive(false)
        } catch {
            NSLog("talon: audio session restore failed: \(error)")
        }
        session.unlockForConfiguration()
    }

    // MARK: - NativeRtcPeer

    func onStateChange(listener: @escaping (MediaState) -> Void) {
        main { [weak self] in self?.stateListener = listener }
    }

    func onIceCandidate(listener: @escaping (IceCandidate) -> Void) {
        main { [weak self] in self?.candidateListener = listener }
    }

    func createOffer(done: @escaping (String?, String?) -> Void) {
        main { [weak self] in
            guard let self = self, let pc = self.pc else {
                return done(nil, "peer connection closed")
            }
            pc.offer(for: self.mediaConstraints()) { sdp, err in
                self.handleLocal(sdp: sdp, err: err, done: done)
            }
        }
    }

    func createAnswer(remoteSdp: String, done: @escaping (String?, String?) -> Void) {
        main { [weak self] in
            guard let self = self, let pc = self.pc else {
                return done(nil, "peer connection closed")
            }
            let offer = RTCSessionDescription(type: .offer, sdp: remoteSdp)
            pc.setRemoteDescription(offer) { err in
                self.main {
                    if let err = err { return done(nil, "setRemoteDescription: \(err)") }
                    guard let pc = self.pc else {
                        return done(nil, "peer connection closed")
                    }
                    pc.answer(for: self.mediaConstraints()) { sdp, err in
                        self.handleLocal(sdp: sdp, err: err, done: done)
                    }
                }
            }
        }
    }

    func applyAnswer(remoteSdp: String, done: @escaping (String?) -> Void) {
        main { [weak self] in
            guard let self = self, let pc = self.pc else {
                return done("peer connection closed")
            }
            pc.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: remoteSdp)) { err in
                self.main { done(err.map { "setRemoteDescription: \($0)" }) }
            }
        }
    }

    func addIceCandidate(candidate: IceCandidate) {
        main { [weak self] in
            self?.pc?.add(
                RTCIceCandidate(
                    sdp: candidate.candidate,
                    sdpMLineIndex: candidate.sdpMLineIndex,
                    sdpMid: candidate.sdpMid
                )
            ) { err in
                if let err = err { NSLog("talon: addIceCandidate failed: \(err)") }
            }
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
        main { [weak self] in
            guard let self = self, let pc = self.pc, !self.localStatsInFlight else { return }
            self.localStatsInFlight = true
            pc.statistics { report in
                var level = -1.0
                for (_, stat) in report.statistics where stat.type == "media-source" {
                    if let v = stat.values["audioLevel"] as? NSNumber {
                        level = v.doubleValue
                    }
                }
                self.main {
                    self.lastLocalLevel = level
                    self.localStatsInFlight = false
                }
            }
        }
        return lastLocalLevel
    }

    func remoteAudioLevel() -> Double {
        main { [weak self] in
            guard let self = self, let pc = self.pc, !self.statsInFlight else { return }
            self.statsInFlight = true
            pc.statistics { report in
                var level = -1.0
                for (_, stat) in report.statistics where stat.type == "inbound-rtp" {
                    if let v = stat.values["audioLevel"] as? NSNumber {
                        level = v.doubleValue
                    }
                }
                self.main {
                    self.lastLevel = level
                    self.statsInFlight = false
                }
            }
        }
        return lastLevel
    }

    func setMuted(muted: Bool) {
        main { [weak self] in self?.micTrack?.isEnabled = !muted }
    }

    func close() {
        main { [weak self] in
            guard let self = self, !self.closed else { return }
            self.closed = true
            self.micTrack?.isEnabled = false
            self.micTrack = nil
            self.pc?.close()
            self.pc = nil
            // A parked non-trickle completion must fire, not vanish:
            // the Kotlin side is suspended on it, and dropping it
            // leaked that coroutine forever. Desktop throws on a
            // closed peer; match it.
            if let done = self.pendingGather {
                self.pendingGather = nil
                done(nil, "peer connection closed")
            }
            self.releaseAudioSession()
        }
    }

    private func releaseAudioSession() {
        guard ownsAudioSession else { return }
        ownsAudioSession = false
        // Decrement, re-check, and restore under one hold of the lock,
        // so a peer constructing concurrently can't configure between
        // our decision and our restore.
        TalonRtcPeer.sessionLock.lock()
        defer { TalonRtcPeer.sessionLock.unlock() }
        TalonRtcPeer.audioPeers -= 1
        if TalonRtcPeer.audioPeers == 0 { TalonRtcPeer.restoreAudioSession() }
    }

    // MARK: - internals

    private func mediaConstraints() -> RTCMediaConstraints {
        RTCMediaConstraints(
            mandatoryConstraints: ["OfferToReceiveAudio": "true"],
            optionalConstraints: nil
        )
    }

    /// Set the local description, then either return the SDP straight
    /// away (trickle) or park the completion until gathering finishes —
    /// with an 8s fallback, because a dead STUN/TURN server must
    /// degrade, not hang the call setup forever.
    private func handleLocal(
        sdp: RTCSessionDescription?,
        err: Error?,
        done: @escaping (String?, String?) -> Void
    ) {
        main { [weak self] in
            if let err = err { return done(nil, "createSdp: \(err)") }
            guard let self = self else { return done(nil, "peer released") }
            guard let sdp = sdp, let pc = self.pc else {
                return done(nil, "no session description")
            }
            pc.setLocalDescription(sdp) { err in
                self.main {
                    if let err = err { return done(nil, "setLocalDescription: \(err)") }
                    if self.trickle {
                        done(sdp.sdp, nil)
                    } else if pc.iceGatheringState == .complete {
                        done(self.localSdp() ?? sdp.sdp, nil)
                    } else {
                        self.pendingGather = done
                        self.scheduleGatherTimeout(heldSdp: sdp.sdp)
                    }
                }
            }
        }
    }

    /// Desktop and Android proceed with whatever candidates arrived
    /// after 8 seconds; so does this. pendingGather is main-confined,
    /// so the nil check cannot race the gathering-complete delegate.
    private func scheduleGatherTimeout(heldSdp: String) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 8) { [weak self] in
            guard let self = self, let done = self.pendingGather else { return }
            self.pendingGather = nil
            NSLog("talon: gathering incomplete after 8s — proceeding with partial candidates")
            done(self.localSdp() ?? heldSdp, nil)
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
        guard newState == .complete else { return }
        main { [weak self] in
            guard let self = self, let done = self.pendingGather else { return }
            self.pendingGather = nil
            done(self.localSdp(), nil)
        }
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
