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
        }
    }
}
