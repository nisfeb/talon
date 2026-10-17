package io.nisfeb.talon.ai

/**
 * Archie's voice: a man's, and older (sneagan, 2026-10-17: "can we make
 * the voice male. old maybe"), picked from the phone's own text to speech
 * voices, so nothing leaves the phone to be spoken.
 *
 * No engine marks a voice's age, and few mark its sex, so a man's voice is
 * found by what engines do say: a "male" feature, or a name known to be a
 * man's (Google's en-us-x-iol, -iom and -tpd, en-gb-x-rjs and -gbd,
 * en-au-x-aud; RHVoice's Bdl and Alan). Older is a lower pitch and a slower
 * pace, which any engine can do.
 */
// ponytail: names known to be men's voices, a guess per engine. If the
// phone's log shows none matched, add its voice's name here, or offer a
// voice picker in Settings.
object ArchieVoice {
    /** Lower than the engine's 1.0: older, and deeper. */
    const val PITCH = 0.85f

    /** A touch slower than the engine's 1.0. */
    const val RATE = 0.9f

    /** One of the phone's voices, as the engine describes it. */
    data class Option(val name: String, val languageTag: String, val features: Set<String>, val network: Boolean, val quality: Int)

    private val MEN = listOf("-iol", "-iom", "-tpd", "-rjs", "-gbd", "-aud", "bdl", "alan")

    /** Whether [o] is a man's voice, as far as its engine says. */
    fun isMale(o: Option): Boolean {
        val name = o.name.lowercase()
        val features = o.features.map { it.lowercase() }
        return "male" in features || "gender=male" in features || MEN.any { it in name } || Regex("""(?<!fe)male""").containsMatchIn(name)
    }

    /**
     * The man's voice to speak [wanted] (a language tag such as "en-US")
     * in, or null to keep the engine's own: one that needs no network
     * first, then the owner's own region, then the engine's best quality.
     */
    fun pick(options: List<Option>, wanted: String): String? {
        val language = wanted.substringBefore('-').lowercase()
        return options
            .filter { it.languageTag.substringBefore('-').lowercase() == language && isMale(it) }
            .sortedWith(compareBy<Option>({ it.network }, { !it.languageTag.equals(wanted, ignoreCase = true) }, { -it.quality }))
            .firstOrNull()?.name
    }
}
