package io.nisfeb.talon.notify

// Manual smoke test for SystemNotifier — emits a single native
// notification through the platform-native path. Not wired into
// production; invoked via `./gradlew :composeApp:notifierSmoke`.
fun main() {
    val n = SystemNotifier(trayFallback = { _, _ ->
        System.err.println("SystemNotifier fell back to TRAY — native path failed")
    })
    println("Sending native notification…")
    n.notify(
        "Talon",
        "Native notification test — if this is rendered by your DE's notification daemon, the new path works.",
    )
    Thread.sleep(2500)
    println("Done. Check your notification panel.")
}
