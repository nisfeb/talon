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
 */
class SettingsSyncBucketRecoveryTest {

    @Test
    fun `missing bucket triggers seed`() {
        assertTrue(SettingsSyncImpl.bucketIsMissingOrEmpty(null))
    }

    @Test
    fun `empty bucket triggers seed`() {
        // {} — bucket exists but has no entries. Treat as missing
        // for recovery purposes.
        assertTrue(SettingsSyncImpl.bucketIsMissingOrEmpty(buildJsonObject {}))
    }

    @Test
    fun `bucket with one entry skips seed`() {
        val bucket = buildJsonObject { put("config", "anything") }
        assertFalse(SettingsSyncImpl.bucketIsMissingOrEmpty(bucket))
    }

    @Test
    fun `bucket with many entries skips seed`() {
        val bucket = buildJsonObject {
            put("a", "1")
            put("b", "2")
            put("c", "3")
        }
        assertFalse(SettingsSyncImpl.bucketIsMissingOrEmpty(bucket))
    }
}
