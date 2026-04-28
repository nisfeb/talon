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
        parentId: String?,
        title: String,
        body: String,
        sentMs: Long,
    ) {
        // The production object takes `terms: List<String>` and a raw
        // `label: String` (which it composes into the user-facing title
        // as "$terms in $label"). Our interface exposes a single `term`
        // and a `title` that's documented as the raw label. Wrap term
        // in a list; pass title straight through as label.
        ProductionNotifications.showWatchwordHit(
            context = ctx,
            whom = whom,
            postId = postId,
            parentId = parentId,
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
