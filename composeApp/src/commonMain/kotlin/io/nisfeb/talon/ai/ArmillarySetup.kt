package io.nisfeb.talon.ai

/**
 * The model Talon gives what runs on Armillary: one that may use tools,
 * which the assistant needs; then one that keeps nothing (ZDR), since
 * catch-up and the assistant read messages; then one known to use tools;
 * then the strongest. Speech and embedding models are not chat models.
 * ponytail: [outPrice] is the only strength the vendor's catalog says;
 * a vendor tag naming a role, when a cheaper model is the better one.
 */
fun armillaryBest(models: List<ModelInfo>, outPrice: (String) -> Long): String? =
    models.filter { !it.speech && "embed" !in it.id.lowercase() }
        .sortedWith(
            compareByDescending<ModelInfo> { it.tools != false }
                .thenByDescending { it.zdr }
                .thenByDescending { it.tools == true }
                .thenByDescending { outPrice(it.id) },
        )
        .firstOrNull()?.id

/**
 * The profile once the owner has paid for Armillary: what does not work
 * is given [best], and what works stays the owner's (their decision,
 * 2026-09-28). A gap is no default model, one on a provider with no key
 * or address, a blank Armillary pick (which ran whatever the ship listed
 * first), and an assistant on a model that cannot use tools.
 *
 * No switch is turned: catch-up and the assistant read messages, and
 * turning them on is the owner's own tap. Triage is never moved, since
 * it reads every message unasked, on this device.
 */
fun AiProfile.filledWithArmillary(best: String): AiProfile {
    val ours = ModelRef(ARMILLARY_PROVIDER, best)
    val p = if (provider(ARMILLARY_PROVIDER) != null) this
    else copy(providers = providers + AiProvider(ARMILLARY_PROVIDER, ProviderKind.Armillary, ProviderKind.Armillary.label))
    fun gap(ref: ModelRef?) = ref == null || (ref.provider == ARMILLARY_PROVIDER && ref.model.isBlank()) ||
        p.resolve(ref)?.problem() != null
    val filled = p.copy(defaultModel = p.defaultModel.takeUnless(::gap) ?: ours)
    val features = filled.features.toMutableMap()
    for (f in listOf(AiFeature.CatchUp, AiFeature.Assistant)) {
        val s = features[f] ?: FeatureSetting()
        val noTools = f == AiFeature.Assistant &&
            filled.resolve(f)?.let { r -> r.provider.models.firstOrNull { it.id == r.model }?.tools == false } == true
        if ((s.model != null && gap(s.model)) || noTools) features[f] = s.copy(model = ours)
    }
    return filled.copy(features = features)
}

/**
 * The default or a feature runs on Armillary and this device has no row
 * for it: a profile from another device, since the row never travels.
 */
fun AiProfile.wantsArmillaryRow(): Boolean = provider(ARMILLARY_PROVIDER) == null &&
    (listOfNotNull(defaultModel) + features.values.mapNotNull { it.model }).any { it.provider == ARMILLARY_PROVIDER }
