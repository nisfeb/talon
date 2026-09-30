package io.nisfeb.talon.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which Grubbery apps wait on the owner, by the rule the permits page itself uses. */
class PermitsBannerTest {
    private val asks = Json.parseToJsonElement(
        """[
          {"app":"/apps/a","poke":[{"road":"/sys/bowl.sig","why":"time"},{"road":"/sys/eyre/"}],"peek":[],"make":[]},
          {"app":"/apps/b","poke":[{"road":"/sys/eyre/"}],"peek":[{"road":"/sys/scry/"}],"make":[]},
          {"app":"/apps/c","poke":[],"peek":[],"make":[]}
        ]""",
    ).jsonArray

    @Test
    fun `an app approved as it asks now is settled, in any order`() {
        val approved = Json.parseToJsonElement(
            """{"/apps/a":{"declared":{"poke":["/sys/eyre/","/sys/bowl.sig"],"peek":[],"make":[]}},
                "/apps/b":{"declared":{"poke":[{"road":"/sys/eyre/"}],"peek":[{"road":"/sys/scry/"}],"make":[]}},
                "/apps/c":{"declared":{"poke":[],"peek":[],"make":[]}}}""",
        ).jsonObject
        assertEquals(emptyList(), pendingPermitApps(asks, approved))
    }

    @Test
    fun `an app never approved, or asking for more than it was, is pending`() {
        val approved = Json.parseToJsonElement(
            """{"/apps/a":{"declared":{"poke":[{"road":"/sys/eyre/"}],"peek":[],"make":[]}},
                "/apps/c":{"declared":{"poke":[],"peek":[],"make":[]}}}""",
        ).jsonObject
        assertEquals(listOf("/apps/a", "/apps/b"), pendingPermitApps(asks, approved))
    }
}
