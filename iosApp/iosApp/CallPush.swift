import CallKit
import ComposeApp
import Foundation
import Intents
import PushKit
import UIKit

/// Native incoming-call ringing on iOS: PushKit wakes the app (even
/// killed) on a VoIP push, and CallKit shows the system full-screen
/// ring UI. Apple requires that every VoIP push report a call to
/// CallKit synchronously in `didReceiveIncomingPushWith`, or the app
/// is terminated and its VoIP privileges revoked — so the two are one
/// mechanism.
///
/// The shared Kotlin call stack does the actual join; this file only
/// registers the token, rings, and hands the user's answer/decline to
/// `IosVoipBridge`.
/// As `IosCallKit` it also reports the calls Kotlin places, joins,
/// connects, mutes and ends, so they are phone calls to iOS the same
/// as the ones we receive.
class AppDelegate: NSObject, UIApplicationDelegate, PKPushRegistryDelegate, CXProviderDelegate, IosCallKit {
    // Requests we make of CallKit (start, answer, end, mute) go through
    // this; CallKit then asks us to perform them via the delegate.
    private let callController = CXCallController()
    // Ends we asked for ourselves: the perform is bookkeeping, not a
    // hang-up to forward — the Kotlin side already ended the call.
    private var endingLocally: Set<UUID> = []
    // Calls we placed: ended by the far side, they are "remote ended";
    // an incoming one nobody answered is "unanswered", which is what
    // Recents shows as Missed.
    private var outgoing: Set<UUID> = []

    private var provider: CXProvider!
    // Held, not local: PKPushRegistry.delegate is weak, so a registry
    // that only lived inside didFinishLaunching deallocated at the end
    // of launch and deregistered .voIP with it — neither the token
    // callback nor an incoming push could ever fire, and a backgrounded
    // iPhone never rang.
    private var pushRegistry: PKPushRegistry!
    // CallKit works in UUIDs; %trunk works in string call ids. Keep
    // both directions so an answer/end action maps back to the id the
    // Kotlin side knows.
    private var uuidToCallId: [UUID: String] = [:]
    private var callIdToUuid: [String: UUID] = [:]
    // Calls the user actually answered. Trunk emits %handled on every
    // accept, and the relay turns that into a ring-cancel — so without
    // this the cancel for our OWN accept reported the call ended and
    // CallKit tore down the in-call UI seconds into a live call.
    private var answered: Set<UUID> = []

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let config = CXProviderConfiguration()
        config.supportsVideo = false
        config.maximumCallGroups = 1
        config.maximumCallsPerCallGroup = 1
        config.supportedHandleTypes = [.generic]
        provider = CXProvider(configuration: config)
        provider.setDelegate(self, queue: nil)
        IosVoipBridge.shared.callKit = self

