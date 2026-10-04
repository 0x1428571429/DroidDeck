package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {
    private fun targets(query: String) = SettingsCatalog.search(query).map { it.target }.toSet()

    @Test fun allEightCategoriesAreRepresentedAndEveryDestinationIsUnique() {
        assertEquals(SettingsCategory.entries.toSet(), SettingsCatalog.entries.map { it.category }.toSet())
        val targetIds = SettingsCatalog.entries.map { it.target }
        assertEquals("Each search result must have one unambiguous route", targetIds.size, targetIds.toSet().size)
    }

    @Test fun searchTrimsAndMatchesAliasesWithoutReturningResultsForBlankQueries() {
        assertTrue(SettingsCatalog.search("  ").isEmpty())
        assertEquals(setOf("theme"), targets("  COLORS  "))
        assertTrue(targets("wireless debugging").contains("phantom-process"))
        assertTrue(targets("anisotropic filtering").contains("textures-anisotropy"))
    }

    @Test fun protonDefaultsInstalledBuildsAndComponentNamesRouteToTheirOwnPages() {
        assertEquals(setOf("default-proton"), targets("default proton"))
        assertEquals(setOf("editing-proton"), targets("component editing target"))
        assertEquals(setOf("protons"), targets("installed proton"))
        assertTrue(targets("DXVK").contains("components"))
        assertTrue(targets("VKD3D").contains("components"))
        assertFalse(SettingsCatalog.entries.any { it.target == "steam-menu" || it.target == "open-qam" })
    }

    @Test fun routesPreserveDestinationCategoryAndMode() {
        val resolution = SettingsCatalog.entries.single { it.target == "res" }
        assertEquals(SettingsCategory.DISPLAY, resolution.category)
        assertEquals("steam", resolution.mode)

        val renderer = SettingsCatalog.entries.single { it.target == "renderer" }
        assertEquals(SettingsCategory.SESSIONS, renderer.category)
        assertEquals(com.droiddeck.launcher.session.SessionService.MODE_DESKTOP, renderer.mode)

        val overlay = SettingsCatalog.entries.single { it.target == "mangoapp" }
        assertEquals(SettingsCategory.DISPLAY, overlay.category)
        assertEquals("steam", overlay.mode)
    }

    @Test fun commonSearchTermsRouteToTheirSettingsPages() {
        assertEquals(setOf("res"), targets("resolution"))
        assertEquals(setOf("gpu-drivers"), targets("driver"))
        assertTrue(targets("Proton").contains("protons"))
        assertTrue(targets("Proton").contains("components"))
        assertTrue(targets("microphone").contains("mic"))
        assertTrue(targets("ROM").contains("roms"))

        val updates = SettingsCatalog.entries.filter { it.target == "updates" }.single()
        assertEquals(SettingsCategory.SUPPORT, updates.category)
    }

    @Test fun controllerKeyboardButtonRemainsSearchableAsASetting() {
        assertTrue(targets("keyboard button").contains("controller-keyboard"))
        assertFalse(SettingsCatalog.entries.any { it.target == "keyboard" || it.target == "second-screen" })
    }
}
