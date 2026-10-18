package io.nisfeb.talon

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import io.nisfeb.talon.ui.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import io.nisfeb.talon.ui.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.nisfeb.talon.QuickDictation.Stage
import io.nisfeb.talon.ai.CardDay
import io.nisfeb.talon.ai.CardMail
import io.nisfeb.talon.ai.CardMessage
import io.nisfeb.talon.ai.CardPerson
import io.nisfeb.talon.ai.CardProposal
import io.nisfeb.talon.ai.CardTask
import io.nisfeb.talon.ai.DictationCard
import io.nisfeb.talon.ai.DictationRun
import io.nisfeb.talon.ai.loopsCanRun
import io.nisfeb.talon.ui.MdBlock
import io.nisfeb.talon.ui.dmLink
import io.nisfeb.talon.ui.dmShip
import io.nisfeb.talon.ui.icons.TalonIcons
import io.nisfeb.talon.ui.inlineAnnotated
import io.nisfeb.talon.ui.parseMarkdownBlocks
import io.nisfeb.talon.ui.withTalonLinks
import io.nisfeb.talon.urbit.TalonLink
import kotlinx.coroutines.delay

/**
 * What the owner sees of a dictation: a card over whatever was on screen,
 * the lock screen included. It starts [DictationService] (the microphone
 * may only be opened by an app on screen) and stays for the whole of it:
 * listening, working, then what orrery left waiting with Send and Don't
 * send, or the answer, with the events and tasks it read and a Follow up
 * that dictates again in the same context. The widget, the tile and the
 * assist gesture all open this.
 *
 * Made to be read and answered in a car (sneagan, 2026-10-16: "the cards
 * and text are too small"): large type, few words, big buttons, and the
 * buttons mid-screen, since low on the card a tap could land on the
 * fingerprint sensor and unlock the phone.
 *
 * Android-only: no desktop analog (see [QuickDictation]).
 */
class QuickDictationActivity : ComponentActivity() {
    /** Why it cannot listen, in words for the owner; null while it can. */
    private val problem = mutableStateOf<String?>(null)

    /** Opened by Home's Archie button: a conversation, not a dictation. */
    private val converse by lazy { intent.getBooleanExtra(EXTRA_CONVERSE, false) }

