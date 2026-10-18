package io.nisfeb.talon.urbit

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pin the per-bucket recovery decision in [SettingsSyncImpl.bootstrap].
 *
 * The rc9 fix made Android push AI feature toggles to %settings —
 * before that, the ship had no AI bucket at all for Android-primary
 * users. To self-heal those users on the next bootstrap, we seed
 * the bucket from local whenever the ship is missing it. The
 * predicate here is what gates the seed.
 *
 * The contract:
 *   - missing bucket → seed (true)
 *   - bucket present but empty → seed (true; the ship has nothing
 *     useful to apply, our local state is more informed)
 *   - bucket present with at least one entry → don't seed (false;
 *     ship state is authoritative, we apply via applyBucket)
 *
 * Regression risk if this flips: silent-don't-sync (false negative,
 * the rc9 bug returns) or stale-overwrites (false positive, this
 * device clobbers another device's good state).
 *
 * Changed 2026-10-17 (review M16): "empty or absent synced data never
 * wins over local data, and a deliberate deletion syncs until the user
 * adds something back" (sneagan). An empty bucket cannot tell a list
 * never filled from one a deletion emptied, so a device that missed
 * the deletion seeded it back. A deletion that empties a list now
 * leaves [SettingsSyncImpl.ENTRY_EMPTIED] in it: such a bucket is not
 * empty, so it is applied (as no rows) and never seeded. A bare `{}`
 * still seeds: an rc10 device's deletion leaves exactly that, and so
 * may whatever else empties a bucket, so the rc9 contract stands there.
 */
class SettingsSyncBucketRecoveryTest {

    @Test
    fun `missing or empty bucket triggers seed`() {
        assertTrue(SettingsSyncImpl.bucketIsMissingOrEmpty(null))
        // {} — bucket exists but has no entries. Treat as missing.
        assertTrue(SettingsSyncImpl.bucketIsMissingOrEmpty(buildJsonObject {}))
    }

    @Test
    fun `a bucket a deletion emptied is the ship's word, not a gap to seed`() {
        val emptied = buildJsonObject { put(SettingsSyncImpl.ENTRY_EMPTIED, """{"emptied":1784592399000}""") }
        assertFalse(SettingsSyncImpl.bucketIsMissingOrEmpty(emptied))
    }

    @Test
    fun `bucket with one entry skips seed`() {
        val bucket = buildJsonObject { put("config", "anything") }
        assertFalse(SettingsSyncImpl.bucketIsMissingOrEmpty(bucket))
    }

}
