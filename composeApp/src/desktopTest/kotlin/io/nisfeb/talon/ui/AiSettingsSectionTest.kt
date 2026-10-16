package io.nisfeb.talon.ui

import io.nisfeb.talon.ai.ModelInfo
import androidx.compose.ui.test.onAllNodesWithTag
import io.nisfeb.talon.ai.DEVICE_PROVIDER
import io.nisfeb.talon.ai.FeatureSetting
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ai.ARMILLARY_PROVIDER
import io.nisfeb.talon.ai.AiFeature
import io.nisfeb.talon.ai.AiProfile
import io.nisfeb.talon.ai.AiProvider
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.ModelRef
import io.nisfeb.talon.ai.ProviderKind
import io.nisfeb.talon.ai.profile
import io.nisfeb.talon.armillary.Account
import io.nisfeb.talon.armillary.ArmillaryRepo
import io.nisfeb.talon.armillary.Checkout
import io.nisfeb.talon.armillary.LedgerRow
import io.nisfeb.talon.armillary.Payment
import io.nisfeb.talon.armillary.Plan
import io.nisfeb.talon.ui.screens.connectionLine
import io.nisfeb.talon.ui.screens.balanceWarning
import io.nisfeb.talon.ui.screens.dollarsToMicro
import io.nisfeb.talon.ui.screens.historyLines
import io.nisfeb.talon.ui.screens.minTopUp
import io.nisfeb.talon.ui.screens.paymentLine
import io.nisfeb.talon.ui.screens.planLine
import io.nisfeb.talon.ui.screens.subscribeLabel
import io.nisfeb.talon.ui.screens.subscriptionLine
import io.nisfeb.talon.ui.screens.topUpSizes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import io.nisfeb.talon.ui.screens.AiSettingsSection
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AiSettingsSectionTest {
    /** One of the card's rarer actions, behind its More button. */
    private fun ComposeUiTest.more(item: String) {
        onNodeWithText("More").performScrollTo().performClick()
        onNodeWithText(item).performClick()
    }

    // ── the Armillary card ─────────────────────────────────────────

    private val jsonHeaders = headersOf("Content-Type", "application/json")

    /** A profile holding the Armillary row, which is what makes the card show. */
    private fun withArmillary() = AiProfile(
        providers = listOf(
            AiProvider("main", ProviderKind.Anthropic, "Anthropic", apiKey = "sk-ant"),
            AiProvider(ARMILLARY_PROVIDER, ProviderKind.Armillary, "Armillary"),
        ),
        defaultModel = ModelRef("main", "claude-opus-5"),
    )

    /** A repo over a ship that answers [reply], already refreshed once. */
    private fun repo(ai: FakeAiSettings, reply: (String) -> Pair<HttpStatusCode, String>): ArmillaryRepo {
        val client = HttpClient(MockEngine { req ->
            val (status, body) = reply(req.url.encodedPath)
            respond(body, status, jsonHeaders)
        })
        val r = ArmillaryRepo(client, CoroutineScope(Dispatchers.Default), ai)
        r.attach("https://ship.example", "~feb")
        runBlocking { r.refresh() }
        return r
    }

    private fun FakeAiSettings.withProfile(p: AiProfile): FakeAiSettings = apply {
        applyRemote(AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "sk-ant", model = "claude-opus-5", savedProfile = p))
    }

    @Test
    fun `a ship without armillary says so on the card and offers nothing to buy`() = runComposeUiTest {
        val ai = FakeAiSettings().withProfile(withArmillary())
        val bought = repo(ai) { HttpStatusCode.NotFound to "" }
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null, armillary = bought) }
            }
        }
        onNodeWithText("Armillary is not on your ship. Install it from the Apps page.").assertExists()
        onNodeWithText("Armillary is published by ~ricsul-bilwyt, the same as Orrery and the Calendar.").assertExists()
        onNodeWithText("Top up").assertIsNotEnabled()
        // No address and no key anywhere: the ship holds both, and an
        // own provider left from before is not shown.
        assertEquals(0, onAllNodesWithText("API key", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `a ship that refuses the cookie says that instead`() = runComposeUiTest {
        val ai = FakeAiSettings().withProfile(withArmillary())
        val bought = repo(ai) { HttpStatusCode.Forbidden to "" }
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null, armillary = bought) }
            }
        }
        onNodeWithText("Signed out of the ship.").assertExists()
    }

    @Test
    fun `an answering ship shows the balance, the mode and what there is to buy`() = runComposeUiTest {
        val ai = FakeAiSettings().withProfile(withArmillary())
        val bought = repo(ai) { path ->
            HttpStatusCode.OK to when {
                path.endsWith("/api/inference") ->
                    """{"mode":"proxy","base_url":"https://wex.example/apps/armillary/v1","key":"k.s","models":["stub/alpha"]}"""
                path.endsWith("/api/plans") ->
                    """[{"id":"five","name":"Five dollars","kind":"topup","price":5000000,"credit":5000000,"interval":""},
                        {"id":"starter","name":"Starter","kind":"subscription","price":10000000,"credit":12000000,"interval":"month"}]"""
                path.endsWith("/api/catalog") ->
                    """[{"id":"stub/alpha","provider":"stub","in":3900000,"out":19500000,"tags":["zdr"]}]"""
                else ->
                    """{"ship":"~feb","balance":12345000,"plan":"","subscription":{"active":false},
                        "lease":{},"checkouts":{},"vendor":"~wex","self":"~feb","stale":3}"""
            }
        }
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null, armillary = bought) }
            }
        }
        // An app that answers needs no line saying so.
        onNodeWithText("Answering on this ship.").assertDoesNotExist()
        onNodeWithText("$12.35 credit with ~wex").assertExists()
        onNodeWithText("Change vendor").assertExists()
        onNodeWithText("Pay as you go.").assertExists()
        onNodeWithText("Connected through your vendor. 1 model.").assertExists()
        onNodeWithText("This device isn't connected yet.").assertDoesNotExist()
        onNodeWithText("Subscribe: Starter, $10.00 a month for $12.00 of credit").assertExists()
        onNodeWithText("Top up").performClick()
        waitForIdle()
        onNodeWithText("$5.00").assertExists()
        onNodeWithText("In dollars. The smallest the vendor takes is $5.00.").assertExists()
        // Nothing chosen is nothing to continue with.
        onNodeWithText("Continue").assertIsNotEnabled()
    }

    /** A ship answering [plans] and [account], with one model on offer. */
    private fun selling(ai: FakeAiSettings, plans: String, account: String) = repo(ai) { path ->
        HttpStatusCode.OK to when {
            path.endsWith("/api/inference") ->
                """{"mode":"proxy","base_url":"https://wex.example/apps/armillary/v1","key":"k.s","models":["stub/alpha"]}"""
            path.endsWith("/api/plans") -> plans
            path.endsWith("/api/catalog") -> """[{"id":"stub/alpha","provider":"stub","in":3900000,"out":19500000,"tags":[]}]"""
            else -> account
        }
    }

    private val threeSizes = """[
        {"id":"ten","name":"Ten","kind":"topup","price":10000000,"credit":10000000,"interval":""},
        {"id":"fifty","name":"Fifty","kind":"topup","price":50000000,"credit":50000000,"interval":""},
        {"id":"five","name":"Five","kind":"topup","price":5000000,"credit":5000000,"interval":""},
        {"id":"pro","name":"Talon Pro","kind":"subscription","price":10000000,"credit":12000000,"interval":"month"}]"""

    private val funded = """{"ship":"~feb","balance":12345000,"plan":"","subscription":{"active":false},
        "lease":{},"checkouts":{},"vendor":"~wex","self":"~feb","stale":3}"""

    @Test
    fun `the sheet shows the sizes cheapest first, then another amount`() = runComposeUiTest {
        val ai = FakeAiSettings().withProfile(withArmillary())
        val bought = selling(ai, threeSizes, funded)
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null, armillary = bought) }
            }
        }
        onNodeWithText("Top up").performClick()
        waitForIdle()
        val five = onNodeWithText("$5.00").fetchSemanticsNode().boundsInRoot
        val ten = onNodeWithText("$10.00").fetchSemanticsNode().boundsInRoot
        val fifty = onNodeWithText("$50.00").fetchSemanticsNode().boundsInRoot
        assertTrue(five.left < ten.left && ten.left < fifty.left, "sizes run left to right by price")
        assertEquals(five.top, fifty.top, "sizes sit on one row")
        onNodeWithText("Another amount").assertExists()
        onNodeWithText("Card").assertExists()
        onNodeWithText("Bitcoin").assertExists()
        onNodeWithText("Continue").assertIsNotEnabled()
        onNodeWithText("$10.00").performClick()
        waitForIdle()
        onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun `a vendor with no sizes offers the custom field alone`() = runComposeUiTest {
        val ai = FakeAiSettings().withProfile(withArmillary())
        val bought = selling(
            ai,
            """[{"id":"pro","name":"Talon Pro","kind":"subscription","price":10000000,"credit":12000000,"interval":"month"}]""",
            funded,
        )
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null, armillary = bought) }
            }
        }
        onNodeWithText("Top up").performClick()
        waitForIdle()
        onNodeWithText("Another amount").assertExists()
        onNodeWithText("In dollars. The smallest the vendor takes is $5.00.").assertExists()
        // No size buttons: the one price on the screen is the minimum line above.
        assertEquals(1, onAllNodesWithText("$5.00", substring = true).fetchSemanticsNodes().size)
        onNodeWithText("$10.00").assertDoesNotExist()
        onNodeWithText("Bitcoin").assertExists()
    }

    @Test
    fun `bitcoin is not offered for a subscription`() = runComposeUiTest {
        val ai = FakeAiSettings().withProfile(withArmillary())
        val bought = selling(ai, threeSizes, funded)
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null, armillary = bought) }
            }
        }
        onNodeWithText("Subscribe: Talon Pro, $10.00 a month for $12.00 of credit").performClick()
        waitForIdle()
        onNodeWithText("Subscribe").assertExists()
        onNodeWithText("Talon Pro, $10.00 a month for $12.00 of credit").assertExists()
        onNodeWithText("Card").assertExists()
        onNodeWithText("Bitcoin").assertDoesNotExist()
        onNodeWithText("Another amount").assertDoesNotExist()
        onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun `the history lists the ledger newest first with the rail and the tokens`() = runComposeUiTest {
        val ai = FakeAiSettings().withProfile(withArmillary())
        val bought = selling(
            ai, threeSizes,
            """{"ship":"~feb","balance":4999863,"plan":"","subscription":{"active":false},"lease":{},"checkouts":{},
                "ledger":[
                  {"kind":"debit","amount":137,"cost":105,"model":"stub/alpha","in":10,"out":5,"mode":"proxy","rail":"","ref":"","note":"","at":"2026-09-21T12:35:00Z"},
                  {"kind":"credit","amount":5000000,"cost":0,"model":"","in":0,"out":0,"mode":"","rail":"btcpay","ref":"inv1","note":"","at":"2026-09-21T12:34:00Z"}],
                "vendor":"~wex","self":"~feb","stale":3}""",
        )
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null, armillary = bought) }
            }
        }
        onNodeWithText("2026-09-21 12:35  Charge  under a cent").assertDoesNotExist()
        more("History")
        waitForIdle()
        val charge = onNodeWithText("2026-09-21 12:35  Charge  under a cent").fetchSemanticsNode().boundsInRoot
        val credit = onNodeWithText("2026-09-21 12:34  Credit  $5.00, by bitcoin").fetchSemanticsNode().boundsInRoot
        assertTrue(charge.top < credit.top, "newest first")
        onNodeWithText("stub/alpha, 10 in, 5 out").assertExists()
    }

    // ── the card's own sentences, without a ship ───────────────────

    @Test
    fun `the payment line follows the watch, and the row's status once it has spoken`() {
        fun row(status: String, note: String = "") = Checkout("n1", "https://pay.example/1", status, "stripe", 5_000_000L, note)
        val waiting = Payment("n1", "stripe", 0L, Payment.Phase.WAITING)
        assertNull(paymentLine(null, null))
        assertEquals("Waiting for your payment", paymentLine(waiting, null))
        assertEquals("Waiting for your payment", paymentLine(waiting, row("pending")))
        assertEquals(
            "Waiting for your bitcoin payment to confirm, usually ten to twenty minutes",
            paymentLine(waiting.copy(rail = "btcpay"), row("pending")),
        )
        assertEquals("Payment seen, waiting for confirmation", paymentLine(waiting, row("processing")))
        assertEquals("Paid: $5.00 added", paymentLine(waiting.copy(phase = Payment.Phase.PAID, addedMicro = 5_000_000L), row("paid")))
        assertEquals("The payment did not go through", paymentLine(waiting.copy(phase = Payment.Phase.ENDED), row("failed")))
        assertEquals("The checkout expired before it was paid", paymentLine(waiting.copy(phase = Payment.Phase.ENDED), row("expired")))
        assertEquals("amount: below the minimum", paymentLine(waiting.copy(phase = Payment.Phase.ENDED), row("refused", "amount: below the minimum")))
        assertEquals(
            "No payment seen yet. If you paid, it arrives within a few minutes; Refresh to check.",
            paymentLine(waiting.copy(phase = Payment.Phase.UNSEEN), row("pending")),
        )
        // Once the balance rose the row's own status no longer matters.
        assertEquals("Paid: $1.00 added", paymentLine(waiting.copy(phase = Payment.Phase.PAID, addedMicro = 1_000_000L), row("processing")))
    }

    @Test
    fun `a history row says what kind, how much, and by which rail`() {
        val credit = LedgerRow("credit", 10_000_000L, "", 0, 0, "stripe", "", "2026-09-20T08:00:00Z")
        assertEquals("2026-09-20 08:00  Credit  $10.00, by card" to null, historyLines(credit))
        val owner = credit.copy(rail = "", note = "first dollar")
        assertEquals("2026-09-20 08:00  Credit  $10.00" to null, historyLines(owner))
        val charge = LedgerRow("debit", 137L, "stub/alpha", 10, 5, "", "", "2026-09-20T08:01:00Z")
        assertEquals("2026-09-20 08:01  Charge  under a cent" to "stub/alpha, 10 in, 5 out", historyLines(charge))
        val refund = LedgerRow("refund", 2_500_000L, "", 0, 0, "stripe", "dispute lost", "2026-09-21T09:00:00Z")
        assertEquals("2026-09-21 09:00  Refund  $2.50" to null, historyLines(refund))
    }

    @Test
    fun `the sizes are the top-up plans by price, and the minimum is the cheapest`() {
        val plans = listOf(
            Plan("fifty", "Fifty", "topup", 50_000_000L, 50_000_000L, ""),
            Plan("pro", "Pro", "subscription", 10_000_000L, 12_000_000L, "month"),
            Plan("five", "Five", "topup", 5_000_000L, 5_000_000L, ""),
        )
        assertEquals(listOf("five", "fifty"), topUpSizes(plans).map { it.id })
        assertEquals(5_000_000L, minTopUp(plans))
        assertEquals(5_000_000L, minTopUp(emptyList()), "five dollars where the vendor lists no size")
        val three = Plan("three", "Three", "topup", 3_000_000L, 3_000_000L, "")
        assertEquals(3_000_000L, minTopUp(plans + three), "the cheapest plan, not the default")
        assertEquals("Subscribe: Pro, $10.00 a month for $12.00 of credit", subscribeLabel(plans[1]))
        assertEquals("Pro, $10.00 a year for $12.00 of credit", subscriptionLine(plans[1].copy(interval = "year")))
    }


    @Test
    fun `the balance warns before it runs out and says when it has`() {
        fun acct(micro: Long) = Account(true, micro, "", false, null, "~wex", 0, leaseHeld = false, leaseDisabled = false, checkouts = emptyList())
        assertEquals(null, balanceWarning(acct(5_000_000)))
        assertEquals(null, balanceWarning(acct(1_000_000)))
        assertEquals("Almost out: top up before your next request fails.", balanceWarning(acct(999_999)))
        assertEquals("Empty: requests fail until you top up.", balanceWarning(acct(0)))
        assertEquals("Empty: requests fail until you top up.", balanceWarning(acct(-137)))
        // An app that cannot take a payment (iOS off the US storefront)
        // names no button that is not there, and no other place to pay.
        assertEquals(
            "Empty: requests fail until there is credit on the account.",
            balanceWarning(acct(0), canBuy = false),
        )
        assertEquals("Almost out: requests fail once it runs out.", balanceWarning(acct(999_999), canBuy = false))
        assertEquals(null, balanceWarning(acct(5_000_000), canBuy = false), "a full balance warns either way")
    }

    @Test
    fun `the connection line says whether it works, and how many models`() {
        val empty = Account(true, 0, "", false, null, "~wex", 0, leaseHeld = true, leaseDisabled = true, checkouts = emptyList())
        assertEquals("Out of credit. Top up to keep using AI. 2 models.", connectionLine("lease", empty, 2))
        // Outside the US App Store no line says to pay (guideline 3.1.1(a)).
        assertEquals("Out of credit. 2 models.", connectionLine("lease", empty, 2, canBuy = false))
        assertEquals("Connected. 1 model.", connectionLine("lease", null, 1))
        assertEquals("Connected through your vendor. 3 models.", connectionLine("proxy", null, 3))
        assertEquals("Connected. 0 models.", connectionLine(null, null, 0))
    }

    @Test
    fun `the plan line names the subscription and when it renews`() {
        val on = Account(true, 0, "Starter", true, "2026-10-20T00:00:00Z", "~wex", 0, false, false, emptyList())
        assertEquals("Starter, renews 2026-10-20T00:00:00Z.", planLine(on))
        assertEquals("Pay as you go.", planLine(on.copy(plan = "", subscriptionActive = false, renews = null)))
    }

    @Test
    fun `a custom amount is dollars, and nonsense is no amount at all`() {
        assertEquals(5_000_000L, dollarsToMicro("5"))
        assertEquals(5_000_000L, dollarsToMicro(" $5.00 "))
        assertEquals(12_500_000L, dollarsToMicro("12.50"))
        assertNull(dollarsToMicro(""))
        assertNull(dollarsToMicro("lots"))
        assertNull(dollarsToMicro("-5"))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the card shows the balance where credit cannot be bought`() = runComposeUiTest {
        // The flag is true on every platform today, so what can be
        // asserted here is the half that does not depend on it: the
        // balance, the warning and the vendor stand on their own, and
        // the sheet is only ever reached through Top up.
        val ai = FakeAiSettings().withProfile(withArmillary())
        val bought = selling(
            ai,
            threeSizes,
            """{"ship":"~feb","balance":0,"plan":"","subscription":{"active":false},
                "lease":{},"checkouts":{},"vendor":"~wex","self":"~feb","stale":3}""",
        )
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null, armillary = bought) }
            }
        }
        waitForIdle()
        // The half that does not depend on the flag stands either way.
        onNodeWithText("No credit with ~wex").assertExists()
        if (io.nisfeb.talon.ui.isArmillaryPurchaseSupported) {
            onNodeWithText("Top up").assertExists()
            onNodeWithText("Empty: requests fail until you top up.").assertExists()
        } else {
            assertTrue(
                onAllNodesWithText("Top up").fetchSemanticsNodes().isEmpty(),
                "nothing to press where credit cannot be bought",
            )
            onNodeWithText("Empty: requests fail until there is credit on the account.").assertExists()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a profile with nothing set up is pitched armillary first`() = runComposeUiTest {
        // A fresh install is not an empty list: this device is always a
        // provider, so the pitch turns on there being nothing the owner
        // went and set up.
        val ai = FakeAiSettings()
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null) }
            }
        }
        waitForIdle()
        onNodeWithText("Turn on AI").assertExists()
        onNodeWithText("no keys to copy in", substring = true).assertExists()
        onNodeWithText("Set up Armillary").assertExists()
        assertTrue(onAllNodesWithText("Top up to turn on.").fetchSemanticsNodes().size >= 2, "each paid feature says what it waits on")
        // Pressing it adds the row, and nothing else is asked of the owner.
        onNodeWithText("Set up Armillary").performClick()
        waitForIdle()
        assertEquals(ProviderKind.Armillary, ai.state.value.profile().armillary()?.kind)
    }

    // The card says there is nothing more to set up. With no default
    // model nothing resolves, so on a fresh install no feature ran.
    @OptIn(ExperimentalTestApi::class)

    // ── Armillary-only (2026-10-16) ─────────────────────────────

    private fun paid(balance: Long = 12_345_000, free: Boolean = false) = FakeAiSettings().apply { applyRemote(io.nisfeb.talon.ai.paidSettings(speech = true)) }.let { ai ->
        ai to repo(ai) { path ->
            HttpStatusCode.OK to when {
                path.endsWith("/api/inference") ->
                    """{"mode":"proxy","base_url":"https://ship.example/apps/armillary/v1","key":"k.s","models":["a/1"],"suggested":{"models":{"default":"a/1"}},"free":$free}"""
                path.endsWith("/api/plans") -> threeSizes
                path.endsWith("/api/catalog") -> "[]"
                else -> """{"ship":"~feb","balance":$balance,"plan":"","subscription":{"active":false},"lease":{},"checkouts":{},"vendor":"~nisfeb","self":"~feb","stale":3}"""
            }
        }
    }

    @Test
    fun `every paid feature is one switch, with no provider or model to pick`() = runComposeUiTest {
        val (ai, bought) = paid()
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null, armillary = bought) }
            }
        }
        waitForIdle()
        onNodeWithText("$12.35 credit with ~nisfeb").assertExists()
        listOf("Catch-up", "Call transcripts").forEach { onNodeWithText(it).assertExists() }
        if (isAssistantSupported) onNodeWithText("Assistant").assertExists()
        listOf("Providers", "Default model", "Add a provider", "API key", "Use Armillary's pick", "Top up to turn on.").forEach {
            assertTrue(onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isEmpty(), "no '$it' any more")
        }
        // A switch turned off stays off: the owner's own.
        onNodeWithText("Catch-up").performScrollTo()
        val row = onNodeWithText("Catch-up").fetchSemanticsNode().boundsInRoot
        val switches = onAllNodes(isToggleable())
        val nearest = switches.fetchSemanticsNodes().indices.minBy { kotlin.math.abs(switches[it].fetchSemanticsNode().boundsInRoot.top - row.top) }
        switches[nearest].performClick()
        waitForIdle()
        assertFalse(ai.state.value.profile().isOn(io.nisfeb.talon.ai.AiFeature.CatchUp))
    }

    @Test
    fun `an empty balance says to top up, and the sheet says what paying turns on`() = runComposeUiTest {
        val (ai, bought) = paid(balance = 0)
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null, armillary = bought) }
            }
        }
        waitForIdle()
        assertTrue(onAllNodesWithText("Top up to turn on.").fetchSemanticsNodes().size >= 2)
        onNodeWithText("Top up").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText(io.nisfeb.talon.ui.screens.CONSENT_LINE).assertExists()
    }

    @Test
    fun `Manage models in Armillary opens Armillary's page on the ship`() = runComposeUiTest {
        val (ai, bought) = paid()
        val opened = mutableListOf<String>()
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalUriHandler provides object : androidx.compose.ui.platform.UriHandler {
                    override fun openUri(uri: String) { opened += uri }
                },
            ) {
                TalonTheme(darkTheme = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null, armillary = bought) }
                }
            }
        }
        onNodeWithText("Manage models in Armillary").performScrollTo().performClick()
        assertEquals(listOf("https://ship.example/apps/armillary#catalog"), opened)
    }

    @Test
    fun `own keys dropped by the reset are said once, until read`() = runComposeUiTest {
        val ai = FakeAiSettings().apply {
            applyRemote(io.nisfeb.talon.ai.paidSettings().let { c -> c.copy(savedProfile = c.savedProfile!!.copy(ownKeysDropped = true)) })
        }
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null) }
            }
        }
        onNodeWithText("Your own AI keys are no longer used.", substring = true).assertExists()
        onNodeWithText("OK").performClick()
        waitForIdle()
        assertFalse(ai.state.value.profile().ownKeysDropped)
        assertTrue(onAllNodesWithText("Your own AI keys are no longer used.", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a ship that is its own vendor on its own key is not told to top up`() = runComposeUiTest {
        val (ai, bought) = paid(balance = 0, free = true)
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null, armillary = bought) }
            }
        }
        waitForIdle()
        assertTrue(onAllNodesWithText("Top up to turn on.").fetchSemanticsNodes().isEmpty(), "its zero balance is no reason")
        onNodeWithText("Catch-up").assertExists()
    }
}
