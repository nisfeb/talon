package io.nisfeb.talon.call

/**
 * The seam between Kotlin and the Swift WebRTC implementation.
 *
 * WebRTC lives in the Xcode project, added as a Swift Package, rather
 * than being pulled into Gradle through cinterop. Android and desktop
 * already own their media stacks per platform ([AndroidPeerLink],
 * [DesktopPeerLink]); Swift is the iOS equivalent, and it is the
 * language libwebrtc's iOS API is designed for.
 *
 * Everything here is deliberately plain — callbacks, primitives, and
 * simple data classes. Swift *cannot* implement a Kotlin `suspend`
 * function or hand back a `StateFlow`, and [PeerLink] / [CallEngine]
 * are built from both. So Swift implements this callback shape and
 * [IosPeerLink] / [IosCallEngine] adapt it to the interfaces the rest
 * of the app already speaks.
 */
interface NativeRtcPeer {

    /** Media state transitions, from WebRTC's own connection state. */
    fun onStateChange(listener: (MediaState) -> Unit)

    /**
     * Locally gathered ICE candidates. Only called when the peer was
     * created with `trickle = true`; a non-trickle peer folds its
     * candidates into the SDP instead.
     */
    fun onIceCandidate(listener: (IceCandidate) -> Unit)

    /**
     * Create an offer. [done] receives the SDP, or an error string if
     * it failed — exactly one of the two is non-null.
     *
     * A non-trickle peer must not call [done] until ICE gathering has
     * completed, because the SDP is the only chance its candidates get
     * to reach the far end.
     */
    fun createOffer(done: (String?, String?) -> Unit)

    /** Apply [remoteSdp] as an offer and answer it. Same contract. */
    fun createAnswer(remoteSdp: String, done: (String?, String?) -> Unit)

    /** Apply [remoteSdp] as an answer. [done] gets null, or an error. */
    fun applyAnswer(remoteSdp: String, done: (String?) -> Unit)

    fun addIceCandidate(candidate: IceCandidate)

    /**
     * Remote audio level, 0..1, or -1 when unknown. A plain Double
     * rather than a nullable Float: primitives cross to Swift without
     * boxing, and there is no useful distinction between "silent" and
     * "can't tell" for a speaking dot.
     */
    fun remoteAudioLevel(): Double

    /**
     * Our own microphone level, 0..1, or -1 when unknown.
     *
     * Named unlike anything on [PeerLink], and that is the whole
     * reason for the name.
     *
     * Kotlin/Native maps a no-argument function to a bare Objective-C
     * selector, so two exported declarations with the same name and
     * *incompatible return types* collide, and one gets an underscore.
     * PeerLink.audioLevel returns Float? — a boxed object — where this
     * returns a primitive Double, so `audioLevel` and then
     * `localAudioLevel` both arrived in Swift as `..._()` and the
     * class stopped conforming.
     *
     * Sharing a name is not enough on its own: close, setMuted,
     * applyAnswer and create are all shared with PeerLink and are
     * fine, because their signatures either match exactly or differ in
     * arity, which produces a different selector anyway. It is the
     * same-selector, different-type case that breaks.
     */
    fun micLevel(): Double

    fun setMuted(muted: Boolean)

    /**
     * Open or close the camera. [done] gets null on success, or a
     * short reason it failed — no device, permission refused, already
     * in use — which the UI shows rather than leaving a dead button.
     * A `String?` for the same reason applyAnswer uses one: it avoids
     * the boxing that a `(Boolean) -> Unit` would incur.
     *
     * Two arguments, which is not an accident: a no-argument export
     * whose selector matched something on [CallEngine] with a
     * different return type is what silently renamed `audioLevel` and
     * broke conformance twice. Every name added here is unique to this
     * interface for the same reason.
     */
    fun setCameraEnabled(enabled: Boolean, done: (String?) -> Unit)

    /**
     * Flip between front and back cameras. No-op when the camera is off.
     * No-arg is safe here: the selector is unique to this interface and
     * returns Unit everywhere, so it can't collide the way a same-named,
     * different-return-type export would (see [micLevel]).
     */
    fun switchCamera()

    /**
     * Who is showing a camera, whenever that changes.
     *
     * Passes [VideoState] rather than two Booleans: Kotlin/Native
     * boxes primitives in lambda parameter position, so `(Boolean,
     * Boolean) -> Unit` reaches Swift as `(KotlinBoolean,
     * KotlinBoolean) -> Void`. A data class crosses unboxed and reads
     * better on both sides.
     */
    fun onVideoChange(listener: (VideoState) -> Unit)

    /**
     * The `RTCMTLVideoView` showing our camera, or theirs, or null
     * before there is one.
     *
     * Typed `Any?` because the Kotlin side has no UIKit types in
     * scope here and does not need them: it hands the value straight
     * to Compose's UIKitView, which wants a UIView anyway.
     */
    fun localVideoView(): Any?
    fun remoteVideoView(): Any?

    /** Must be idempotent: the adapters can close more than once. */
    fun close()
}

/**
 * Builds peers. Supplied by the iOS app target and handed to
 * `MainViewController`; null there keeps calls dark exactly as it does
 * on a platform with no engine at all.
 */
interface NativeRtcFactory {
    /**
     * @param sendAudio capture the microphone and send it. False for a
     *   party line's down links, which only receive.
     * @param trickle emit candidates as they are found (party lines,
     *   where the far end is a server on a public address). False for
     *   1:1 calls, which exchange one complete SDP over ames — see the
     *   trunkline design, D3.
     */
    fun create(
        iceServers: List<IceServer>,
        sendAudio: Boolean,
        trickle: Boolean,
    ): NativeRtcPeer
}
