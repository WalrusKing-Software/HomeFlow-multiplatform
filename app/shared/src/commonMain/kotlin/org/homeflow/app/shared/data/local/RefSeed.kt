package org.homeflow.app.shared.data.local

/**
 * Bundled reference data — seeded on first open (see [LocalBootstrap]).
 * Keyed by slug (D4: slugs are stable across stores; local UUIDs are random per device).
 * Labels are derived from slugs (snake_case → Title Case) at seed time for simplicity.
 */
object RefSeed {

    data class CategorySeed(
        val slug: String,
        val label: String,
        val selectionType: String,
        val phase: String,
        val sortOrder: Int,
        val options: List<OptionSeed>,
    )

    data class OptionSeed(
        val slug: String,
        val label: String,
        val sortOrder: Int,
    )

    data class RegionSeed(
        val slug: String,
        val label: String,
        val sortOrder: Int,
        val locations: List<LocationSeed>,
    )

    data class LocationSeed(
        val slug: String,
        val label: String,
        val sortOrder: Int,
    )

    private fun slug2Label(slug: String): String =
        slug.split('_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    private fun options(vararg slugs: String): List<OptionSeed> =
        slugs.mapIndexed { i, s -> OptionSeed(s, slug2Label(s), i) }

    val CATEGORIES: List<CategorySeed> = listOf(
        CategorySeed(
            slug = "emotions",
            label = "Emotions",
            selectionType = "multi",
            phase = "always",
            sortOrder = 0,
            options = options(
                "fine", "mood_swings", "sensitive", "angry",
                "irritable", "anxious", "insecure", "sad_depressed",
            ),
        ),
        CategorySeed(
            slug = "sleep_quality",
            label = "Sleep Quality",
            selectionType = "multi",
            phase = "always",
            sortOrder = 1,
            options = options(
                "trouble_falling_asleep", "trouble_staying_asleep",
                "trouble_waking_up", "woke_rested",
            ),
        ),
        CategorySeed(
            slug = "energy",
            label = "Energy",
            selectionType = "single",
            phase = "always",
            sortOrder = 2,
            options = options(
                "exhausted", "tired", "okay", "energetic", "fully_energized",
            ),
        ),
        CategorySeed(
            slug = "sex",
            label = "Sex",
            selectionType = "multi",
            phase = "always",
            sortOrder = 3,
            options = options(
                "protected", "unprotected", "no_sex", "high_drive",
                "low_drive", "sex_toys", "orgasm", "pain_during",
            ),
        ),
        CategorySeed(
            slug = "discharge",
            label = "Discharge",
            selectionType = "multi",
            phase = "always",
            sortOrder = 4,
            options = options(
                "sticky", "creamy", "watery", "clumpy", "white", "yellow", "none",
            ),
        ),
        CategorySeed(
            slug = "skin",
            label = "Skin",
            selectionType = "multi",
            phase = "always",
            sortOrder = 5,
            options = options(
                "fine", "acne", "dry", "oily", "itchy", "red", "inflamed", "puffy",
            ),
        ),
        CategorySeed(
            slug = "digestion",
            label = "Digestion",
            selectionType = "multi",
            phase = "always",
            sortOrder = 6,
            options = options(
                "fine", "bloating", "gas", "heartburn",
                "nausea", "diarrhea", "constipation",
            ),
        ),
        CategorySeed(
            slug = "blood_flow",
            label = "Blood Flow",
            selectionType = "single",
            phase = "menstruation",
            sortOrder = 7,
            options = options("light", "medium", "heavy", "super_heavy"),
        ),
        CategorySeed(
            slug = "collection_method",
            label = "Collection Method",
            selectionType = "single",
            phase = "menstruation",
            sortOrder = 8,
            options = options("tampon", "pad", "panty_liner"),
        ),
        CategorySeed(
            slug = "mind",
            label = "Mind",
            selectionType = "multi",
            phase = "menstruation",
            sortOrder = 9,
            options = options(
                "productive", "unproductive", "motivated", "unmotivated",
                "focused", "distracted", "calm", "stressed",
                "brain_fog", "clear_headed", "forgetful",
            ),
        ),
    )

    val PAIN_REGIONS: List<RegionSeed> = listOf(
        RegionSeed(
            slug = "head_neck",
            label = "Head & Neck",
            sortOrder = 0,
            locations = listOf(
                LocationSeed("front_headache", "Front Headache", 0),
                LocationSeed("back_headache", "Back Headache", 1),
                LocationSeed("migraine", "Migraine", 2),
                LocationSeed("sinus_pressure", "Sinus Pressure", 3),
                LocationSeed("neck", "Neck", 4),
            ),
        ),
        RegionSeed(
            slug = "back",
            label = "Back",
            sortOrder = 1,
            locations = listOf(
                LocationSeed("lower_back", "Lower Back", 0),
                LocationSeed("upper_back", "Upper Back", 1),
                LocationSeed("kidneys", "Kidneys", 2),
            ),
        ),
        RegionSeed(
            slug = "abdomen",
            label = "Abdomen",
            sortOrder = 2,
            locations = listOf(
                LocationSeed("stomach", "Stomach", 0),
                LocationSeed("uterus", "Uterus", 1),
                LocationSeed("ovaries", "Ovaries", 2),
                LocationSeed("pelvis", "Pelvis", 3),
            ),
        ),
        RegionSeed(
            slug = "legs",
            label = "Legs",
            sortOrder = 3,
            locations = listOf(
                LocationSeed("sciatic_nerve", "Sciatic Nerve", 0),
                LocationSeed("groin", "Groin", 1),
                LocationSeed("tailbone", "Tailbone", 2),
                LocationSeed("thighs", "Thighs", 3),
            ),
        ),
        RegionSeed(
            slug = "vagina",
            label = "Vagina",
            sortOrder = 4,
            locations = listOf(
                LocationSeed("clitoris", "Clitoris", 0),
                LocationSeed("vulva", "Vulva", 1),
                LocationSeed("cervix", "Cervix", 2),
            ),
        ),
    )

    /** All category slugs — used to seed default preference order. */
    val ALL_CATEGORY_SLUGS: List<String> = CATEGORIES.map { it.slug }
}
