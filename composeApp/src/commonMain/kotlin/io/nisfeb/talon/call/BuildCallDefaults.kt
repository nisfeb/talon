package io.nisfeb.talon.call

/**
 * The calling defaults this build ships, from the generated constants.
 *
 * The app's bridge between TalonBuild and :core, which deliberately
 * knows nothing about either — a headless process built from the same
 * code supplies its own.
 */
val buildCallDefaults: CallDefaults = CallDefaults(
    iceSpec = io.nisfeb.talon.TalonBuild.defaultIce,
    sfuBase = io.nisfeb.talon.TalonBuild.defaultSfuBase,
    sfuGroup = io.nisfeb.talon.TalonBuild.defaultSfuGroup,
    sfuKey = io.nisfeb.talon.TalonBuild.defaultSfuKey,
)
