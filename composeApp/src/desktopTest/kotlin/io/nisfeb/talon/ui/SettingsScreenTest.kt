package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import io.ktor.client.engine.mock.respond
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import io.nisfeb.talon.call.CallController
import io.nisfeb.talon.call.CallEngineProvider
import io.nisfeb.talon.notify.NotificationHealth
import io.nisfeb.talon.notify.NoopSystemNotificationProbe
import io.nisfeb.talon.notify.SystemNotificationProbe
import io.nisfeb.talon.notify.SystemNotificationState
import io.nisfeb.talon.ui.screens.SettingsScreen
import io.nisfeb.talon.ui.theme.InMemoryThemePreference
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.ui.theme.ThemePreference
import io.nisfeb.talon.urbit.FakeAiSettings
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.UrbitSession
import kotlin.test.Test
import kotlinx.datetime.toInstant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings, as the screen writes them: each control changes the stored
 * setting the rest of the app reads, and shows what is stored.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    private val ui = InMemoryUiSettings()
    private val theme = InMemoryThemePreference()
    private val patpChanges = mutableListOf<Boolean>()
    private val did = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun settings(
        health: NotificationHealth? = null,
        probe: SystemNotificationProbe = NoopSystemNotificationProbe,
        calls: CallController? = null,
        pinCandidates: List<Pair<String, String>> = emptyList(),
        ai: FakeAiSettings = FakeAiSettings(),
        fontRepo: FontRepo? = null,
        block: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalFontRepo provides fontRepo,
                LocalClipboardManager provides object : ClipboardManager {
                    override fun getText(): AnnotatedString? = null
                    override fun setText(annotatedString: AnnotatedString) { did += "copied ${annotatedString.text}" }
                },
            ) {
                TalonTheme(darkTheme = false) {
                    SettingsScreen(
                        aiSettings = ai, themePreference = theme, uiSettings = ui, onBack = {},
                        onOpenShareLoginQr = { did += "login qr" },
                        onAlwaysPatpChanged = { patpChanges += it },
                        notificationHealth = health, systemNotificationProbe = probe, callController = calls,
                        homePinCandidates = pinCandidates,
                    )
                }
            }
        }
        waitForIdle()
        block()
    }

    private fun ComposeUiTest.tap(text: String) {
        val node = onAllNodesWithText(text)[0]
        runCatching { node.performScrollTo() }
        node.performClick()
        waitForIdle()
    }

    /** The switch drawn nearest [label]: the rows share one parent, so nearness is what ties them. */
    private fun ComposeUiTest.switchBeside(label: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        runCatching { onAllNodesWithText(label)[0].performScrollTo() }
        val y = onAllNodesWithText(label)[0].fetchSemanticsNode().boundsInRoot.center.y
        val switches = onAllNodes(isToggleable())
        val nearest = switches.fetchSemanticsNodes().indices
            .minBy { kotlin.math.abs(switches[it].fetchSemanticsNode().boundsInRoot.center.y - y) }
        return switches[nearest]
    }

    @Test
    fun `the theme is stored as chosen`() = settings {
        tap("Dark")
        assertEquals(ThemePreference.Mode.Dark, theme.mode.value)
        tap("Light")
        assertEquals(ThemePreference.Mode.Light, theme.mode.value)
        tap("System")
        assertEquals(ThemePreference.Mode.System, theme.mode.value)
    }

    // "the ability for the user to change the font and font size".
    @Test
    fun `the font is chosen for every device, and the text size for this one`() = settings {
        tap("Serif")
        assertEquals(FontSettings.SERIF, ui.fontSettings.value.family)
        // The font's System, not the theme's, which comes first.
        onAllNodesWithText("System").let { it[it.fetchSemanticsNodes().size - 1] }.performScrollTo().performClick()
        assertEquals(null, ui.fontSettings.value.family)
        onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress))
            .performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(1.5f) }
        assertEquals(1.5f, ui.fontScale.value, 0.001f)
        assertTrue(shows("Text size · 150%"))
    }

    @Test
    fun `an installed font is listed, chosen, and removed from all devices after asking`() {
        ui.setFontSettings(FontSettings(listOf(InstalledFont("f1", "Testa"), InstalledFont("f2", "Testa", 700)), family = null))
        val tmp = kotlin.io.path.createTempDirectory(prefix = "talon-fonts-ui-").toFile()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { req ->
            did += "${req.method.value} ${req.url.encodedPath}"
            respond("", io.ktor.http.HttpStatusCode.OK)
        })
        val repo = FontRepo(ui, FontShip(http, { "https://ship.test" }, { null }), scope, FontFiles(okio.Path.Companion.run { tmp.absolutePath.toPath() }))
        try {
            settings(fontRepo = repo) {
                assertTrue(shows("Testa · 2 files"))
                tap("Testa")
                assertEquals("Testa", ui.fontSettings.value.family)
                tap("Remove")
                assertTrue(shows("Remove Testa?"))
                onAllNodesWithText("Remove").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
                waitForIdle()
                assertTrue(ui.fontSettings.value.fonts.isEmpty())
                assertEquals(listOf("f1", "f2"), ui.fontSettings.value.removed)
                assertEquals(null, ui.fontSettings.value.family)
                waitUntil(timeoutMillis = 5_000) { did.count { it.startsWith("DELETE") } == 2 }
                assertTrue("DELETE /grubbery/api/file/talon/fonts/f1.font" in did, did.toString())
            }
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `density and the home list's order are stored as chosen`() = settings {
        tap("Compact")
        assertEquals(Density.Compact, ui.density.value)
        tap("Cozy")
        assertEquals(Density.Cozy, ui.density.value)
        tap("Host order")
        assertEquals(GroupChannelOrder.HostOrder, ui.groupChannelOrder.value)
        tap("Saved order")
        assertEquals(FolderItemOrder.Manual, ui.folderItemOrder.value)
    }

    @Test
    fun `showing raw ship names is switched and reported`() = settings {
        switchBeside("Always show ~ship names").performClick()
        waitForIdle()
        assertEquals(listOf(true), patpChanges)
        switchBeside("Always show ~ship names").assertIsOn()
    }

    @Test
    fun `the Chats tab's switches store what they show`() = settings {
        tap("Chats")
        assertFalse(ui.hideComposerButtons.value)
        switchBeside("Hide composer buttons").performClick()
        waitForIdle()
        assertTrue(ui.hideComposerButtons.value)
        switchBeside("Hide composer buttons").assertIsOn()

        switchBeside("Power features").performClick()
        waitForIdle()
        assertTrue(ui.powerFeaturesEnabled.value)

        val before = ui.swipeQuotes.value
        tap(if (before) "Replies in its thread" else "Quotes it")
        assertEquals(!before, ui.swipeQuotes.value)
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    // ─── Appearance: own themes and accent ─────────────────────────

    @Test
    fun `a new theme saves only when named with good colours, is used, and is deleted`() = settings {
        tap("New theme")
        onNodeWithText("Save and use").performScrollTo().assertIsNotEnabled()
        onNode(hasSetTextAction() and hasText("Theme name")).performTextInput("Dusk")
        onNodeWithText("Save and use").assertIsEnabled()
        // The first colour field after the name is Primary.
        val primary = onAllNodes(hasSetTextAction())[1]
        primary.performTextReplacement("#33669")
        onNodeWithText("Save and use").assertIsNotEnabled()
        primary.performTextReplacement("#336699")
        tap("Save and use")
        val saved = ui.themeSettings.value.active!!
        assertEquals("Dusk" to "#336699", saved.name to saved.primary)
        assertTrue(!shows("Save and use"), "the editor closes")

        tap("Delete")
        assertTrue(ui.themeSettings.value.themes.isNotEmpty(), "asked first")
        onAllNodesWithText("Delete").onLast().performClick()
        waitForIdle()
        assertTrue(ui.themeSettings.value.themes.isEmpty() && ui.themeSettings.value.activeId == null)
    }

    @Test
    fun `more colours start Auto, set from what was drawn, go back to Auto, and save whole`() = settings {
        tap("New theme")
        onNode(hasSetTextAction() and hasText("Theme name")).performTextInput("Dusk")
        tap("More colours")
        assertEquals(6, onAllNodesWithText("Auto").fetchSemanticsNodes().size, "all six derived")
        // Set Links: it starts from the standard blue.
        val autoRows = onAllNodesWithText("Set")
        autoRows[5].performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("#2962FF").assertExists()
        tap("Save and use")
        val saved = ui.themeSettings.value.active!!
        assertEquals("#2962FF" to "", saved.link to saved.text, "set, and the others written as Auto")

        tap("Edit")
        tap("More colours (1 set)")
        onAllNodesWithText("Auto")[5].performScrollTo().performClick()
        waitForIdle()
        tap("Save and use")
        assertEquals("", ui.themeSettings.value.active!!.link, "back to Auto")
    }

    @Test
    fun `a custom accent is applied only as a real colour`() = settings {
        switchBeside("Custom accent color").performClick()
        tap("Custom hex")
        val hex = onNode(hasSetTextAction())
        hex.performTextInput("#12")
        onNodeWithText("Apply").assertIsNotEnabled()
        hex.performTextReplacement("#123456")
        tap("Apply")
        assertEquals("#123456", ui.accentSettings.value.customHex)
        assertEquals(AccentMode.Custom, ui.accentSettings.value.mode)
    }

    // ─── Home ──────────────────────────────────────────────────────

    private fun homeWith(vararg widgets: HomeWidget) = ui.setHomeLayout(HomeLayoutCodec.encode(HomeLayout(widgets.toList())))

    private fun widget(kind: HomeWidgetKind) = HomeLayoutCodec.decode(ui.homeLayout.value).widgets.first { it.kind == kind }

    @Test
    fun `the Home tab stores the dial's units and each widget's own settings`() {
        homeWith(HomeWidget(HomeWidgetKind.MESSAGES), HomeWidget(HomeWidgetKind.CALENDAR), HomeWidget(HomeWidgetKind.STATUS))
        settings(pinCandidates = listOf("~bus" to "Bus", "~nec" to "Nec")) {
            tap("Home")
            tap("Celsius")
            assertTrue(!ui.homeFahrenheit.value)

            tap("8")
            assertEquals(8, widget(HomeWidgetKind.MESSAGES).count)
            tap("Next 6 hours")
            assertEquals(CalendarRange.NEXT_6_HOURS, widget(HomeWidgetKind.CALENDAR).calendarRange)
            tap("Nec")
            assertEquals(listOf("~nec"), widget(HomeWidgetKind.STATUS).pinned)

            switchBeside("Chat").performClick()
            waitForIdle()
            assertTrue(!widget(HomeWidgetKind.MESSAGES).visible)
        }
    }

    // The hour sat under Home's dial, while chats, search and the calendar
    // each kept a clock of their own.
    @Test
    fun `the clock is chosen once, under Appearance, and every time shown follows it`() {
        settings {
            tap("Appearance")
            tap("24-hour")
            assertTrue(ui.homeTwentyFourHour.value)
            val oneTwentySixPm = kotlinx.datetime.LocalDateTime(2026, 7, 10, 13, 26)
                .toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds()
            io.nisfeb.talon.util.ClockStyle.twentyFourHour.value = ui.homeTwentyFourHour.value
            assertEquals("13:26", io.nisfeb.talon.util.formatClock(oneTwentySixPm))
            tap("12-hour")
            io.nisfeb.talon.util.ClockStyle.twentyFourHour.value = ui.homeTwentyFourHour.value
            assertEquals("1:26 PM", io.nisfeb.talon.util.formatClock(oneTwentySixPm))
        }
    }

    @Test
    fun `no more can be pinned once the statuses widget is full`() {
        val people = (1..HOME_PINNED_MAX + 1).map { "~p$it" to "Person $it" }
        homeWith(HomeWidget(HomeWidgetKind.STATUS, pinned = people.take(HOME_PINNED_MAX).map { it.first }))
        settings(pinCandidates = people) {
            tap("Home")
            onNodeWithText("Person ${HOME_PINNED_MAX + 1}").performScrollTo().assertIsNotEnabled()
            tap("Person 1")
            assertEquals(HOME_PINNED_MAX - 1, widget(HomeWidgetKind.STATUS).pinned.size)
            onNodeWithText("Person ${HOME_PINNED_MAX + 1}").assertIsEnabled()
        }
    }

    // ─── Notifications ─────────────────────────────────────────────

    private fun probe(state: SystemNotificationState) = object : SystemNotificationProbe {
        override fun snapshot() = state
        override fun openBatteryOptimizationSettings() = did.add("battery")
        override fun openAppDetailsSettings() = did.add("app")
        override fun openFullScreenIntentSettings() = did.add("ring")
        override fun openCallLogIntegrationSettings() = did.add("call log")
    }

    @Test
    fun `the health panel names what would drop notifications, and each fix opens its settings`() = settings(
        health = NotificationHealth().apply {
            markSseEvent(System.currentTimeMillis() - 90_000)
            repeat(3) { incrementForceReconnects() }
            addRecoveredEvents(2)
        },
        probe = probe(
            SystemNotificationState(
                notificationsAllowed = false, batteryOptimizationsExempt = false, backgroundRestricted = true,
                fullScreenIntentAllowed = false, callAccountRegistered = true, callLogIsVoip = true,
                telecomEvents = listOf("first thing", "second thing"),
            ),
        ),
    ) {
        tap("Notifications")
        for (text in listOf(
            "reconnecting…", "1m ago", "never", "Force-reconnects (this session)", "Events recovered by sync",
            "blocked — fix in Settings", "throttled — fix in Settings", "RESTRICTED — fix in Settings",
            "logged as VoIP calls", "second thing\nfirst thing",
        )) assertTrue(shows(text), text)
        for (fix in listOf("Fix ring", "Call log", "Fix battery", "App settings")) tap(fix)
        assertEquals(listOf("ring", "call log", "battery", "app"), did.toList())
    }

    @Test
    fun `a healthy device offers nothing to fix`() = settings(
        health = NotificationHealth().apply { markSseConnected(true); markReconcileSuccess(System.currentTimeMillis() - 2 * 3_600_000L) },
        probe = probe(SystemNotificationState(notificationsAllowed = true, batteryOptimizationsExempt = true, backgroundRestricted = false, callAccountRegistered = true)),
    ) {
        tap("Notifications")
        assertTrue(shows("connected") && shows("2h ago") && shows("exempt") && shows("account registered"))
        for (fix in listOf("Fix ring", "Call log", "Fix battery", "App settings", "Force-reconnects")) assertTrue(!shows(fix), fix)
    }

    // ─── About ─────────────────────────────────────────────────────

    @Test
    fun `About says when the ship's calling desk is behind, and copies it with the version`() {
        val ship = FakeShip("~zod").apply { scries["trunk/version"] = """{"wire":8}""" }
        val controller = CallController(UrbitSession(ship.http, ship.session).apply { tryRestore("~zod") }, CallEngineProvider { error("no media") })
        controller.start()
        try {
            settings(calls = controller) {
                waitUntil(timeoutMillis = 10_000) { controller.wire.value == 8 }
                tap("About")
                waitUntil(timeoutMillis = 5_000) { shows("so your ship's copy is behind") }
                assertTrue(shows(io.nisfeb.talon.TalonBuild.versionName))
                tap("Copy version info")
                val copied = did.single()
                assertTrue(io.nisfeb.talon.TalonBuild.versionName in copied && "%trunk wire 8 (app speaks 9)" in copied, copied)
            }
        } finally {
            controller.stop()
        }
    }

    @Test
    fun `without calling, About leaves the desk out`() = settings {
        tap("About")
        assertTrue(shows("github.com/nisfeb/talon") && !shows("Calling (%trunk)"))
        tap("Copy version info")
        assertTrue("%trunk" !in did.single())
    }

    // ─── the AI tab's assistant extras, and Account ────────────────

    private fun assistantOn() = FakeAiSettings(
        io.nisfeb.talon.ai.AiSettings.Config(provider = io.nisfeb.talon.ai.AiSettings.Provider.Anthropic, apiKey = "sk-ant", model = null, agentEnabled = true),
    )

    @Test
    fun `the assistant's web search key is saved, and only when it changed`() {
        val ai = assistantOn()
        settings(ai = ai) {
            tap("AI")
            onNodeWithText("Save key").performScrollTo().assertIsNotEnabled()
            onNode(hasSetTextAction() and hasText("Brave Search API key (optional)")).performTextInput(" brv-1 ")
            onNodeWithText("Save key").performClick()
            waitForIdle()
            assertEquals("brv-1", ai.state.value.braveApiKey)
            onNodeWithText("Save key").assertIsNotEnabled()
        }
    }

    @Test
    fun `a system prompt is edited, saved as customized, and reset to the default saves nothing`() {
        val ai = assistantOn()
        settings(ai = ai) {
            tap("AI")
            tap("Edit Assistant prompt")
            // The dialog's box, which comes last: the screen has key fields of its own.
            onAllNodes(hasSetTextAction()).onLast().performTextReplacement("Be brief.")
            onNodeWithText("Save").performClick()
            waitForIdle()
            assertEquals("Be brief.", ai.state.value.prompt(io.nisfeb.talon.ai.AiSettings.PromptKind.Assistant))
            tap("Edit Assistant prompt (customized)")
            onNodeWithText("Reset to default").performClick()
            onNodeWithText("Save").performClick()
            waitForIdle()
            assertEquals("", ai.state.value.prompt(io.nisfeb.talon.ai.AiSettings.PromptKind.Assistant), "the default is kept as no override, so improvements reach it")
        }
    }

    @Test
    fun `syncing the AI settings is switched off where there are keys to sync`() {
        val ai = assistantOn()
        settings(ai = ai) {
            tap("AI")
            switchBeside("Sync AI settings across devices").performClick()
            waitForIdle()
            assertFalse(ai.state.value.syncEnabled)
        }
        settings {
            tap("AI")
            assertTrue(onAllNodesWithText("Sync AI settings across devices").fetchSemanticsNodes().isEmpty(), "nothing to sync, nothing to switch")
        }
    }

    @Test
    fun `the login QR generator opens from Account`() = settings {
        tap("Account")
        tap("Login QR generator")
        assertEquals(listOf("login qr"), did.toList())
    }
}
