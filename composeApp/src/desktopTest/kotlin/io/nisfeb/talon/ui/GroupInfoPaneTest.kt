package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ChannelGroupEntity
import io.nisfeb.talon.data.GroupEntity
import io.nisfeb.talon.data.MessageMediaEntity
import io.nisfeb.talon.data.notifyLevelOf
import io.nisfeb.talon.urbit.MediaCategory
import io.nisfeb.talon.ui.screens.GroupInfoPane
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.SettingsSyncImpl
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The pane beside a group channel: who is in it, inviting, notifications,
 * shared media and leaving. Each action is checked by the poke it sends.
 */
@OptIn(ExperimentalTestApi::class)
class GroupInfoPaneTest {
    private val flag = "~bus/crew"
    private val nest = "chat/~bus/general"

    private fun record(privacy: String) = """{
        "meta":{"title":"The Crew","description":"","image":"","cover":""},
        "admins":[],
        "seats":{"~bus":{"roles":[],"joined":0},"~zod":{"roles":[],"joined":0},"~nec":{"roles":[],"joined":0}},
        "admissions":{"privacy":"$privacy","banned":{"ships":[],"ranks":[]},"invited":{},"pending":{},"requests":{}}}"""

    private val opened = mutableListOf<String>()

    private fun pane(
        whom: String = nest,
        privacy: String = "private",
        media: List<String> = emptyList(),
        // Closes the pane when set false: a write must outlive it.
        shown: androidx.compose.runtime.MutableState<Boolean> = androidx.compose.runtime.mutableStateOf(true),
        block: ComposeUiTest.(FakeShip, AppDatabase) -> Unit,
    ) {
        val tmp = createTempDirectory(prefix = "talon-info-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod").apply { scries["groups/v2/groups/$flag"] = record(privacy) }
        val sync = SettingsSyncImpl(db = db, aiSettings = FakeAiSettings()).apply { attach(ship.channel) }
        val repo = TlonChatRepo(db, settingsSync = sync).apply { attachForTest(ship.channel, "~zod") }
        runBlocking {
            db.groups().upsertGroups(listOf(GroupEntity(flag, "The Crew", null)))
            db.groups().upsertChannelGroups(listOf(ChannelGroupEntity(nest, flag)))
            db.messageMedia().insertAll(media.mapIndexed { i, cat -> MessageMediaEntity(whom, "$i", "https://x.test/$i", cat, null, i.toLong(), "~bus") })
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(scope)
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalClipboardManager provides object : androidx.compose.ui.platform.ClipboardManager {
                            override fun getText(): androidx.compose.ui.text.AnnotatedString? = null
                            override fun setText(annotatedString: androidx.compose.ui.text.AnnotatedString) { opened += "copied ${annotatedString.text}" }
                        }) {
                        if (shown.value) GroupInfoPane(
                            db = db, repo = repo, whom = whom,
                            onOpenCategory = { opened += "media $it" },
                            onOpenMembers = { opened += "members" },
                        )
                        }
                    }
                }
                block(ship, db)
            }
        } finally {
            scope.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.showing(text: String) = waitUntil(timeoutMillis = 5_000) { shows(text) }

    @Test
    fun `it names the group and counts its members from the ship`() = pane { _, _ ->
        showing("3 members")
        showing("The Crew")
        onNodeWithText("Group members (3)").performClick()
        assertEquals(listOf("members"), opened)
        assertTrue(!shows("Join by code"), "a private group has no join code")
    }

    @Test
    fun `a public group offers a code to join by`() = pane(privacy = "public") { _, _ ->
        showing("3 members")
        row("Join the group by code")
    }

    @Test
    fun `inviting sends the invite and says who went`() = pane { ship, _ ->
        showing("3 members")
        row("Invite someone to the group").performClick()
        onNode(hasSetTextAction()).performTextInput("~mastyr-bottec")
        onNodeWithText("Invite").performClick()
        showing("Invited")
        val invite = ship.pokesTo("groups").single()
        assertTrue("~mastyr-bottec" in invite.json.toString() && flag in invite.json.toString(), invite.json.toString())
    }

    @Test
    fun `a notification level is kept here and on the ship`() = pane { ship, db ->
        showing("✓ Same as the group (Mentions only)")
        // The channel's options come first, the group's after them.
        onAllNodesWithText("All messages")[0].performClick()
        showing("✓ All messages")
        assertEquals("all", runBlocking { db.notifyPrefs().stream(nest).first()?.level })
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("settings").any { nest in it.json.toString() } }
    }

    @Test
    fun `shared media is counted by kind and opens that kind`() = pane(media = listOf("Photo", "Photo", "Link")) { _, _ ->
        showing("📷")
        onNodeWithText("📷").performClick()
        assertEquals(listOf("media ${MediaCategory.Photo}"), opened)
        assertTrue(shows("🔗") && !shows("🎥"))
    }

    /** A row further down the lazy list: scrolled to by the list, since it may not be composed yet. */
    private fun ComposeUiTest.row(text: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        onNode(androidx.compose.ui.test.hasScrollToNodeAction()).performScrollToNode(androidx.compose.ui.test.hasText(text, substring = true))
        return onNodeWithText(text, substring = true)
    }

    /** The last row of a lazy list: scrolled to by the list, since it may not be composed yet. */
    private fun ComposeUiTest.leaveRow(): androidx.compose.ui.test.SemanticsNodeInteraction {
        onNode(androidx.compose.ui.test.hasScrollToNodeAction()).performScrollToNode(androidx.compose.ui.test.hasText("Leave group"))
        return onNodeWithText("Leave group")
    }

    @Test
    fun `leaving asks first, and goes once the ship agrees`() = pane { ship, db ->
        showing("3 members")
        leaveRow().performClick()
        showing("Leave The Crew?")
        onNodeWithText("Cancel").performClick()
        assertTrue(ship.pokesTo("groups").isEmpty())
        leaveRow().performClick()
        onNodeWithText("Leave").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.groups().getGroup(flag) } == null }
        assertEquals("group-leave", ship.pokesTo("groups").single().mark)
    }

    @Test
    fun `a leave the ship refuses says so, and the group stays`() = pane { ship, db ->
        ship.refuse = { if (it.mark == "group-leave") "not a member" else null }
        showing("3 members")
        leaveRow().performClick()
        onNodeWithText("Leave").performClick()
        showing("not a member")
        assertTrue(shows("Leave The Crew?"), "the dialog stays to say why")
        assertNotNull(runBlocking { db.groups().getGroup(flag) })
    }

    @Test
    fun `a DM has no group rows`() = pane(whom = "~bus") { _, _ ->
        showing("Notifications")
        assertTrue(!shows("Group members") && !shows("Invite someone") && !shows("Leave group") && !shows("Same as the group"))
        assertTrue(shows("No shared media yet"))
    }

    // sneagan, 2026-10-07: the pane headed the channel's settings with the
    // group's name and count, beside the group's members, invite and leave,
    // so what was whose was anyone's guess.
    @Test
    fun `the channel comes first by its own name, and the group's own things under the group`() = pane { _, _ ->
        showing("3 members")
        showing("general")
        showing("in The Crew")
        showing("This channel")
        showing("Notifications for the whole group")
        showing("Invite someone to the group")
    }

    private fun settingsPoke(ship: FakeShip, key: String) =
        ship.pokesTo("settings").map { it.json.toString() }.lastOrNull { key in it }

    // sneagan, 2026-10-07: "if that's per channel how do you set the whole
    // group's notification". The group's level is kept beside the channels'
    // and its channels follow it until one is given its own.
    @Test
    fun `the group's level is kept, its channels follow it, and a channel can go back to it`() = pane { ship, db ->
        showing("✓ Same as the group (Mentions only)")
        // The group's options are the second set.
        onAllNodesWithText("Off")[1].performClick()
        showing("✓ Same as the group (Off)")
        waitUntil(timeoutMillis = 5_000) { settingsPoke(ship, "group/$flag") != null }
        assertEquals(
            """{"put-entry":{"desk":"talon","bucket-key":"notify-prefs","entry-key":"group/~bus/crew","value":"{\"level\":\"none\"}"}}""",
            settingsPoke(ship, "group/$flag"),
        )
        assertEquals("none", runBlocking { db.notifyLevelOf(nest) }, "the channel follows the group")

        onAllNodesWithText("All messages")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.notifyPrefs().levelFor(nest) } == "all" }
        assertEquals("all", runBlocking { db.notifyLevelOf(nest) }, "its own wins")

        onNodeWithText("Same as the group (Off)").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.notifyPrefs().levelFor(nest) } == null }
        waitUntil(timeoutMillis = 5_000) { settingsPoke(ship, "del-entry") != null }
        assertEquals(
            """{"del-entry":{"desk":"talon","bucket-key":"notify-prefs","entry-key":"chat/~bus/general"}}""",
            settingsPoke(ship, "del-entry"),
        )
        assertEquals("none", runBlocking { db.notifyLevelOf(nest) })
    }

    // The write runs on the repo's scope: closing the pane at once does not
    // take it along. Real time, not the test's virtual clock.
    @Test
    fun `a level chosen as the pane closes still reaches the ship`() {
        val shown = androidx.compose.runtime.mutableStateOf(true)
        pane(shown = shown) { ship, db ->
            showing("Notifications for the whole group")
            onAllNodesWithText("All messages")[1].performClick()
            shown.value = false
            waitForIdle()
            val until = System.currentTimeMillis() + 5_000
            while (settingsPoke(ship, "group/$flag") == null && System.currentTimeMillis() < until) Thread.sleep(50)
            assertTrue(settingsPoke(ship, "group/$flag")?.contains("all") == true, "the ship heard it")
            assertEquals("all", runBlocking { db.notifyPrefs().levelFor("group/$flag") })
        }
    }

    // Copied here, pasted in Talon's or Tlon's join box or in a chat (2026-10-09).
    @Test
    fun `the group's reference is copied in Tlon's form, for any group`() = pane { _, _ ->
        showing("3 members")
        row("Group reference")
        onNodeWithText("Copy").performClick()
        assertEquals(listOf("copied /1/group/$flag"), opened)
        showing("Copied")
    }

    @Test
    fun `a DM has no group reference`() = pane(whom = "~bus") { _, _ ->
        waitForIdle()
        assertTrue(!shows("Group reference"))
    }
}
