package org.homeflow.core.domain

/**
 * The four menstrual-cycle phases. `slug` matches the keys used in the
 * sleep-predictions API payload (`menstruation`, `follicular`, `ovulation`, `luteal`).
 */
enum class CyclePhase(
    val slug: String,
    val label: String,
) {
    MENSTRUATION("menstruation", "Menstruation"),
    FOLLICULAR("follicular", "Follicular"),
    OVULATION("ovulation", "Ovulation"),
    LUTEAL("luteal", "Luteal"),
}
