package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAdminParserTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(flag: String, raw: String) =
        parseAdminGroup(flag, json.parseToJsonElement(raw).jsonObject)

    @Test
    fun `new schema fully populated group`() {
        val raw = """
            {
              "meta": {
                "title": "My group",
                "description": "a group",
                "image": "",
                "cover": ""
              },
              "admins": ["admin"],
              "seats": {
                "~sampel-palnet": {"roles": ["admin"], "joined": 0},
                "~ricsul-bilwyt-dozzod-nisfeb": {"roles": [], "joined": 0}
              },
              "admissions": {
                "privacy": "private",
                "banned": {"ships": ["~dopzod"], "ranks": []},
                "invited": {
                  "~mastyr-bottec": {"token": "0v2.abc", "at": "~2026"}
                },
                "pending": {
                  "~rallec-tadpur": []
                },
                "requests": {
                  "~wicrys-bortel": {"requestedAt": 0}
                }
              }
            }
        """.trimIndent()
        val g = parse("~sampel-palnet/my-group", raw)

        assertEquals("My group", g.title)
        assertEquals("a group", g.description)
        assertEquals("private", g.privacy)
        // private privacy → shut cordon (for legacy poke back-compat).
        assertEquals("shut", g.cordonKind)
        assertEquals(setOf("admin"), g.adminSects)
        assertEquals(setOf("~dopzod"), g.bannedShips)
        assertEquals(mapOf("~mastyr-bottec" to "0v2.abc"), g.invitedTokenByShip)
        assertEquals(setOf("~rallec-tadpur"), g.directInvitedShips)
        assertEquals(setOf("~wicrys-bortel"), g.pendingShips)
        assertEquals(2, g.members.size)
        // Host ship is admin by flag prefix even if roles list is empty.
        val host = g.members.first { it.ship == "~sampel-palnet" }
        assertTrue(host.isAdmin)
    }

    @Test
    fun `public privacy maps to open cordon`() {
        val raw = """
            {"meta":{"title":"x","description":"","image":"","cover":""},
             "admins":[],"seats":{},"admissions":{"privacy":"public"}}
        """.trimIndent()
        val g = parse("~sampel/x", raw)
        assertEquals("public", g.privacy)
        assertEquals("open", g.cordonKind)
    }

    @Test
    fun `invited ship already in seats is filtered out`() {
        // If a ship has joined, the invite is stale — don't re-expose
        // them in the Invited section.
        val raw = """
            {"meta":{"title":"x","description":"","image":"","cover":""},
             "admins":[],
             "seats":{"~mastyr-bottec":{"roles":[],"joined":0}},
             "admissions":{"privacy":"private",
               "invited":{"~mastyr-bottec":{"token":"0v","at":"~"}}}}
        """.trimIndent()
        val g = parse("~sampel/x", raw)
        assertTrue("no invite shown for joined member", g.invitedTokenByShip.isEmpty())
    }

    @Test
    fun `legacy shut cordon schema falls through`() {
        val raw = """
            {"meta":{"title":"old","description":"","image":"","cover":""},
             "bloc":["admin"],
             "fleet":{"~sampel":{"sects":["admin"],"joined":0}},
             "cordon":{"shut":{"pending":[],"ask":[]}}}
        """.trimIndent()
        val g = parse("~sampel/old", raw)
        assertEquals("shut", g.cordonKind)
        assertEquals(setOf("admin"), g.adminSects)
        assertEquals(1, g.members.size)
        assertTrue(g.members[0].isAdmin)
    }

    @Test
    fun `empty meta strings become null`() {
        val raw = """
            {"meta":{"title":"","description":"","image":"","cover":""},
             "admins":[],"seats":{},"admissions":{"privacy":"secret"}}
        """.trimIndent()
        val g = parse("~sampel/x", raw)
        assertNull(g.title)
        assertNull(g.description)
        assertNull(g.image)
        assertNull(g.cover)
    }

    @Test
    fun `host of flag is admin even without admin role`() {
        val raw = """
            {"meta":{"title":"x","description":"","image":"","cover":""},
             "admins":[],
             "seats":{"~host":{"roles":[],"joined":0},
                      "~guest":{"roles":[],"joined":0}},
             "admissions":{"privacy":"private"}}
        """.trimIndent()
        val g = parse("~host/x", raw)
        assertTrue(g.members.first { it.ship == "~host" }.isAdmin)
        assertFalse(g.members.first { it.ship == "~guest" }.isAdmin)
    }

    @Test
    fun `custom admin role listed in admins elevates member`() {
        val raw = """
            {"meta":{"title":"x","description":"","image":"","cover":""},
             "admins":["moderator"],
             "seats":{"~sampel":{"roles":["moderator"],"joined":0},
                      "~plain":{"roles":[],"joined":0}},
             "admissions":{"privacy":"private"}}
        """.trimIndent()
        val g = parse("~host/x", raw)
        assertTrue(g.members.first { it.ship == "~sampel" }.isAdmin)
        assertFalse(g.members.first { it.ship == "~plain" }.isAdmin)
    }
}
