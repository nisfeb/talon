package io.nisfeb.talon.relay

import org.slf4j.LoggerFactory

/**
 * FCM dispatch surface. Real impl lights up once the operator
 * supplies a Firebase Admin SDK service-account JSON via
 * `FIREBASE_CREDENTIALS_PATH`; until then we log the would-have-
 * been-pushed payload at INFO so end-to-end SSE→relay→would-push
 * can be smoke-tested without having to wire FCM first.
 *
 * Payload shape (hint-only, per the design doc):
 *   data = {
 *     "event": "new-message",
 *     "patp":  "<ship>",
 *     "whom":  "<conversation>",
 *     "id":    "<event-id>"   // for client-side dedup vs SSE
 *   }
 *   No `notification` key — Android's `MessagingService` posts the
 *   notification client-side after pulling content via the live SSE.
 *   That keeps message bodies out of FCM.
 */
class Push(private val credentialsPath: String?) {

    private val log = LoggerFactory.getLogger("Push")
    private val firebase: Any? by lazy { initFirebase() }

    fun send(fcmToken: String, patp: String, whom: String, eventId: Long) {
        val data = mapOf(
            "event" to "new-message",
            "patp" to patp,
            "whom" to whom,
            "id" to eventId.toString(),
        )
        if (firebase == null) {
            log.info("[push:stub] token=${fcmToken.take(8)}… data=$data")
            return
        }
        // Real send — reflectively invoked so we don't take a hard
        // compile-time dep on com.google.firebase.* when the operator
        // hasn't wired credentials. The class is on the classpath
        // either way (build.gradle pulls firebase-admin); the lazy
        // [initFirebase] above is what gates initialization.
        try {
            sendViaAdmin(fcmToken, data)
        } catch (e: Throwable) {
            // Don't let FCM failures kill the SSE consumer — a single
            // bad token shouldn't drop notifications for everyone on
            // the same ship. Log + move on; the watchdog will retry.
            log.warn("FCM send failed for ${fcmToken.take(8)}…: ${e.message}")
        }
    }

    /** Lazy-init Firebase Admin from the credentials JSON. Returns
     *  null when no path is configured, in which case [send] falls
     *  through to the log-only stub. */
    private fun initFirebase(): Any? {
        val path = credentialsPath ?: return null
        return try {
            val credsCls = Class.forName("com.google.auth.oauth2.GoogleCredentials")
            val creds = credsCls
                .getMethod("fromStream", java.io.InputStream::class.java)
                .invoke(null, java.io.FileInputStream(path))
            val optionsCls = Class.forName("com.google.firebase.FirebaseOptions")
            val builder = optionsCls.getMethod("builder").invoke(null)
            val builderCls = Class.forName("com.google.firebase.FirebaseOptions\$Builder")
            builderCls.getMethod("setCredentials", credsCls).invoke(builder, creds)
            val options = builderCls.getMethod("build").invoke(builder)
            val firebaseAppCls = Class.forName("com.google.firebase.FirebaseApp")
            firebaseAppCls.getMethod("initializeApp", optionsCls).invoke(null, options)
                .also { log.info("Firebase Admin initialized from $path") }
        } catch (e: Throwable) {
            log.warn("Firebase init failed for $path: ${e.message}")
            null
        }
    }

    private fun sendViaAdmin(fcmToken: String, data: Map<String, String>) {
        // Reflective build of Message.builder().setToken(...).putAllData(...).build()
        val messageCls = Class.forName("com.google.firebase.messaging.Message")
        val builder = messageCls.getMethod("builder").invoke(null)
        val builderCls = Class.forName("com.google.firebase.messaging.Message\$Builder")
        builderCls.getMethod("setToken", String::class.java).invoke(builder, fcmToken)
        builderCls.getMethod("putAllData", Map::class.java).invoke(builder, data)
        val message = builderCls.getMethod("build").invoke(builder)

        val msgServiceCls = Class.forName("com.google.firebase.messaging.FirebaseMessaging")
        val service = msgServiceCls.getMethod("getInstance").invoke(null)
        msgServiceCls.getMethod("send", messageCls).invoke(service, message)
    }
}
