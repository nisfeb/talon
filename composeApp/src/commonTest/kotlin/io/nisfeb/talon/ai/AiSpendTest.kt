package io.nisfeb.talon.ai

import io.nisfeb.talon.data.OrrerySentDao
import io.nisfeb.talon.data.OrrerySentEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AiSpendTest {
    private class Rows : OrrerySentDao {
        val rows = mutableMapOf<Pair<String, String>, OrrerySentEntity>()
        override suspend fun get(ship: String, key: String) = rows[ship to key]
        override suspend fun some(ship: String, keys: List<String>) = keys.mapNotNull { rows[ship to it] }
        override suspend fun under(ship: String, prefix: String) = rows.values.filter { it.ship == ship && it.key.startsWith(prefix) }
        override suspend fun forget(ship: String, key: String) { rows.remove(ship to key) }
        override suspend fun put(row: OrrerySentEntity) { rows[row.ship to row.key] = row }
        override suspend fun putAll(rows: List<OrrerySentEntity>) = rows.forEach { put(it) }
        override suspend fun clear(ship: String) { rows.keys.removeAll { it.first == ship } }
    }

    @Test
    fun `each feature's spend adds up within a month and starts again in the next`() = runTest {
        val sept = 1_789_000_000_000L // mid-September 2026
        val oct = sept + 30L * 86_400_000
        AiSpend.dao = Rows()
        AiSpend.add(AiFeature.CatchUp.name, 0.01, sept)
        AiSpend.add(AiFeature.CatchUp.name, 0.02, sept)
        AiSpend.add(AiSpend.JEV, 0.004, sept)
        AiSpend.add(AiFeature.Assistant.name, null, sept)
        AiSpend.add(AiFeature.Assistant.name, 0.0, sept)
        assertEquals(setOf(AiFeature.CatchUp.name, AiSpend.JEV), AiSpend.month.value.keys, "nothing reported, nothing kept")
        assertEquals(0.03, AiSpend.month.value.getValue(AiFeature.CatchUp.name), 1e-9)
        AiSpend.load(oct)
        assertEquals(emptyMap(), AiSpend.month.value)
        AiSpend.load(sept)
        assertEquals(0.004, AiSpend.month.value.getValue(AiSpend.JEV), 1e-9)
        AiSpend.dao = null
    }
}
