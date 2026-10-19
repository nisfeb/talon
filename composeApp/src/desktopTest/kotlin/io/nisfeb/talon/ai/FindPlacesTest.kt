package io.nisfeb.talon.ai

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * find_places against a vendor's place search: the two requests it
 * makes, as Orrery's lookups make them (GET {search}/res/v1/local/
 * place_search, the key in X-Subscription-Token), what it says when
 * something fails, and that a dictation, locked or not, is offered it.
 */
class FindPlacesTest {
    private lateinit var tmpDir: File
    private lateinit var db: AppDatabase
    private lateinit var repo: TlonChatRepo

    /** Each request: its path, its query parameters, and its key. */
    private val asked = CopyOnWriteArrayList<Triple<String, Map<String, String>, String?>>()
    @Volatile private var status = HttpStatusCode.OK
    @Volatile private var geocode = """{"results":[{"title":"Florida Theatre","coordinates":[30.3290,-81.6568],"postal_address":{"displayAddress":"128 E Forsyth St, Jacksonville, FL 32202"}}]}"""
    private val stores = """{"results":[
        {"title":"Ace Hardware","coordinates":[30.3200,-81.6600],"postal_address":{"displayAddress":"5 Main St, Jacksonville, FL 32206"}},
        {"title":"The Home Depot","coordinates":[30.2600,-81.6000],"postal_address":{"displayAddress":"9 Far Rd, Jacksonville, FL 32256"}}
    ]}"""

    private val http = HttpClient(MockEngine { req ->
        val p = req.url.parameters
        asked += Triple(req.url.encodedPath, p.names().associateWith { p[it].orEmpty() }, req.headers["X-Subscription-Token"])
        val body = if (p["latitude"] == null) geocode else stores
        respond(body, status, headersOf("Content-Type", "application/json"))
    })

    private val searchRow = AiProvider(ARMILLARY_PROVIDER, ProviderKind.Armillary, "Armillary", searchUrl = "https://vendor.test/apps/armillary/brave", searchKey = "k1")

    private fun cfg(row: AiProvider? = searchRow, on: Boolean = true) = AiSettings.Config(
        provider = AiSettings.Provider.Anthropic, apiKey = "", model = null, agentEnabled = on, askUrbitEnabled = on,
        savedProfile = AiProfile(providers = listOfNotNull(row)),
    )

    @Volatile private var config = cfg()
    private val client = BraveSearchClient(http) { config }