        pushRegistry = PKPushRegistry(queue: .main)
        pushRegistry.delegate = self
        pushRegistry.desiredPushTypes = [.voIP]
        return true
    }

    // MARK: - PushKit

    func pushRegistry(
        _ registry: PKPushRegistry,
        didUpdate pushCredentials: PKPushCredentials,
        for type: PKPushType
    ) {
        guard type == .voIP else { return }
        let hex = pushCredentials.token.map { String(format: "%02x", $0) }.joined()
        IosVoipBridge.shared.setVoipToken(hex: hex)
    }

    func pushRegistry(
        _ registry: PKPushRegistry,
        didReceiveIncomingPushWith payload: PKPushPayload,
        for type: PKPushType,
        completion: @escaping () -> Void
    ) {
        guard type == .voIP else { completion(); return }
        let dict = payload.dictionaryPayload
        let from = (dict["from"] as? String) ?? "Unknown"
        let callId = (dict["id"] as? String) ?? UUID().uuidString
        CallTrace.log("push \((dict["event"] as? String) ?? "ring") id=\(callId) from=\(from) reason=\((dict["reason"] as? String) ?? "-")")

        // A ring-cancel un-rings a call we're showing.
        if (dict["event"] as? String) == "ring-cancel" {
            if let uuid = callIdToUuid[callId], answered.contains(uuid) {
                // Our own accept produced this cancel. Satisfy the
                // PushKit contract by re-reporting the SAME uuid: CallKit
                // rejects it as already existing, and the live call — and
                // the audio session it owns — are untouched. Reporting a
                // fresh uuid here left a second call ringing forever
                // beside the live one: two bars, and CallKit's audio
                // going to the phantom while the real call went silent.
                CallTrace.log("ring-cancel \(callId): our own accept, re-reporting live uuid")
                let update = CXCallUpdate()
                update.remoteHandle = CXHandle(type: .generic, value: from)
                update.localizedCallerName = from
                provider.reportNewIncomingCall(with: uuid, update: update) { _ in
                    completion()
                }
                return
            }
            // iOS 13+ requires reportNewIncomingCall for EVERY VoIP push;
            // reportCall(with:endedAt:) does not satisfy it, and this
            // branch runs on essentially every call — repeat offenders
            // get the app terminated and VoIP delivery revoked.
            let uuid = callIdToUuid[callId] ?? UUID()
            let update = CXCallUpdate()
            update.remoteHandle = CXHandle(type: .generic, value: from)
            update.localizedCallerName = from
            // Why it was cancelled decides how Recents files it: the
            // caller gave up (missed), or another of the user's
            // devices answered (not missed). Older relays say nothing,
            // which reads as the caller giving up.
            let reason: CXCallEndedReason =
                (dict["reason"] as? String) == "answered" ? .answeredElsewhere : .unanswered
            CallTrace.log("ring-cancel \(callId): ending as \(reason == .answeredElsewhere ? "answered elsewhere" : "unanswered")")
            provider.reportNewIncomingCall(with: uuid, update: update) { _ in
                self.provider.reportCall(with: uuid, endedAt: nil, reason: reason)
                self.forget(uuid)
                completion()
            }
            return
        }

        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: from)
        update.hasVideo = false
        update.localizedCallerName = from

        // A duplicate delivery of the same ring must reuse the UUID we
        // already rang with. Minting a fresh one overwrites the mapping,
        // and the first CallKit call is then un-endable — no action on
        // it maps back to a call id, so it rings on after the caller
        // hangs up. Re-reporting the SAME UUID still satisfies the
        // every-push contract; CallKit rejects it as already-existing
        // and the live ring is left alone.
        if let existing = callIdToUuid[callId] {
            provider.reportNewIncomingCall(with: existing, update: update) { _ in
                completion()
            }
            return
        }

        let uuid = UUID()
        uuidToCallId[uuid] = callId
        callIdToUuid[callId] = uuid

        // MUST happen before completion() — this is the report Apple
        // requires for every VoIP push. A report CallKit refuses (Do Not
        // Disturb, a call already up elsewhere) rings nothing, so drop
        // the mapping too rather than leave an entry no answer or end
        // action will ever clear.
        provider.reportNewIncomingCall(with: uuid, update: update) { error in
            CallTrace.log("ring \(callId): reported \(error == nil ? "ok" : "REFUSED \(error!)")")
            if error != nil {
                self.forget(uuid)
            }
            completion()
        }
    }

    func pushRegistry(
        _ registry: PKPushRegistry,
        didInvalidatePushTokenFor type: PKPushType
    ) {
        // Token gone; the next didUpdate re-registers. Nothing to do.
    }

    // MARK: - CallKit

    func providerDidReset(_ provider: CXProvider) {
        uuidToCallId.removeAll()
        callIdToUuid.removeAll()
        answered.removeAll()
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        CallTrace.log("callkit: answer \(uuidToCallId[action.callUUID] ?? "?")")
        if let callId = uuidToCallId[action.callUUID] {
            answered.insert(action.callUUID)
            IosVoipBridge.shared.answer(callId: callId)
        }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        let uuid = action.callUUID
        CallTrace.log("callkit: end \(uuidToCallId[uuid] ?? "?")\(endingLocally.contains(uuid) ? " (ours)" : "")")
        if endingLocally.remove(uuid) != nil {
            forget(uuid)
            action.fulfill()
            return
        }
        if let callId = uuidToCallId[uuid] {
            IosVoipBridge.shared.end(callId: callId)
            forget(uuid)
        }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        provider.reportOutgoingCall(with: action.callUUID, startedConnectingAt: nil)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        if let callId = uuidToCallId[action.callUUID] {
            IosVoipBridge.shared.setMuted(callId: callId, muted: action.isMuted)
        }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        if let callId = uuidToCallId[action.callUUID] {
            IosVoipBridge.shared.setHeld(callId: callId, held: action.isOnHold)
        }
        action.fulfill()
    }

    // No didActivate/didDeactivate handoff on purpose: libwebrtc runs
    // its own RTCAudioSession (not manual-audio), the way every
    // working build has. Reporting the call to CallKit and letting
    // WebRTC own the session is what worked; the handoff without
    // useManualAudio left playout dead on a reported call.

    // MARK: - IosCallKit (reports from the Kotlin call stack)

    func reportOutgoing(id: String, handle: String, name: String) {
        CallTrace.log("app: outgoing \(id) to \(handle)\(callIdToUuid[id] != nil ? " (already reported)" : "")")
        if callIdToUuid[id] != nil { return }
        let uuid = UUID()
        uuidToCallId[uuid] = id
        callIdToUuid[id] = uuid
        outgoing.insert(uuid)
        // The handle is the ship, so a Recents tap can dial it; the
        // name is what Recents shows.
        let start = CXStartCallAction(call: uuid, handle: CXHandle(type: .generic, value: handle))
        callController.request(CXTransaction(action: start)) { error in
            if let error = error {
                // A cellular call up, or Do Not Disturb: the call goes
                // on app-managed, exactly as before this existed.
                NSLog("talon: CallKit refused the outgoing call: \(error)")
                self.forget(uuid)
                return
            }
            let update = CXCallUpdate()
            update.localizedCallerName = name
            self.provider.reportCall(with: uuid, updated: update)
        }
    }

    /// A tap on a Recents entry. iOS hands it over as a start-call
    /// intent whose handle is the one we reported: the ship.
    static func callBack(from activity: NSUserActivity) {
        let handle: String?
        if let intent = activity.interaction?.intent as? INStartCallIntent {
            handle = intent.contacts?.first?.personHandle?.value
        } else if let intent = activity.interaction?.intent as? INStartAudioCallIntent {
            handle = intent.contacts?.first?.personHandle?.value
        } else {
            handle = nil
        }
        if let handle = handle { IosVoipBridge.shared.callBack(handle: handle) }
    }

    func reportAnswered(id: String) {
        CallTrace.log("app: answered \(id)\(callIdToUuid[id] == nil ? " (unknown to CallKit)" : "")")
        guard let uuid = callIdToUuid[id], !answered.contains(uuid) else { return }
        callController.request(CXTransaction(action: CXAnswerCallAction(call: uuid))) { _ in }
    }

    func reportConnected(id: String) {
        guard let uuid = callIdToUuid[id] else { return }
        provider.reportOutgoingCall(with: uuid, connectedAt: nil)
    }

    func reportEnded(id: String, remote: Bool) {
        CallTrace.log("app: ended \(id) remote=\(remote)\(callIdToUuid[id] == nil ? " (unknown to CallKit)" : "")")
        guard let uuid = callIdToUuid[id] else { return }
        if remote {
            let reason: CXCallEndedReason =
                (outgoing.contains(uuid) || answered.contains(uuid)) ? .remoteEnded : .unanswered
            provider.reportCall(with: uuid, endedAt: nil, reason: reason)
            forget(uuid)
        } else {
            endingLocally.insert(uuid)
            callController.request(CXTransaction(action: CXEndCallAction(call: uuid))) { error in
                if error != nil {
                    self.endingLocally.remove(uuid)
                    self.forget(uuid)
                }
            }
        }
    }

    func reportMuted(id: String, muted: Bool) {
        guard let uuid = callIdToUuid[id] else { return }
        callController.request(CXTransaction(action: CXSetMutedCallAction(call: uuid, muted: muted))) { _ in }
    }

    private func forget(_ uuid: UUID) {
        answered.remove(uuid)
        outgoing.remove(uuid)
        if let callId = uuidToCallId.removeValue(forKey: uuid) {
            callIdToUuid.removeValue(forKey: callId)
        }
    }
}

/// A call trace the user can read in the Files app (Documents is
/// exposed there for last-crash.txt already). Every push, every
/// CallKit action and every report from the app, timestamped — the
/// iOS side of what adb gives us on Android. Bounded.
enum CallTrace {
    private static let url: URL? = FileManager.default
        .urls(for: .documentDirectory, in: .userDomainMask).first?
        .appendingPathComponent("call-trace.txt")
    private static let stamp: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MM-dd HH:mm:ss.SSS"
        return f
    }()

    static func log(_ line: String) {
        NSLog("talon call: %@", line)
        guard let url = url else { return }
        let entry = "\(stamp.string(from: Date())) \(line)\n"
        if let h = try? FileHandle(forWritingTo: url) {
            defer { try? h.close() }
            if let size = try? h.seekToEnd(), size > 200_000 {
                try? h.truncate(atOffset: 0)
            }
            h.write(Data(entry.utf8))
        } else {
            try? entry.write(to: url, atomically: true, encoding: .utf8)
        }
    }
}
