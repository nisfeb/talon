package io.nisfeb.talon.util

/**
 * Caveat: when packaged via `compose.desktop.application`'s native
 * distributions (.dmg, .msi, .deb), the JVM's stderr is NOT routed
 * to anywhere the user can see — packaged JVM apps detach from the
 * launching shell. Log lines from a Dock-launched app on macOS or
 * a double-clicked .msi installation on Windows go to /dev/null.
 *
 * Stage E will need a real log destination for distributed builds —
 * either redirect System.err to a rotating file under
 * `Path.of(System.getProperty("user.home"), ".local/share/talon/log")`
 * or switch to slf4j with a file appender configured at startup.
 * For development (`./gradlew :composeApp:run`) stderr is fine.
 */
actual object Log {
    actual fun i(tag: String, msg: String) {
        System.err.println("INFO  [$tag] $msg")
    }
    actual fun w(tag: String, msg: String, t: Throwable?) {
        System.err.println("WARN  [$tag] $msg")
        t?.printStackTrace(System.err)
    }
    actual fun e(tag: String, msg: String, t: Throwable?) {
        System.err.println("ERROR [$tag] $msg")
        t?.printStackTrace(System.err)
    }
}
