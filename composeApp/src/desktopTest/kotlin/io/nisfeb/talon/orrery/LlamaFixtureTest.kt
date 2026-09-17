package io.nisfeb.talon.orrery

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The floor against the fixture set: the gate a rung must pass before
 * the ladder may select it. Runs only with a model file named, since
 * it is a gigabyte and a minute of CPU:
 *
 *   TALON_MODEL_PATH=/path/to/qwen2.5-1.5b-instruct-q4_k_m.gguf ./gradlew :composeApp:desktopTest --tests '*LlamaFixtureTest*'
 *
 * Judged by field match, never by text: each case names the claims it
 * expects as subject and attr, plus a value check where the value is
 * the point. A case with no expected claims must yield none.
 */
class LlamaFixtureTest {
    private val me = "~zod"
    private val bodies = listOf(
        KnownBody("person/me", "me", listOf("me"), me),
        KnownBody("person/sarah", "Sarah", listOf("Sarah", "wife"), "~sampel-palnet"),
        KnownBody("thing/subaru", "the Subaru", listOf("the car", "subaru"), null),
        KnownBody("place/home", "Home", listOf("home"), null),
        KnownBody("place/johns-machine-shop", "John's Machine Shop", listOf("John's", "the shop"), null),
    )
    private val index = NameIndex(bodies)
    private val attrs = mapOf(
        "person" to listOf("status", "location", "phone", "email", "ship", "birthday", "relationship", "employer", "timezone", "likes", "dislikes"),
        "place" to listOf("type", "address", "phone", "hours", "geo"),
        "thing" to listOf("type", "status", "location", "owner", "make", "model", "plate", "last-service", "warranty-until"),
    )

    private data class Case(val author: String, val text: String, val expect: List<Pair<String, String>>, val value: ((Noticed) -> Boolean)? = null)

    private val cases = listOf(
        Case("~zod", "car died on route 9, stranded waiting for a tow", listOf("thing/subaru" to "status", "person/me" to "status", "person/me" to "location")),
        // "it" is the car; only a rung with context reads that. The floor may say nothing here.
        Case("~zod", "tow guy is here, taking the car to john's machine shop", listOf("thing/subaru" to "location")) { it.value.let { v -> v is JsonObject && v.jsonObject["ref"]?.jsonPrimitive?.content == "place/johns-machine-shop" } },
        Case("~sampel-palnet", "I'm at the shop with the car, they say it needs a new alternator", listOf("person/sarah" to "location")),
        Case("~zod", "home. left the car at john's overnight", listOf("person/me" to "location", "thing/subaru" to "location")),
        Case("~sampel-palnet", "lol did you see the game last night", emptyList()),
        Case("~sampel-palnet", "are you at the shop?", emptyList()),
        Case("~bus", "my new number is 555-0142", listOf("person/bus" to "phone")) { it.value == JsonPrimitive("555-0142") },
    )

    @Test
    fun `the floor reads the fixtures at least as well as the rules`() {
        val path = System.getenv("TALON_MODEL_PATH")
        if (path.isNullOrBlank() || !File(path).isFile) {
            println("LlamaFixtureTest: no model given; skipped")
            return
        }
        LlamaCppModel(path, "fixture").use { model ->
            var hit = 0
            var wanted = 0
            var extra = 0
            val report = StringBuilder()
            runBlocking {
                for (c in cases) {
                    val out = ModelExtractor.extract(model, index, bodies, c.text, c.author, 1_789_646_400_000L, me, attrs)
                    val got = out.map { it.subject to it.attr }
                    val ok = c.expect.filter { it in got }
                    val valueOk = c.value?.let { check -> out.any(check) } ?: true
                    hit += ok.size + (if (c.value != null && valueOk) 1 else 0)
                    wanted += c.expect.size + (if (c.value != null) 1 else 0)
                    if (c.expect.isEmpty() && out.isNotEmpty()) extra++
                    report.append("\n  ${c.author}: \"${c.text}\"\n    expected ${c.expect}, got ${out.map { "${it.subject}.${it.attr}=${it.value}" }}")
                }
            }
            println("LlamaFixtureTest: $hit of $wanted expected claims, $extra chatter cases with claims$report")
            // The rules alone read two of these (Sarah at the shop, and
            // the home line only if it said "I'm home"). The bar for a
            // rung is to beat them without making anything up.
            assertTrue(hit >= 5, "the floor must read at least 5 of $wanted expected claims, read $hit:$report")
            assertTrue(extra == 0, "chatter must not turn into claims:$report")
        }
    }
}
