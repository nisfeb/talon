package io.nisfeb.talon.ai

import io.nisfeb.talon.data.LatticeKeyDao
import io.nisfeb.talon.data.LatticeKeyEntity
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The skills path against a real lattice (118 or later). Runs only when
 * told where and with what key, so CI never sees it. Use a fleet ship,
 * never a real one:
 *
 *   TALON_LATTICE_URL=http://localhost:8120 \
 *   TALON_LATTICE_KEY='<an agent key with skills read>' \
 *   TALON_LATTICE_SKILL='<a skill stored there>' \
 *     ./gradlew --no-configuration-cache :composeApp:desktopTest --tests '*LatticeSkillsLiveTest*' --rerun
 *
 * (The configuration cache keeps the environment of the run that filled it.)
 *
 * TALON_LATTICE_NO_SKILLS_KEY, a key with skills none, checks the 403
 * path too. Nothing here writes: the write probe's empty name is refused.
 */
class LatticeSkillsLiveTest {
    private val url = System.getenv("TALON_LATTICE_URL")
    private val key = System.getenv("TALON_LATTICE_KEY")
    private val skill = System.getenv("TALON_LATTICE_SKILL")
    private val noSkills = System.getenv("TALON_LATTICE_NO_SKILLS_KEY")

    private class Keys(token: String) : LatticeKeyDao {
        val rows = MutableStateFlow(mapOf("~live" to LatticeKeyEntity("~live", token.substringBefore('.'), token)))
        override suspend fun get(ship: String) = rows.value[ship]
        override fun stream(ship: String): Flow<LatticeKeyEntity?> = rows.map { it[ship] }
        override suspend fun upsert(row: LatticeKeyEntity) { rows.value = rows.value + (row.ship to row) }
        override suspend fun delete(ship: String) { rows.value = rows.value - ship }
    }

    private fun keys(token: String) = createAppHttpClient().let { http ->
        LatticeKeys(Keys(token), "~live", LatticeApi(http, http, url), "Live")
    }

    @Test fun `the index is fetched and parsed, and a skill is read`() = runBlocking {
        if (url.isNullOrBlank() || key.isNullOrBlank() || skill.isNullOrBlank()) {
            println("LatticeSkillsLiveTest: no ship given; skipped")
            return@runBlocking
        }
        LatticeKeys.forget()
        val mind = keys(key).mind()
        println("LatticeSkillsLiveTest prompt skills:\n" + mind.prompt.substringAfter("SKILLS\n", "(none)"))
        assertTrue("\n- $skill: " in mind.prompt, "the index line for $skill is in the prompt")
        val read = mind.tools.single { it.spec.name == "skill_read" }
        val body = read.execute(buildJsonObject { put("name", skill) })
        println("LatticeSkillsLiveTest skill_read:\n$body")
        assertTrue(body.startsWith("---\n") && "name: $skill" in body, body)
        println("LatticeSkillsLiveTest spoken skills:\n" + mind.spoken().prompt.substringAfter("SKILLS\n", "(none)"))
        println("LatticeSkillsLiveTest tools: " + mind.tools.map { it.spec.name }.filter { it.startsWith("skill_") })
    }

    @Test fun `a key without skills gets none, and is marked for Settings`() = runBlocking {
        if (url.isNullOrBlank() || noSkills.isNullOrBlank()) {
            println("LatticeSkillsLiveTest: no skills-none key given; skipped")
            return@runBlocking
        }
        LatticeKeys.forget()
        val mind = keys(noSkills).mind()
        assertTrue("SKILLS" !in mind.prompt)
        assertTrue(mind.tools.none { it.spec.name.startsWith("skill_") })
        assertEquals(setOf(noSkills), LatticeKeys.SKILLS_REFUSED.value)
        assertEquals(false, keys(noSkills).skillsReadable())
    }
}
