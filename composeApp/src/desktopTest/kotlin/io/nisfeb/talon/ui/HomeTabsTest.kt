package io.nisfeb.talon.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.screens.DmListScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.update.NoopUpdateInstallerHook
import io.nisfeb.talon.update.StaticUpdateRuntime
import io.nisfeb.talon.update.UpdateState
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/**
 * Party lines are the All view's third tab. The pane comes in as a
 * slot, so the tab only exists where a caller supplies one — this
 * pins both halves of that contract.
 */
class HomeTabsTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `party lines tab shows the supplied pane`() = runComposeUiTest {
        val (db, repo, updateState) = deps()
        setContent {
            TalonTheme(darkTheme = false) {
                DmListScreen(
                    db = db,
                    repo = repo,
                    drafts = InMemoryDraftStore(),
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
                    activeShip = "~sampel-palnet",
                    allShips = listOf("~sampel-palnet"),
                    partyLinesTab = { Text("party-pane") },
                )
            }
        }
        onNodeWithText("Party lines").assertIsDisplayed().performClick()
        onNodeWithText("party-pane").assertIsDisplayed()
        // and back out again
        onNodeWithText("Groups").performClick()
        onNodeWithText("party-pane").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `no third tab without a pane`() = runComposeUiTest {
        val (db, repo, updateState) = deps()
        setContent {
            TalonTheme(darkTheme = false) {
                DmListScreen(
                    db = db,
                    repo = repo,
                    drafts = InMemoryDraftStore(),
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
                    activeShip = "~zod",
                    allShips = listOf("~zod"),
                )
            }
        }
        onNodeWithText("DMs").assertIsDisplayed()
        onNodeWithText("Party lines").assertDoesNotExist()
    }

    private fun deps(): Triple<AppDatabase, TlonChatRepo, UpdateState> {
        val tmp = createTempDirectory(prefix = "talon-hometabs-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return Triple(
            db,
            TlonChatRepo(db),
            UpdateState(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                runtime = StaticUpdateRuntime(),
                installer = NoopUpdateInstallerHook(),
            ),
        )
    }
}
