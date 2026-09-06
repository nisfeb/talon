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

    // MARK: - video
    /// The sendrecv video sender, created up-front with no track, so
    /// turning the camera on later needs no renegotiation.
    private var videoSender: RTCRtpSender?
    private var capturer: RTCCameraVideoCapturer?
    private var localVideoTrack: RTCVideoTrack?
    /// Which camera is live, so switchCamera knows what to flip to.
    /// Starts front — the default setCameraEnabled picks.
    private var usingFront = true
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

        // Every link holds the audio session (not only the mic), so a
        // muted user's up link closing keeps the refcount above zero
        // while their down links still play. Configured before any mic
        // track is created.
        configureAudioSession()
        if sendAudio {
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

        // Video: a 1:1 call is sendRecv; a party-line UP link is
        // send-only (Galène streams are one-directional). A party DOWN
        // link adds no video transceiver — it receives the remote
        // camera straight from the offer (see didStartReceivingOn). So
        // only N cameras negotiate, never a video m-line per down link.
        if !trickle {
            let vparams = RTCRtpTransceiverInit()
            vparams.direction = .sendRecv
            videoSender = pc?.addTransceiver(of: .video, init: vparams)?.sender
        } else if sendAudio {
            let vparams = RTCRtpTransceiverInit()
            vparams.direction = .sendOnly
            videoSender = pc?.addTransceiver(of: .video, init: vparams)?.sender
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
        // Only the first link on the line configures the session; the
        // rest just hold a ref so the last one out restores it.
        guard TalonRtcPeer.audioPeers == 1 else { return }

        // libwebrtc's ADM applies RTCAudioSessionConfiguration.webRTC()
        // when it starts playout, and that default carries no
        // .defaultToSpeaker — so a party line configured for speaker
        // came out of the earpiece, and the user had to pick Speaker by
        // hand on every line. Set the class default before the ADM
        // reads it.
        let webrtcConfig = RTCAudioSessionConfiguration.webRTC()
        webrtcConfig.categoryOptions = trickle
            ? [.allowBluetooth, .defaultToSpeaker]
            : [.allowBluetooth]
        RTCAudioSessionConfiguration.setWebRTC(webrtcConfig)

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

    func switchCamera() {
        main { [weak self] in
            guard let self = self, let cap = self.capturer else { return }
            let wantFront = !self.usingFront
            let devices = RTCCameraVideoCapturer.captureDevices()
            let pos: AVCaptureDevice.Position = wantFront ? .front : .back
            guard let device = devices.first(where: { $0.position == pos })
                ?? devices.first else { return }
            let formats = RTCCameraVideoCapturer.supportedFormats(for: device)
            guard let format = formats.min(by: { a, b in
                let da = CMVideoFormatDescriptionGetDimensions(a.formatDescription)
                let db = CMVideoFormatDescriptionGetDimensions(b.formatDescription)
                return abs(Int(da.width) - 640) < abs(Int(db.width) - 640)
            }) else { return }
            let fps = min(
                30,
                Int(format.videoSupportedFrameRateRanges.map { $0.maxFrameRate }.max() ?? 30)
            )
            // Restart the same capturer on the other device; the track,
            // sender and view stay put and keep receiving frames.
            cap.startCapture(with: device, format: format, fps: fps) { [weak self] err in
                self?.main { if err == nil { self?.usingFront = wantFront } }
            }
        }
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

        // startCapture only forwards a lockForConfiguration failure —
        // a denied camera merely logs — so without this the enable path
        // called done(nil), localOn went true and we broadcast
        // VIDEO_KIND on while every tile showed black.
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .denied, .restricted:
            return done("camera access is off — enable it in Settings")
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                self?.main {
                    guard let self = self else { return }
                    if granted { self.setCameraEnabled(enabled: true, done: done) }
                    else { done("camera access is off — enable it in Settings") }
                }
            }
            return
        default:
            break
        }

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
        main { [weak self] in
            guard let self = self, !self.closed else { return }
            self.closed = true
            self.micTrack?.isEnabled = false
            self.micTrack = nil
            // Stop capture before the peer connection goes: a running
            // capturer feeding a closed source is a callback into freed
            // memory, the same hazard as tearing down mid-gather.
            self.capturer?.stopCapture()
            self.capturer = nil
            self.localVideoTrack = nil
            self.remoteVideoTrack = nil
            self.videoSender = nil
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
            // Drop the Kotlin lambdas. They capture the Kotlin adapter,
            // which holds this peer — the documented Kotlin/Native
            // cross-heap cycle the GC cannot collect. Left set, every
            // 1:1 call leaked a peer and its Metal views, and a party
            // line leaked one per remote speaker and per republish.
            self.stateListener = nil
            self.candidateListener = nil
            self.videoListener = nil
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
