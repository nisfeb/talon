package io.nisfeb.talon.ai

/** The vendor's default model, or null where it names none. */
fun AiProfile.vendorDefault(): String? = armillary()?.suggested?.get("default")?.takeIf { it.isNotBlank() }

/**
 * The default back on the vendor's pick where the owner never picked it
 * by hand ([AiProfile.defaultOwn]). A payment used to write the model
 * Talon rated best, so Fable outranked the vendor's Opus on price
 * (sneagan, 2026-10-12: the default follows Armillary unless the user
 * explicitly changes it).
 */
fun AiProfile.followingVendorDefault(): AiProfile {
    val d = defaultModel ?: return this
    if (defaultOwn || d.provider != ARMILLARY_PROVIDER || d.model.isBlank() || vendorDefault() == null) return this
    return copy(defaultModel = d.copy(model = ""))
}

/**
 * The default or a feature runs on Armillary and this device has no row
 * for it: a profile from another device, since the row never travels.
 */
fun AiProfile.wantsArmillaryRow(): Boolean = provider(ARMILLARY_PROVIDER) == null &&
    (listOfNotNull(defaultModel) + features.values.mapNotNull { it.model }).any { it.provider == ARMILLARY_PROVIDER }
