package io.nisfeb.talon.notifications

import android.content.Context
import io.nisfeb.talon.Notifications as ProductionNotifications

class AndroidNotifications(private val ctx: Context) : Notifications {

    init {
        ProductionNotifications.ensureChannel(ctx)
    }

    override fun showMessage(
        whom: String,
        postId: String?,
        parentId: String?,
        title: String,
        body: String,
        sentMs: Long,
    ) {
        ProductionNotifications.showMessage(ctx, whom, postId, parentId, title, body, sentMs)
    }

    override fun showWatchwordHit(
        whom: String,
        term: String,
        postId: String,
        title: String,
        body: String,
        sentMs: Long,
    ) {
        // The production object takes terms: List<String> and label: String.
        // The interface exposes a simpler shape: a single term string and
        // a pre-formatted title. Adapt by wrapping term in a list and
        // using title as the label (the production builds its own title
        // from label + terms, so passing term as both is a reasonable v1
        // approximation).
        ProductionNotifications.showWatchwordHit(
            context = ctx,
            whom = whom,
            postId = postId,
            parentId = null,
            terms = listOf(term),
            label = title,
            body = body,
            sentMs = sentMs,
        )
    }

    override fun showDailyDigest(title: String, body: String) {
        // The production object requires ship, dateLocal, and generatedAtMs.
        // The interface abstracts those away; supply placeholders for v1.
        // Stage D wires up a richer digest screen and can pass real values.
        ProductionNotifications.showDailyDigest(
            context = ctx,
            ship = "",
            dateLocal = "",
            title = title,
            body = body,
            generatedAtMs = System.currentTimeMillis(),
        )
    }

    override fun cancelAllForChat(whom: String) {
        ProductionNotifications.cancelAllForChat(ctx, whom)
    }
}
