package org.homeflow.app.shared.data.local

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RefSeedTest {

    @Test
    fun `seed produces correct number of categories`() {
        assertEquals(10, RefSeed.CATEGORIES.size)
    }

    @Test
    fun `all category slugs are unique`() {
        val slugs = RefSeed.CATEGORIES.map { it.slug }
        assertEquals(slugs.size, slugs.toSet().size)
    }

    @Test
    fun `seed data inserts all categories and options into db`() {
        val db = TestDbHelper.inMemory()
        LocalBootstrap.seed(db)

        val cats = db.refDataQueries.selectAllCategories().executeAsList()
        assertEquals(10, cats.size)

        // Each category must have options.
        for (cat in cats) {
            val opts = db.refDataQueries.selectOptionsByCategory(cat.id).executeAsList()
            assertTrue(opts.isNotEmpty(), "Category ${cat.slug} has no options")
        }
    }

    @Test
    fun `seed data inserts all pain regions and locations`() {
        val db = TestDbHelper.inMemory()
        LocalBootstrap.seed(db)

        val regions = db.refDataQueries.selectAllRegions().executeAsList()
        assertEquals(5, regions.size)

        for (region in regions) {
            val locs = db.refDataQueries.selectLocationsByRegion(region.id).executeAsList()
            assertTrue(locs.isNotEmpty(), "Region ${region.slug} has no locations")
        }
    }

    @Test
    fun `seed is idempotent - double seed does not duplicate rows`() {
        val db = TestDbHelper.inMemory()
        LocalBootstrap.seed(db)
        LocalBootstrap.seed(db)  // second call should no-op

        val cats = db.refDataQueries.selectAllCategories().executeAsList()
        assertEquals(10, cats.size)
    }

    @Test
    fun `emotions category has 8 options`() {
        val db = TestDbHelper.inMemory()
        LocalBootstrap.seed(db)

        val cat = db.refDataQueries.selectCategoryBySlug("emotions").executeAsOneOrNull()
        assertNotNull(cat)
        val opts = db.refDataQueries.selectOptionsByCategory(cat.id).executeAsList()
        assertEquals(8, opts.size)
    }

    @Test
    fun `blood_flow is single-select menstruation category`() {
        val cat = RefSeed.CATEGORIES.find { it.slug == "blood_flow" }
        assertNotNull(cat)
        assertEquals("single", cat.selectionType)
        assertEquals("menstruation", cat.phase)
    }

    @Test
    fun `all symptom slugs are present in ALL_CATEGORY_SLUGS`() {
        val expected = RefSeed.CATEGORIES.map { it.slug }.toSet()
        assertEquals(expected, RefSeed.ALL_CATEGORY_SLUGS.toSet())
    }

    @Test
    fun `users row inserted by bootstrap`() {
        val db = TestDbHelper.inMemory()
        LocalBootstrap.seed(db)

        val user = db.usersQueries.selectById(LocalBootstrap.LOCAL_USER_ID).executeAsOneOrNull()
        assertNotNull(user)
        assertEquals(LocalBootstrap.LOCAL_USER_ID, user.id)
    }
}
