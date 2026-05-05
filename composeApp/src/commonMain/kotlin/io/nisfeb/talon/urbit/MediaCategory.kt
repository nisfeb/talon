package io.nisfeb.talon.urbit

/**
 * Buckets that [MediaClassifier.extractMedia] sorts inline-content URLs
 * into. Powers the group-info shared-media stats grid (Phase 3) and
 * the per-bucket drilldown list. Order matches the stats grid's
 * top-to-bottom rendering order.
 *
 * Adding a new bucket is a four-touch change:
 *  1. Add the enum value here.
 *  2. Add a rule to [MediaClassifier.extractMedia].
 *  3. Add an icon + label to [GroupInfoPane]'s grid.
 *  4. Add a tap handler to [MediaListPane] (image viewer / player /
 *     uri / etc.) — most new buckets reuse an existing handler.
 */
enum class MediaCategory { Photo, Video, Gif, Voice, Audio, File, Link }

/**
 * Persistence helper. Falls back to [Link] on any parse failure so a
 * future enum rename doesn't blow up rows that pre-date the change.
 */
fun mediaCategoryOrLink(name: String?): MediaCategory {
    if (name.isNullOrBlank()) return MediaCategory.Link
    return runCatching { MediaCategory.valueOf(name) }.getOrDefault(MediaCategory.Link)
}
