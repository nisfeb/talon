package io.nisfeb.talon.notify

// Manual smoke test for SystemNotifier — emits a single notification
// through the platform-native path on Linux. macOS and Windows go
// through the AWT tray fallback by design (so notifications attribute
// to the .app bundle's icon, not osascript/Script Editor). Not wired
// into production; invoked via `./gradlew :composeApp:notifierSmoke`.
fun main() {
    val osName = System.getProperty("os.name").lowercase()
    val expectsFallback = "linux" !in osName
    val n = SystemNotifier(trayFallback = { _, _ ->
        if (expectsFallback) {
            System.err.println("SystemNotifier delegated to TRAY (expected on this OS)")
        } else {
            System.err.println("SystemNotifier fell back to TRAY — native path failed")
        }
    })
    println("Sending native notification…")
    n.notify(
        "Talon",
        "Native notification test — if this is rendered by your DE's notification daemon, the new path works.",
    )
    Thread.sleep(2500)
    println("Done. Check your notification panel.")
}
