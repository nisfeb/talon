package io.nisfeb.talon.ai

import io.nisfeb.talon.data.AppDatabase
import kotlinx.coroutines.CoroutineScope

/** Android's [MessageIndexer], over the on-device MediaPipe [Embedder]. */
class EmbeddingIndexer(
    db: AppDatabase,
    embedder: Embedder,
    scope: CoroutineScope,
) : MessageIndexer(db, embedder::embed, scope)
