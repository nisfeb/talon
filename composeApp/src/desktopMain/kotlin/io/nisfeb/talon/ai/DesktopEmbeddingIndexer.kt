package io.nisfeb.talon.ai

import io.nisfeb.talon.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Desktop's [MessageIndexer], over the ONNX [DesktopEmbedder]. */
class DesktopEmbeddingIndexer(
    db: AppDatabase,
    embedder: DesktopEmbedder,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : MessageIndexer(db, embedder::embed, scope)