    private val askForMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else problem.value = "Talon needs the microphone to hear a dictation."
    }

    private fun begin() = if (converse) QuickDictation.converse(this) else QuickDictation.listen(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) {
            // Back while listening throws the words away; later it only
            // hides the card, and the shade asks in its place.
            if (QuickDictation.stage.value is Stage.Listening) QuickDictation.cancel()
            if (QuickDictation.stage.value is Stage.Said) QuickDictation.close()
            // Archie never goes on listening behind a card that is gone.
            if (QuickDictation.talking.value) QuickDictation.hangUp()
            finish()
        }
        // Not again after a recreation, nor over a run under way: one
        // waiting for the owner's word is shown, not recorded over.
        val now = QuickDictation.stage.value
        if (savedInstanceState == null && (now is Stage.Idle || now is Stage.Said)) start()
        // The app's theme, as the owner set it up, without painting the
        // window: that would hide what is behind the card. It drew in the
        // brand palette whatever the app's theme was.
        setContent {
            io.nisfeb.talon.ui.theme.TalonAppTheme(application as TalonApplication, paintWindow = false) {
                CompositionLocalProvider(LocalUriHandler provides links) { Sheet() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        QuickDictation.shown(true)
    }

    override fun onPause() {
        QuickDictation.shown(false)
        super.onPause()
    }

    /**
     * Home or recents: the owner left the card, and Archie stops as on
     * back. Not the screen going off, which calls no hint: in the car
     * the conversation goes on with the phone dark.
     */
    override fun onUserLeaveHint() {
        if (QuickDictation.talking.value) QuickDictation.hangUp()
        super.onUserLeaveHint()
    }

    private fun start() {
        val app = application as TalonApplication
        val cfg = app.aiSettings.state.value
        problem.value = DictationRun.blocker(cfg, app.sessionStore.activeShip() != null)
            // A conversation is the model's throughout, orrery or not.
            ?: DictationRun.NO_MODEL_FOR_QUESTIONS.takeIf { converse && !cfg.loopsCanRun() }
        if (problem.value != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            begin()
        } else {
            askForMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Send: at once on an unlocked phone; on a locked one, for anything
     * that reaches another person, only once the fingerprint has unlocked it.
     * The unlock card's Unlock, before messages or mail are read, goes so too.
     */
    private fun approve(unlock: Boolean) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!unlock || keyguard?.isDeviceLocked != true) return QuickDictation.decide(true)
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = QuickDictation.decide(true)
        })
    }

    private fun close() {
        QuickDictation.close()
        finish()
    }

    /**
     * Where a tap on the card goes: an @p or a name to that person's chat,
     * a message to the message, mail to the mail, the calendar's rows to
     * the calendar, anything else where it points. Talon itself waits for
     * an unlock on a locked phone, so the card asks for one first.
     */
    private fun open(uri: String) {
        val talon = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ship = dmShip(uri)
        val target: Intent = when {
            ship != null -> talon.putExtra(Notifications.EXTRA_OPEN_WHOM, ship)
            uri == CALENDAR -> talon.putExtra(Notifications.EXTRA_OPEN_CALENDAR, io.nisfeb.talon.calendar.CALENDAR_PUSH_PREFIX)
            TalonLink.isTalonUrl(uri) -> when (val l = TalonLink.parse(uri)) {
                is TalonLink.Message -> talon.putExtra(Notifications.EXTRA_OPEN_WHOM, l.whom).apply {
                    if (l.parentId != null) putExtra(Notifications.EXTRA_OPEN_THREAD, l.parentId).putExtra(Notifications.EXTRA_THREAD_ANCHOR, l.id)
                    else putExtra(Notifications.EXTRA_SCROLL_TO_MESSAGE, l.id)
                }
                is TalonLink.Mail -> talon.putExtra(Notifications.EXTRA_OPEN_MAIL, true)
                else -> talon
            }
            else -> Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val go = {
            runCatching { startActivity(target) }
            close()
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceLocked == true) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = go()
            })
        } else {
            go()
        }
    }

    private val links = object : UriHandler {
        override fun openUri(uri: String) = open(uri)
    }

    @Composable
    private fun Sheet() {
        val stage by QuickDictation.stage.collectAsState()
        val why by problem
        // Gone once the dictation is over: when it is idle again having
        // started, and a while after it says how it ended; longer where
        // a follow-up is offered, which is read before it is tapped.
        var began by remember { mutableStateOf(false) }
        LaunchedEffect(stage) {
            when (val s = stage) {
                is Stage.Idle -> if (began) finish()
                // Archie's turn: the card listens again by itself, and stays.
                is Stage.Said -> if (s.talking) began = true else {
                    began = true
                    val base = if (s.followable || s.card != null) 30_000L else 5_000L
                    delay((base + 50L * s.line.length).coerceAtMost(45_000L))
                    close()
                }
                is Stage.Approve -> {
                    began = true
                    if (s.unlockNow) approve(unlock = true)
                }
                // A read of messages or mail on a locked phone: the fingerprint at once.
                is Stage.Unlock -> {
                    began = true
                    approve(unlock = true)
                }
                else -> began = true
            }
        }
        val tall = (LocalConfiguration.current.screenHeightDp * 0.86f).dp
        Box(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp, max = tall),
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 16.dp,
            ) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Header(stage)
                    val blocked = why
                    if (blocked != null) {
                        Blocked(blocked)
                    } else {
                        when (val s = stage) {
                            is Stage.Listening -> Listening(s.level)
                            is Stage.Approve -> Approve(s)
                            is Stage.Unlock -> Unlock(s)
                            is Stage.Said -> Said(s)
                            is Stage.Working -> Working(s.label)
                            Stage.Idle -> Working("Starting…")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Header(stage: Stage) {
        val talking by QuickDictation.talking.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (talking) {
                Icon(io.nisfeb.talon.ui.icons.ArchieMark, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            } else {
                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_talon_mark), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Text(
                if (talking) io.nisfeb.talon.ui.icons.ASSISTANT_NAME.uppercase() else "DICTATION",
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
            )
            if ((stage is Stage.Approve && stage.unlock) || stage is Stage.Unlock) {
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }

    @Composable
    private fun BigButton(text: String, icon: ImageVector?, onClick: () -> Unit) {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(68.dp), shape = RoundedCornerShape(20.dp)) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onClick, modifier = modifier.height(60.dp), shape = RoundedCornerShape(20.dp)) {
            Text(text, fontSize = 20.sp)
        }
    }

    @Composable
    private fun Blocked(why: String) {
        Text(why, style = MaterialTheme.typography.headlineSmall)
        BigButton("Open Talon", null) {
            startActivity(Intent(this@QuickDictationActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
        QuietButton("Close", { finish() })
    }

    @Composable
    private fun ColumnScope.Listening(level: Float) {
        val swell by animateFloatAsState(1f + level * 0.35f, label = "level")
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(150.dp).scale(swell).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), CircleShape),
            )
            Box(Modifier.size(104.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(TalonIcons.Mic, contentDescription = "Listening", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(52.dp))
            }
        }
        Text("Listening…", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(
            "Say it, then pause.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuietButton("Cancel", { QuickDictation.cancel(); finish() }, Modifier.weight(1f))
            Button(onClick = { QuickDictation.done() }, modifier = Modifier.weight(1f).height(60.dp), shape = RoundedCornerShape(20.dp)) {
                Text("Done", fontSize = 20.sp)
            }
        }
    }

    @Composable
    private fun Working(label: String) {
        Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            CircularProgressIndicator(Modifier.size(64.dp), strokeWidth = 5.dp)
            Text(label, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        }
    }

    /** Big, and little to read: this is answered in a car. */
    @Composable
    private fun ColumnScope.Approve(s: Stage.Approve) {
        Text(
            if (s.sending) "Send this?" else "Do this?",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            s.proposals.forEach { Proposal(it) }
            if (s.reply.isNotBlank()) Text(s.reply, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            when {
                s.unlockNow -> "Unlock to send."
                s.hearing -> "Say \u201c${if (s.sending) "send" else "yes"}\u201d or \u201ccancel\u201d"
                s.unlock -> "Your fingerprint sends it"
                else -> " "
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        BigButton(if (s.sending) "Send" else "Do it", if (s.sending) Icons.AutoMirrored.Filled.Send else Icons.Filled.Check) { approve(s.unlock) }
        QuietButton(if (s.sending) "Don't send" else "Don't", { QuickDictation.decide(false) })
    }

    /** Messages or mail asked for on a locked phone: read only once it is unlocked. */
    @Composable
    private fun Unlock(s: Stage.Unlock) {
        Text(s.line, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold))
        BigButton("Unlock", Icons.Filled.Lock) { approve(unlock = true) }
        QuietButton("Not now", { QuickDictation.decide(false) })
    }

    /** One thing orrery would do, drawn as what it is. */
    @Composable
    private fun Proposal(p: CardProposal) {
        when (p.kind) {
            "message" -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                p.to?.let { Person(it, null) }
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(topStart = 6.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 22.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(p.text ?: p.title, fontSize = 24.sp, lineHeight = 32.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(18.dp))
                }
            }
            "calendar" -> Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(18.dp)).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.Filled.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.title, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
                    p.time?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
                    p.place?.let { Place(it) }
                }
            }
            "task" -> Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(18.dp)).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Column {
                    Text(p.title, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
                    p.time?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            else -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Text(p.title, fontSize = 22.sp, lineHeight = 28.sp, modifier = Modifier.padding(16.dp))
            }
        }
    }

    @Composable
    private fun ColumnScope.Said(s: Stage.Said) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                // While Archie talks, a tap on what it says stops the voice, and it listens.
                .then(if (s.talking) Modifier.clickable(onClickLabel = "Stop talking") { QuickDictation.hush() } else Modifier),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Answer(s.line, s.card?.names.orEmpty())
            s.card?.let { Card(it) }
        }
        if (s.talking) {
            QuietButton("Done", { close() })
            return
        }
        if (s.followable) BigButton("Follow up", TalonIcons.Mic) { QuickDictation.followUp(this@QuickDictationActivity) }
        QuietButton("Close", { close() })
    }

    /**
     * The answer, in large type: its markdown drawn, and each @p, name the
     * answer looked up, and link something to tap.
     */
    @Composable
    private fun Answer(text: String, names: Map<String, String>) {
        val blocks = remember(text) { parseMarkdownBlocks(text) }
        val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
        val link = MaterialTheme.colorScheme.primary
        val big = text.length < 140
        val body = TextStyle(fontSize = if (big) 24.sp else 20.sp, lineHeight = if (big) 32.sp else 28.sp, color = MaterialTheme.colorScheme.onSurface)
        fun inline(t: String) = inlineAnnotated(t, codeBg, link).withTalonLinks(names, link)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            blocks.forEachIndexed { i, b ->
                when (b) {
                    is MdBlock.Code -> Surface(color = codeBg, shape = RoundedCornerShape(12.dp)) {
                        Text(b.text, style = body.copy(fontFamily = FontFamily.Monospace, fontSize = 16.sp, lineHeight = 22.sp), modifier = Modifier.padding(12.dp))
                    }
                    is MdBlock.Heading -> Text(inline(b.text), style = body.copy(fontWeight = FontWeight.Bold, fontSize = if (b.level == 1) 26.sp else 22.sp))
                    is MdBlock.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("\u2022", style = body.copy(color = link))
                        Text(inline(b.text), style = body)
                    }
                    is MdBlock.Ordered -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${b.number}.", style = body.copy(color = link, fontWeight = FontWeight.SemiBold))
                        Text(inline(b.text), style = body)
                    }
                    // The first paragraph is the highlight, the part said aloud.
                    is MdBlock.Paragraph -> Text(
                        inline(b.text),
                        style = if (i == 0 && blocks.size > 1) body.copy(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold) else body,
                    )
                }
            }
        }
    }

    /** What the answer read, as Talon shows it, each row opening where it lives. */
    @Composable
    private fun Card(card: DictationCard) {
        if (card.people.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { card.people.forEach { Person(it.name, it.ship) } }
        card.days.forEach { Day(it) }
        if (card.tasks.isNotEmpty()) Tasks(card.tasks)
        if (card.messages.isNotEmpty()) Messages(card.messages)
        if (card.mail.isNotEmpty()) Mail(card.mail)
    }

    /** Someone, with a face of their initial; tapped, their chat. */
    @Composable
    private fun Person(name: String, ship: String?) {
        Row(
            Modifier.clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable(enabled = ship != null) { ship?.let { open(dmLink(it)) } }
                .padding(start = 6.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Text(name.trimStart('~').take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column {
                Text(name, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                if (ship != null && ship != name) Text(ship, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f))
            }
        }
    }

    @Composable
    private fun Section(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }

    @Composable
    private fun Messages(messages: List<CardMessage>) = Section("Messages", TalonIcons.Chat) {
        messages.forEach { m ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { open(m.link) }.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("${m.author}  \u00b7  ${m.where}  \u00b7  ${m.time}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.text, fontSize = 19.sp, lineHeight = 26.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    @Composable
    private fun Mail(mail: List<CardMail>) = Section("Mail", Icons.Filled.Email) {
        mail.forEach { t ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { open(t.link) }.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.from, style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (t.unread) FontWeight.Bold else FontWeight.Normal), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.time, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(t.subject, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = if (t.unread) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (t.snippet.isNotBlank()) Text(t.snippet, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    @Composable
    private fun Place(place: String) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(place, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun Day(day: CardDay) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(day.label, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary)
            }
            day.events.forEach { e ->
                Surface(
                    onClick = { open(CALENDAR) },
                    color = if (e.now) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().alpha(if (e.over) 0.55f else 1f),
                ) {
                    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.width(6.dp).height(52.dp).background(e.color?.let(::colorOf) ?: MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                if (e.now) "${e.time}  ·  now" else e.time,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (e.now) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(e.name, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
                            if (e.place.isNotBlank()) Place(e.place)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Tasks(tasks: List<CardTask>) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("Tasks", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary)
            }
            tasks.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.clickable { open(CALENDAR) }.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Box(Modifier.size(18.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape))
                    Text(t.name, fontSize = 20.sp, lineHeight = 26.sp, modifier = Modifier.weight(1f))
                    t.due?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (t.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    companion object {
        /** What a tap on a calendar row opens. */
        private const val CALENDAR = "calendar:"

        /** Start a conversation with Archie rather than a dictation. */
        const val EXTRA_CONVERSE = "io.nisfeb.talon.extra.CONVERSE"
    }

    /** A calendar's "#rrggbb" as a colour, or null for one that is not. */
    private fun colorOf(hex: String): Color? = runCatching {
        Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
    }.getOrNull()
}
