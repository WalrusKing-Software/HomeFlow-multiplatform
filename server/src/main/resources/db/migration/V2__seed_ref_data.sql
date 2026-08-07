-- V2__seed_ref_data.sql — reference data seed.
--
-- Seeds the read-only lookup tables from the "Seed Data Summary" in
-- _planning/data-model.md. Idempotent: every INSERT uses ON CONFLICT DO NOTHING
-- against the natural-key UNIQUE constraints, so re-running is a no-op and safe.
-- Options/locations resolve their parent by slug, so this does not depend on the
-- gen_random_uuid() values assigned to the parent rows.

-- ── Symptom categories ───────────────────────────────────────────────────────

INSERT INTO ref_symptom_categories (slug, label, selection_type, phase, sort_order) VALUES
    ('emotions',          'Emotions',          'multi',  'always',       1),
    ('sleep_quality',     'Sleep Quality',     'multi',  'always',       2),
    ('energy',            'Energy',            'single', 'always',       3),
    ('sex',               'Sex',               'multi',  'always',       4),
    ('discharge',         'Discharge',         'multi',  'always',       5),
    ('skin',              'Skin',              'multi',  'always',       6),
    ('digestion',         'Digestion',         'multi',  'always',       7),
    ('blood_flow',        'Blood Flow',        'single', 'menstruation', 8),
    ('collection_method', 'Collection Method', 'single', 'menstruation', 9),
    ('mind',              'Mind',              'multi',  'menstruation', 10)
ON CONFLICT (slug) DO NOTHING;

-- ── Symptom options ──────────────────────────────────────────────────────────

INSERT INTO ref_symptom_options (category_id, slug, label, sort_order)
SELECT c.id, o.slug, o.label, o.sort_order
FROM ref_symptom_categories c
JOIN (
    VALUES
        ('emotions', 'fine',                 'Fine',                  1),
        ('emotions', 'mood_swings',          'Mood Swings',           2),
        ('emotions', 'sensitive',            'Sensitive',             3),
        ('emotions', 'angry',                'Angry',                 4),
        ('emotions', 'irritable',            'Irritable',             5),
        ('emotions', 'anxious',              'Anxious',               6),
        ('emotions', 'insecure',             'Insecure',              7),
        ('emotions', 'sad_depressed',        'Sad / Depressed',       8),

        ('sleep_quality', 'trouble_falling_asleep', 'Trouble Falling Asleep', 1),
        ('sleep_quality', 'trouble_staying_asleep', 'Trouble Staying Asleep', 2),
        ('sleep_quality', 'trouble_waking_up',      'Trouble Waking Up',      3),
        ('sleep_quality', 'woke_rested',            'Woke Rested',            4),

        ('energy', 'exhausted',        'Exhausted',        1),
        ('energy', 'tired',            'Tired',            2),
        ('energy', 'okay',             'Okay',             3),
        ('energy', 'energetic',        'Energetic',        4),
        ('energy', 'fully_energized',  'Fully Energized',  5),

        ('sex', 'protected',    'Protected',    1),
        ('sex', 'unprotected',  'Unprotected',  2),
        ('sex', 'no_sex',       'No Sex',       3),
        ('sex', 'high_drive',   'High Drive',   4),
        ('sex', 'low_drive',    'Low Drive',    5),
        ('sex', 'sex_toys',     'Sex Toys',     6),
        ('sex', 'orgasm',       'Orgasm',       7),
        ('sex', 'pain_during',  'Pain During',  8),

        ('discharge', 'sticky', 'Sticky', 1),
        ('discharge', 'creamy', 'Creamy', 2),
        ('discharge', 'watery', 'Watery', 3),
        ('discharge', 'clumpy', 'Clumpy', 4),
        ('discharge', 'white',  'White',  5),
        ('discharge', 'yellow', 'Yellow', 6),
        ('discharge', 'none',   'None',   7),

        ('skin', 'fine',     'Fine',     1),
        ('skin', 'acne',     'Acne',     2),
        ('skin', 'dry',      'Dry',      3),
        ('skin', 'oily',     'Oily',     4),
        ('skin', 'itchy',    'Itchy',    5),
        ('skin', 'red',      'Red',      6),
        ('skin', 'inflamed', 'Inflamed', 7),
        ('skin', 'puffy',    'Puffy',    8),

        ('digestion', 'fine',         'Fine',         1),
        ('digestion', 'bloating',     'Bloating',     2),
        ('digestion', 'gas',          'Gas',          3),
        ('digestion', 'heartburn',    'Heartburn',    4),
        ('digestion', 'nausea',       'Nausea',       5),
        ('digestion', 'diarrhea',     'Diarrhea',     6),
        ('digestion', 'constipation', 'Constipation', 7),

        ('blood_flow', 'light',       'Light',       1),
        ('blood_flow', 'medium',      'Medium',      2),
        ('blood_flow', 'heavy',       'Heavy',       3),
        ('blood_flow', 'super_heavy', 'Super Heavy', 4),

        ('collection_method', 'tampon',      'Tampon',      1),
        ('collection_method', 'pad',         'Pad',         2),
        ('collection_method', 'panty_liner', 'Panty Liner', 3),

        ('mind', 'productive',     'Productive',     1),
        ('mind', 'unproductive',   'Unproductive',   2),
        ('mind', 'motivated',      'Motivated',      3),
        ('mind', 'unmotivated',    'Unmotivated',    4),
        ('mind', 'focused',        'Focused',        5),
        ('mind', 'distracted',     'Distracted',     6),
        ('mind', 'calm',           'Calm',           7),
        ('mind', 'stressed',       'Stressed',       8),
        ('mind', 'brain_fog',      'Brain Fog',      9),
        ('mind', 'clear_headed',   'Clear Headed',   10),
        ('mind', 'forgetful',      'Forgetful',      11)
) AS o(category_slug, slug, label, sort_order) ON o.category_slug = c.slug
ON CONFLICT (category_id, slug) DO NOTHING;

