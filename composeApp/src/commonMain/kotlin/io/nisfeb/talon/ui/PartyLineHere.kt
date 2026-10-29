package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import io.nisfeb.talon.call.CallController
import io.nisfeb.talon.call.PartyLine
import io.nisfeb.talon.call.PartyLineHost
import io.nisfeb.talon.call.PartyState
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The party line of the group a chat is in, as its header offers it, or
 * on a phone its Info: [groupRoom] the group's room whether or not a
 * line is open there, [room] the same once the line is known to exist,
 * [present] how many are on it, and [join] the way on (start it, where
 * we host the group, else join). [join] is null with no line here or no
 * call engine.
 */
data class PartyLineHere(
    val groupRoom: Pair<String, String>?,
    val room: Pair<String, String>?,
    val present: Int,
    val join: (() -> Unit)?,
)

/**
 * [PartyLineHere] for [whom], shared by both app roots and by the chat
 * and its Info, so they agree, as [rememberPartyRecording] is. Asks the
 * host once per group whether a line exists when we hold no invite:
 * a member whose ship had no %trunk when the host announced never heard
 * about it ([CallController.lookForLine] asks only once). Occupancy is
 * the chat's to ask for; this reads what has come.
 */
@Composable
fun rememberPartyLineHere(
    whom: String?,
    callController: CallController?,
    partyLine: PartyLine?,
    db: AppDatabase,
    repo: TlonChatRepo,
    ourShip: String?,
    /** The room's title for everyone on it: the conversation's label, not its key. */
    title: (whom: String) -> String,
    /** The app's own scope: a join outlives the screen it was asked from. */
    scope: CoroutineScope,
): PartyLineHere {
    val hostedRooms by (callController?.rooms ?: MutableStateFlow(emptyMap())).collectAsState()
    val knownInvites by (callController?.invites ?: MutableStateFlow(emptyMap())).collectAsState()
    val groupRoom by produceState<Pair<String, String>?>(null, whom) {
        value = whom?.let { PartyLineHost.roomFor(db, it) }
    }
    LaunchedEffect(groupRoom) {
        val (h, n) = groupRoom ?: return@LaunchedEffect
        callController?.lookForLine(h, n)
    }
    val room = groupRoom?.takeIf { (h, n) -> hostedRooms.containsKey("$h/$n") || knownInvites.containsKey("$h/$n") }
    val presence by (callController?.presence ?: MutableStateFlow(emptyMap())).collectAsState()
    // While we are on this line the roster we can see is the floor: a
    // member on a build that does not heartbeat is still there.
    val live = (partyLine?.state?.collectAsState()?.value as? PartyState.Live)?.takeIf { it.room == room?.second }?.members?.size ?: 0
    val present = maxOf(room?.let { (h, n) -> presence["$h/$n"] } ?: 0, live)
    val join: (() -> Unit)? = if (callController != null && partyLine != null && room != null && whom != null) {
        {
            if (room.first == ourShip) {
                scope.launch { PartyLineHost.startLine(callController, repo, db, whom, title(whom)) }
            } else {
                scope.launch { PartyLineHost.joinLine(callController, db, whom) }
            }
        }
    } else {
        null
    }
    return PartyLineHere(groupRoom, room, present, join)
}
