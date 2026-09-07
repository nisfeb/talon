import SwiftUI

@main
struct iOSApp: App {
    // Hosts PushKit + CallKit for native incoming-call ringing
    // (see CallPush.swift). Without the adaptor there is no
    // AppDelegate to receive VoIP pushes.
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
                // A tap on one of our calls in the Phone app's Recents.
                .onContinueUserActivity("INStartCallIntent") { AppDelegate.callBack(from: $0) }
                .onContinueUserActivity("INStartAudioCallIntent") { AppDelegate.callBack(from: $0) }
        }
    }
}
