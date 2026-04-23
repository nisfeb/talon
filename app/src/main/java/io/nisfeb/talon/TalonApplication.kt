package io.nisfeb.talon

import android.app.Application
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.DraftStore
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.urbit.UrbitSession
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class TalonApplication : Application() {
    lateinit var http: OkHttpClient
        private set
    lateinit var db: AppDatabase
        private set
    lateinit var sessionStore: SessionStore
        private set
    lateinit var session: UrbitSession
        private set
    lateinit var repo: TlonChatRepo
        private set
    lateinit var drafts: DraftStore
        private set
    lateinit var shortcuts: ShortcutsPublisher
        private set

    override fun onCreate() {
        super.onCreate()
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // long-lived SSE
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
        db = AppDatabase.build(this)
        sessionStore = SessionStore(this)
        session = UrbitSession(http, sessionStore)
        repo = TlonChatRepo(db)
        drafts = DraftStore(this)
        shortcuts = ShortcutsPublisher(this, db)
        Notifications.ensureChannel(this)
    }
}
