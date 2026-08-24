package io.nisfeb.talon.shots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.ui.InMemoryDraftStore
import io.nisfeb.talon.ui.InMemoryUiSettings
import io.nisfeb.talon.ui.screens.DmChatScreen
import io.nisfeb.talon.ui.screens.DmListScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.update.NoopUpdateInstallerHook
import io.nisfeb.talon.update.StaticUpdateRuntime
import io.nisfeb.talon.update.UpdateState
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.util.createAppHttpClient
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/**
 * Renders App Store screenshots headlessly at the exact 6.9" size Apple
 * requires (1290x2796 @3x). Staged demo data only — never a real ship.
 * Run:  ./gradlew :composeApp:desktopTest --tests '*StoreScreenshots*'
 * Output: PNG files under build/store-screenshots/
 */
class StoreScreenshots {

    private val outDir = File("build/store-screenshots").apply { mkdirs() }
    private val us = "~sampel-palnet"

    private object StubAi : AiSettingsRepository {
        override val state: StateFlow<AiSettings.Config> = MutableStateFlow(
            AiSettings.Config(
                provider = AiSettings.Provider.Anthropic,
                apiKey = "",
                model = null,
                smartFeaturesEnabled = false,
                catchMeUpEnabled = false,
            ),
        )
        override var onStateChange: ((AiSettings.Config, Boolean) -> Unit)? = null
        override fun update(
            provider: AiSettings.Provider,
            apiKey: String,
            model: String?,
            baseUrl: String?,
        ) {}
        override fun setFeature(feature: AiSettings.Feature, enabled: Boolean) {}
        override fun setSyncEnabled(enabled: Boolean) {}
        override fun setBraveApiKey(key: String) {}
        override fun setPrompt(kind: AiSettings.PromptKind, value: String) {}
        override fun applyRemote(config: AiSettings.Config) {}
        override fun clear() {}
    }

    private fun text(msg: String) = """[{"inline":[${jsonStr(msg)}]}]"""
    private fun jsonStr(s: String) = "\"" + s.replace("\"", "\\\"") + "\""

    private fun seed(db: AppDatabase) = runBlocking {
        val now = nowMs()
        val min = 60_000L
        db.contacts().upsertAll(
            listOf(
                ContactEntity(us, "You", null, null, color = "#4C7EF3"),
                ContactEntity("~litzod", "Maya", null, null, color = "#E8674C"),
                ContactEntity("~timzod", "Sam", null, null, color = "#3FA470"),
                ContactEntity("~davzod", "Priya", null, null, color = "#8A5CC9"),
                ContactEntity("~fogzod", "Ben", null, null, color = "#C9A03C"),
            ),
        )
        fun dm(whom: String, id: Int, author: String, mins: Long, body: String) =
            MessageEntity(
                whom = whom, id = "0v$id", author = author,
                sentMs = now - mins * min, contentJson = text(body), kind = "dm",
            )
        db.messages().upsertAll(
            listOf(
                dm("~litzod", 1, "~litzod", 95, "Did you see the eclipse photos from the roof?"),
                dm("~litzod", 2, us, 90, "Just now — the third one is unreal"),
                dm("~litzod", 3, "~litzod", 88, "Right?? Zero editing, straight off the camera"),
                dm("~litzod", 4, us, 45, "Posting them to the group later?"),
                dm("~litzod", 5, "~litzod", 8, "Yeah, uploading tonight. Dinner first — ramen place by the park?"),
                dm("~timzod", 11, "~timzod", 130, "Ship migration went clean, everything's back up"),
                dm("~timzod", 12, us, 125, "Nice. Any data loss?"),
                dm("~timzod", 13, "~timzod", 32, "None — subscriptions resumed on their own"),
                dm("~davzod", 21, "~davzod", 300, "Book club is Thursday, we're on chapter 9"),
                dm("~davzod", 22, us, 280, "I'll actually be caught up this time"),
                dm("~fogzod", 31, "~fogzod", 1500, "Thanks again for the sourdough starter!"),
            ),
        )
    }

    private fun shot(name: String, dark: Boolean = false, warmMs: Long = 4_000, content: @Composable () -> Unit) {
        ImageComposeScene(
            width = 1290,
            height = 2796,
            density = Density(3f),
        ).use { scene ->
            scene.setContent {
                TalonTheme(darkTheme = dark) {
                    // Same root wrapper App.kt uses, so the window
                    // background follows the theme.
                    androidx.compose.material3.Surface(
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    ) { content() }
                }
            }
            // Pump frames so Room flows / LaunchedEffects settle.
            var t = 0L
            val deadline = System.currentTimeMillis() + warmMs
            while (System.currentTimeMillis() < deadline) {
                scene.render(t)
                t += 16_000_000L
                Thread.sleep(50)
            }
            val image = scene.render(t)
            val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(outDir, "$name.png").writeBytes(png)
            println("wrote ${File(outDir, "$name.png").absolutePath} (${png.size} bytes)")
        }
    }

    @Test
    fun render() {
        val tmp = createTempDirectory(prefix = "talon-shots-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "shots.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        seed(db)
        val repo = TlonChatRepo(db)
        val drafts = InMemoryDraftStore()
        val http = createAppHttpClient()
        val updateState = UpdateState(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            runtime = StaticUpdateRuntime(),
            installer = NoopUpdateInstallerHook(),
        )

        shot("01-home") {
            DmListScreen(
                db = db,
                repo = repo,
                drafts = drafts,
                updateState = updateState,
                onOpenConversation = {},
                onOpenSearch = {},
                onNewMessage = {},
                onSignOut = {},
                onOpenSelfProfile = {},
                onOpenStatusFeed = {},
                onOpenBookmarks = {},
                onOpenActivity = {},
                onOpenSettings = {},
                activeShip = us,
                allShips = listOf(us),
            )
        }

        shot("02-chat") {
            DmChatScreen(
                db = db,
                repo = repo,
                drafts = drafts,
                http = http,
                aiSettings = StubAi,
                uiSettings = InMemoryUiSettings(),
                ourPatp = us,
                whom = "~litzod",
                onBack = {},
                onOpenThread = {},
                onOpenConversation = {},
                onOpenImage = {},
                onOpenSelfProfile = {},
            )
        }

        shot("03-home-dark", dark = true) {
            DmListScreen(
                db = db,
                repo = repo,
                drafts = drafts,
                updateState = updateState,
                onOpenConversation = {},
                onOpenSearch = {},
                onNewMessage = {},
                onSignOut = {},
                onOpenSelfProfile = {},
                onOpenStatusFeed = {},
                onOpenBookmarks = {},
                onOpenActivity = {},
                onOpenSettings = {},
                activeShip = us,
                allShips = listOf(us),
            )
        }

        runCatching { db.close() }
        tmp.deleteRecursively()
    }
}
