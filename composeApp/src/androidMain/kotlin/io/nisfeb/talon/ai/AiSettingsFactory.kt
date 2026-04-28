package io.nisfeb.talon.ai

import android.content.Context

fun createAiSettings(ctx: Context): AiSettingsRepository = AndroidAiSettings(ctx)