-- ── Pain regions ─────────────────────────────────────────────────────────────

INSERT INTO ref_pain_regions (slug, label, sort_order) VALUES
    ('head_neck', 'Head & Neck', 1),
    ('back',      'Back',        2),
    ('abdomen',   'Abdomen',     3),
    ('legs',      'Legs',        4),
    ('vagina',    'Vagina',      5)
ON CONFLICT (slug) DO NOTHING;

-- ── Pain locations ───────────────────────────────────────────────────────────

INSERT INTO ref_pain_locations (region_id, slug, label, sort_order)
SELECT r.id, l.slug, l.label, l.sort_order
FROM ref_pain_regions r
JOIN (
    VALUES
        ('head_neck', 'front_headache', 'Front Headache', 1),
        ('head_neck', 'back_headache',  'Back Headache',  2),
        ('head_neck', 'migraine',       'Migraine',       3),
        ('head_neck', 'sinus_pressure', 'Sinus Pressure', 4),
        ('head_neck', 'neck',           'Neck',           5),

        ('back', 'lower_back', 'Lower Back', 1),
        ('back', 'upper_back', 'Upper Back', 2),
        ('back', 'kidneys',    'Kidneys',    3),

        ('abdomen', 'stomach', 'Stomach', 1),
        ('abdomen', 'uterus',  'Uterus',  2),
        ('abdomen', 'ovaries', 'Ovaries', 3),
        ('abdomen', 'pelvis',  'Pelvis',  4),

        ('legs', 'sciatic_nerve', 'Sciatic Nerve', 1),
        ('legs', 'groin',         'Groin',         2),
        ('legs', 'tailbone',      'Tailbone',      3),
        ('legs', 'thighs',        'Thighs',        4),

        ('vagina', 'clitoris', 'Clitoris', 1),
        ('vagina', 'vulva',    'Vulva',    2),
        ('vagina', 'cervix',   'Cervix',   3)
) AS l(region_slug, slug, label, sort_order) ON l.region_slug = r.slug
ON CONFLICT (region_id, slug) DO NOTHING;
