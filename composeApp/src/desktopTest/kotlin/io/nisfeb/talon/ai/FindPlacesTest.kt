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

    // Asked in the car: a dictation is offered it, and it is a read, so a
    // locked phone keeps it.
    @Test
    fun `a dictation is offered it, locked or not`() {
        val tools = ToolCatalog.default(repo, db, null, places = client) { it }
        assertTrue(DictationAgent.toolsFor(tools, locked = false).any { it.spec.name == "find_places" })
        assertTrue(DictationAgent.toolsFor(tools, locked = true).any { it.spec.name == "find_places" })
        assertTrue(DictationAgent.readsFor(tools).any { it.spec.name == "find_places" })
        assertTrue(ToolCatalog.default(repo, db, null) { it }.none { it.spec.name == "find_places" }, "only where the caller passes the client")
    }
}
