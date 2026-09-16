package io.nisfeb.talon.mail

import kotlin.random.Random

/**
 * Mint a draft id.
 *
 * The client mints it, and has to: the save route answers before the
 * ship's writer applies, so an id chosen there could never be told back
 * to the composer that needs it to overwrite the same draft next time.
 *
 * The shape is an Urbit `@uv`: `0v` then base-32 groups of five,
 * dot-separated. Nothing here is a secret — it names a draft on the
 * ship that wrote it and never travels — so this wants to be unlikely
 * to collide rather than unguessable.
 */
fun newDraftId(random: Random = Random): String {
    val alphabet = "0123456789abcdefghijklmnopqrstuv"
    val chars = CharArray(15) { alphabet[random.nextInt(alphabet.length)] }
    // A leading zero is dropped when a @uv is rendered back, so an id we
    // stored and one the ship echoes would not match as strings.
    if (chars[0] == '0') chars[0] = alphabet[1 + random.nextInt(alphabet.length - 1)]
    return "0v" + chars.concatToString().chunked(5).joinToString(".")
}