    @BeforeTest
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "talon-places-test-").toFile()
        db = Room.databaseBuilder<AppDatabase>(name = File(tmpDir, "test.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        repo = TlonChatRepo(db = db)
    }

    @AfterTest
    fun tearDown() {
        runCatching { repo.stop() }
        runCatching { db.close() }
        tmpDir.deleteRecursively()
    }

    private fun findPlaces(vararg args: Pair<String, String>): String = runBlocking {
        ToolCatalog.default(repo, db, null, places = client) { it }.single { it.spec.name == "find_places" }
            .execute(buildJsonObject { args.forEach { (k, v) -> put(k, v) } })
    }

    @Test
    fun `the event's place is looked up in its town, then the kind of store searched around it, nearest first`() {
        val said = findPlaces("query" to "hardware store", "near" to "Florida Theatre, 128 E Forsyth St", "area" to "jacksonville fl united states")
        assertEquals(
            listOf(
                Triple("/apps/armillary/brave/res/v1/local/place_search", mapOf("q" to "Florida Theatre, 128 E Forsyth St", "location" to "jacksonville fl united states", "count" to "3"), "k1"),
                Triple("/apps/armillary/brave/res/v1/local/place_search", mapOf("q" to "hardware store", "latitude" to "30.329", "longitude" to "-81.6568", "radius" to "30000", "count" to "20"), "k1"),
            ),
            asked.toList(),
        )
        val lines = said.lines()
        assertEquals("Nearest \"hardware store\" to Florida Theatre, 128 E Forsyth St, Jacksonville, FL 32202, straight-line:", lines[0])
        assertTrue(lines[1].startsWith("1. Ace Hardware") && lines[2].startsWith("2. The Home Depot"), said)
    }

    @Test
    fun `a position is searched around as it is, and with no area none is sent`() {
        findPlaces("query" to "Home Depot", "near" to "30.329,-81.6568")
        assertEquals(1, asked.size, "no lookup for a position")
        asked.clear()
        findPlaces("query" to "Home Depot", "near" to "Florida Theatre")
        assertTrue("location" !in asked.first().second)
    }

    @Test
    fun `what fails is said, and nothing is asked without the vendor's search`() {
        config = cfg(row = null)
        assertEquals("Place search comes through your Armillary vendor's search, which it does not offer.", findPlaces("query" to "Home Depot", "near" to "Florida Theatre"))
        config = cfg(on = false)
        assertEquals("Place search is part of the assistant, which is off in Settings.", findPlaces("query" to "Home Depot", "near" to "Florida Theatre"))
        assertTrue(asked.isEmpty())
        config = cfg()
        geocode = """{"results":[]}"""
        assertEquals("Couldn't find \"Nowhere Hall\" in springfield il united states to search near.", findPlaces("query" to "Home Depot", "near" to "Nowhere Hall", "area" to "springfield il united states"))
        status = HttpStatusCode.TooManyRequests
        assertTrue(findPlaces("query" to "Home Depot", "near" to "30.3,-81.6").startsWith("Place search error 429"))
        assertEquals("Error: near is required.", findPlaces("query" to "Home Depot"))
    }

    // Errands: "paint, a prescription and dog food near rehearsal" finds
    // rehearsal once and each kind of place around that same point.
    @Test
    fun `several kinds of place are each searched around the one spot, found once`() {
        val said = findPlaces("query" to "hardware store; pharmacy ;;hardware store", "near" to "Florida Theatre")
        assertEquals(1, asked.count { "latitude" !in it.second }, "one lookup of near")
        val searches = asked.filter { "latitude" in it.second }
        assertEquals(listOf("hardware store", "pharmacy"), searches.map { it.second["q"] }, "blanks and repeats dropped")
        assertTrue(searches.all { it.second["latitude"] == "30.329" && it.second["longitude"] == "-81.6568" })
        val groups = said.split("\n\n")
        assertEquals(2, groups.size, said)
        assertTrue(groups[0].startsWith("Nearest \"hardware store\"") && groups[1].startsWith("Nearest \"pharmacy\""), said)
    }

    // OsmAnd routes only to coordinates (sneagan, 2026-10-19: OsmAnd where
    // Google Maps is not): the place is looked up once, by the same search.
    @Test
    fun `a place is located by the vendor's search, and coordinates are taken as given`() = runBlocking {
        assertEquals(30.329 to -81.6568, client.locate("Florida Theatre, Jacksonville"))
        val ask = asked.single()
        assertEquals("Florida Theatre, Jacksonville" to "1", ask.second["q"] to ask.second["count"])
        assertEquals("k1", ask.third)
        asked.clear()
        assertEquals(41.4036 to 2.1744, client.locate("41.4036, 2.1744"))
        assertTrue(asked.isEmpty(), "nothing to look up")
        geocode = """{"results":[]}"""
        assertEquals(null, client.locate("Nowhere at all"))
        status = HttpStatusCode.BadGateway
        assertEquals(null, client.locate("Florida Theatre"), "a failed search is no place, not an error")
        config = cfg(row = null)
        asked.clear()
        assertEquals(null, client.locate("Florida Theatre"))
        assertTrue(asked.isEmpty(), "no vendor search, nothing asked")
    }

    /** Every link handed to the device, and whether it took it. */
    private val opened = CopyOnWriteArrayList<String>()
    @Volatile private var opens = true
    private fun handOff(name: String, dials: Boolean, vararg args: Pair<String, String>): String = runBlocking {
        ToolCatalog.default(repo, db, null, open = { opened += it; opens }, dials = dials) { it }.single { it.spec.name == name }
            .execute(buildJsonObject { args.forEach { (k, v) -> put(k, v) } })
    }

    // "Take me there" and "call it": the device's maps and dialer, and an
    // answer that does not claim a call was made.
    @Test
    fun `take me there opens maps at the address, and call it opens the dialer without calling`() {
        // A computer knows no "here": its map search, said as just that.
        assertEquals("Opened maps at 5 Main St, Jacksonville, FL 32206; directions are a tap away there.", handOff("open_directions", false, "destination" to "5 Main St, Jacksonville, FL 32206", "mode" to "Walking"))
        assertEquals("https://www.openstreetmap.org/search?query=5%20Main%20St%2C%20Jacksonville%2C%20FL%2032206", opened.single())
        assertEquals(io.nisfeb.talon.ui.mapsDirections("5 Main St, Jacksonville, FL 32206", "walking").uri, opened.single(), "the mode as the platform takes it")
        opened.clear()
        assertEquals("Opened the dialer with +1 (904) 555-0100. The call starts when the user taps call.", handOff("call_place", true, "number" to "+1 (904) 555-0100"))
        assertEquals("tel:+19045550100", opened.single())
    }

    @Test
    fun `a hand-off that does not go says so, and a non-number is not dialled`() {
        opens = false
        assertEquals("Couldn't open a maps app.", handOff("open_directions", false, "destination" to "Florida Theatre"))
        assertEquals("Couldn't open the dialer.", handOff("call_place", true, "number" to "904 555 0100"))
        opened.clear()
        assertTrue(handOff("call_place", true, "number" to "ext 123456").startsWith("Error:"), "six digits")
        assertTrue(opened.isEmpty())
        opens = true
        handOff("call_place", true, "number" to "555-0100")
        assertEquals(listOf("tel:5550100"), opened.toList(), "seven digits is a local number")
        opened.clear()
        assertTrue(handOff("open_directions", false, "destination" to " ").startsWith("Error:"))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `no dialer without dials, and no hand-offs without a screen`() {
        val phone = ToolCatalog.default(repo, db, null, open = { true }, dials = true) { it }.map { it.spec.name }
        assertTrue("open_directions" in phone && "call_place" in phone)
        val computer = ToolCatalog.default(repo, db, null, open = { true }) { it }.map { it.spec.name }
        assertTrue("open_directions" in computer && "call_place" !in computer)
        val loop = ToolCatalog.default(repo, db, null, dials = true) { it }.map { it.spec.name }
        assertTrue("open_directions" !in loop && "call_place" !in loop, "a scheduled loop has nobody at the screen")
    }

    // Asked in the car: a dictation is offered them, and they are reads, so
    // a locked phone keeps them.
    @Test
    fun `a dictation is offered places, the way there, the dialer and the web, locked or not`() {
        val tools = ToolCatalog.default(repo, db, null, braveSearch = client, places = client, open = { true }, dials = true) { it }
        val names = listOf("find_places", "open_directions", "call_place", "web_search")
        for (locked in listOf(false, true)) {
            val offered = DictationAgent.toolsFor(tools, locked).map { it.spec.name }
            assertTrue(offered.containsAll(names), "locked=$locked: $offered")
        }
        assertTrue(DictationAgent.readsFor(tools).map { it.spec.name }.containsAll(names))
        assertTrue(ToolCatalog.default(repo, db, null) { it }.none { it.spec.name == "find_places" }, "only where the caller passes the client")
    }
}
