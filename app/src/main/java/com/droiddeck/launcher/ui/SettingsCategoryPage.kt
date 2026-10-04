package com.droiddeck.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsCategoryPage(
    s: FrontEndState,
    a: FrontEndActions,
    category: SettingsCategory,
    mode: String,
    target: String?,
    onOpenDeveloperOptions: () -> Unit,
    onRequestWirelessAdb: (Boolean) -> Unit,
    modeContent: @Composable (MenuHost) -> Unit,
) {
    val host = rememberMenuHost()
    val entry = remember { FocusRequester() }
    val frontFocus = LocalFrontFocus.current
    var hasFocus by remember { mutableStateOf(false) }
    var lastItem by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(frontFocus) {
        snapshotFlow { if (hasFocus) frontFocus?.last else null }.filterNotNull().collect { lastItem = it }
    }
    val back = { if (host.open != null) host.open = null else a.onPageBack() }
    BackHandler(enabled = host.open != null, onBack = back)
    LaunchedEffect(category, mode) {
        if (target == null) {
            focusWithinFrames({ hasFocus }) {
                val id = lastItem
                if (id != null && (frontFocus?.attached?.get(id) ?: 0) > 0) frontFocus!!.items.getValue(id)
                else entry
            }
        }
    }
    CompositionLocalProvider(LocalSettingsTarget provides target, LocalSettingsAnchorsEnabled provides true) {
        SettingsPage(
            host, category.title, back, eyebrow = "Settings",
            lede = category.description,
        ) {
            if (category == SettingsCategory.DISPLAY || category == SettingsCategory.SESSIONS) {
                SegmentedTabs(
                    listOf("steam" to "Steam", "desktop" to "Desktop"), mode,
                    Modifier.padding(bottom = 10.dp), a.onSettingsMode,
                )
                Lede("${if (mode == "steam") "Steam" else "Desktop"} session defaults. Controls marked Shared apply to both modes.")
            } else {
                Lede("Shared defaults. Steam-only options are labeled where applicable.")
            }
            Column(Modifier.fillMaxWidth().focusRequester(entry).onFocusChanged { hasFocus = it.hasFocus }.focusGroup()) {
                if (category != SettingsCategory.SESSIONS || mode == "steam") {
                    SetupContent(s, a, category, host, onOpenDeveloperOptions, onRequestWirelessAdb)
                }
                modeContent(host)
            }
        }
    }
}
