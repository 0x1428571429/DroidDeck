package com.droiddeck.launcher.ui

sealed interface SettingsDestination {
    val key: String

    data class Category(
        val category: SettingsCategory,
        val mode: String = "steam",
        val target: String? = null,
    ) : SettingsDestination {
        override val key = "settings:${category.name}:$mode:${target.orEmpty()}"
    }

    data class Components(val tab: String? = null) : SettingsDestination {
        override val key = "components"
    }

    data object Protons : SettingsDestination { override val key = "protons" }
    data class Performance(val target: String? = null) : SettingsDestination { override val key = "performance:${target.orEmpty()}" }
    data object Mapping : SettingsDestination { override val key = "controller-mapping" }
}

data class SettingsHistory(val destinations: List<SettingsDestination> = emptyList()) {
    val current: SettingsDestination? get() = destinations.lastOrNull()

    fun open(destination: SettingsDestination): SettingsHistory =
        if (destination == current) this else copy(destinations = destinations + destination)

    fun replace(destination: SettingsDestination): SettingsHistory =
        copy(destinations = destinations.dropLast(1) + destination)

    fun back(): SettingsHistory = copy(destinations = destinations.dropLast(1))
}
