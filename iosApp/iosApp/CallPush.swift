import CallKit
import ComposeApp
import Foundation
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
class AppDelegate: NSObject, UIApplicationDelegate, PKPushRegistryDelegate, CXProviderDelegate {

    private var provider: CXProvider!
    // CallKit works in UUIDs; %trunk works in string call ids. Keep
    // both directions so an answer/end action maps back to the id the
    // Kotlin side knows.
    private var uuidToCallId: [UUID: String] = [:]
    private var callIdToUuid: [String: UUID] = [:]

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

        let registry = PKPushRegistry(queue: .main)
        registry.delegate = self
        registry.desiredPushTypes = [.voIP]
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

        // A ring-cancel un-rings a call we're showing.
        if (dict["event"] as? String) == "ring-cancel" {
            if let uuid = callIdToUuid[callId] {
                provider.reportCall(with: uuid, endedAt: nil, reason: .remoteEnded)
                forget(uuid)
            }
            completion()
            return
        }

        let uuid = UUID()
        uuidToCallId[uuid] = callId
        callIdToUuid[callId] = uuid

        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: from)
        update.hasVideo = false
        update.localizedCallerName = from

        // MUST happen before completion() — this is the report Apple
        // requires for every VoIP push.
        provider.reportNewIncomingCall(with: uuid, update: update) { _ in
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
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        if let callId = uuidToCallId[action.callUUID] {
            IosVoipBridge.shared.answer(callId: callId)
        }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        if let callId = uuidToCallId[action.callUUID] {
            IosVoipBridge.shared.decline(callId: callId)
            forget(action.callUUID)
        }
        action.fulfill()
    }

    private func forget(_ uuid: UUID) {
        if let callId = uuidToCallId.removeValue(forKey: uuid) {
            callIdToUuid.removeValue(forKey: callId)
        }
    }
}
