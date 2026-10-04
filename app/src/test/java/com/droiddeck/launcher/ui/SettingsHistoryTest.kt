package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsHistoryTest {
    @Test fun specialistBackRestoresCategoryAndMode() {
        val category = SettingsDestination.Category(SettingsCategory.SESSIONS, "desktop", "renderer")
        val history = SettingsHistory().open(category).open(SettingsDestination.Components("gpu"))
        assertEquals(category, history.back().current)
        assertNull(history.back().back().current)
    }

    @Test fun changingModeReplacesCurrentPage() {
        val category = SettingsDestination.Category(SettingsCategory.DISPLAY)
        val history = SettingsHistory().open(category).replace(category.copy(mode = "desktop"))
        assertEquals("desktop", (history.current as SettingsDestination.Category).mode)
        assertNull(history.back().current)
    }

    @Test fun repeatedDestinationsDoNotAddBackSteps() {
        val destination = SettingsDestination.Performance()
        val history = SettingsHistory().open(destination).open(destination)
        assertEquals(1, history.destinations.size)
        assertNull(history.back().back().current)
    }

}
