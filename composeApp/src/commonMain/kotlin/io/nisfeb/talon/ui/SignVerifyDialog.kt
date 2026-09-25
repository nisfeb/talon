package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.urbit.LatticeSign
import io.nisfeb.talon.urbit.SignedRecord
import io.nisfeb.talon.urbit.Verdict
import io.nisfeb.talon.urbit.armor
import io.nisfeb.talon.urbit.latticeDigest
import io.nisfeb.talon.urbit.signedRecordIn
import io.nisfeb.talon.util.rememberAnyFilePicker
import kotlinx.coroutines.launch

/**
 * Sign something with this ship's key, or check what somebody else
 * signed. The ship signs and checks signatures; no key is held here.
 *
 * A file is checked by working out its digest here ([latticeDigest]),
 * comparing it with the signature block's, and asking the ship whether
 * the block's signature is the signer's.
 */
@Composable
fun SignVerifyDialog(signer: LatticeSign, ourShip: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val pickFile = rememberAnyFilePicker()
    var signing by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    var text by remember { mutableStateOf("") }
    var made by remember { mutableStateOf<SignedRecord?>(null) }
    var madeOf by remember { mutableStateOf("") }

    var pasted by remember { mutableStateOf("") }
    var against by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<String?>(null) }

    fun fail(e: Throwable) {
        outcome = e.message ?: "That did not work."
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (signing) "Sign" else "Check a signature") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = signing, onClick = { signing = true; outcome = null }, label = { Text("Sign") })
                    FilterChip(selected = !signing, onClick = { signing = false; outcome = null }, label = { Text("Check") })
                }
                if (signing) {
                    Text(
                        "Signs with $ourShip's own key. Anyone can check it against the key your " +
                            "ship publishes: Azimuth for an Azimuth ship, Bitcoin for a Groundwire comet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; made = null },
                        label = { Text("Something to sign") },
                        minLines = 2,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !busy && text.isNotBlank(),
                            onClick = {
                                busy = true; outcome = null
                                scope.launch {
                                    runCatching { signer.sign(text) }
                                        .onSuccess { made = it; madeOf = "the text above" }
                                        .onFailure(::fail)
                                    busy = false
                                }
                            },
                        ) { Text("Sign the text") }
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                busy = true; outcome = null
                                scope.launch {
                                    runCatching {
                                        val f = pickFile() ?: return@runCatching null
                                        signer.sign(f.bytes) to f.displayName
                                    }.onSuccess { got ->
                                        if (got != null) { made = got.first; madeOf = got.second }
                                    }.onFailure(::fail)
                                    busy = false
                                }
                            },
                        ) { Text("Sign a file") }
                    }
                    made?.let { rec ->
                        Text("Signed $madeOf.", style = MaterialTheme.typography.bodySmall)
                        Text(
                            rec.armor(),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                        )
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(rec.armor()))
                            outcome = "Copied. Paste it wherever the thing itself went."
                        }) { Text("Copy the signature") }
                    }
                } else {
                    Text(
                        "Paste the signature block, then give it the same thing it was made from.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it; outcome = null },
                        label = { Text("Signature block") },
                        minLines = 2,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = against,
                        onValueChange = { against = it; outcome = null },
                        label = { Text("The text it covers, if it was text") },
                        minLines = 2,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !busy && pasted.isNotBlank(),
                            onClick = {
                                val rec = signedRecordIn(pasted)
                                if (rec == null) {
                                    outcome = "That is not a signature block."
                                    return@TextButton
                                }
                                busy = true; outcome = null
                                scope.launch {
                                    runCatching { signer.verify(rec, against.takeIf { it.isNotBlank() }) }
                                        .onSuccess { v ->
                                            outcome = when (v) {
                                                is Verdict.Ok ->
                                                    if (against.isBlank()) "Signed by ${rec.ship}. Nothing was checked against it."
                                                    else "Signed by ${rec.ship}, and it covers that text."
                                                is Verdict.No -> "No: ${v.reason}"
                                            }
                                        }
                                        .onFailure(::fail)
                                    busy = false
                                }
                            },
                        ) { Text("Check") }
                        TextButton(
                            enabled = !busy && pasted.isNotBlank(),
                            onClick = {
                                val rec = signedRecordIn(pasted)
                                if (rec == null) {
                                    outcome = "That is not a signature block."
                                    return@TextButton
                                }
                                busy = true; outcome = null
                                scope.launch {
                                    runCatching {
                                        val f = pickFile() ?: return@runCatching null
                                        val mine = latticeDigest(f.bytes)
                                        if (mine != rec.digest) return@runCatching "No: that file is not what was signed."
                                        when (val v = signer.verify(rec)) {
                                            is Verdict.Ok -> "Signed by ${rec.ship}, and it covers ${f.displayName}."
                                            is Verdict.No -> "No: ${v.reason}"
                                        }
                                    }.onSuccess { if (it != null) outcome = it }.onFailure(::fail)
                                    busy = false
                                }
                            },
                        ) { Text("Check a file") }
                    }
                }
                if (busy) {
                    Spacer(Modifier.size(4.dp))
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                outcome?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Done") } },
    )
}
