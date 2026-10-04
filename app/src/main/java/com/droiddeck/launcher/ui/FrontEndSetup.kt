package com.droiddeck.launcher.ui

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.core.PhantomProcessLimit
import com.droiddeck.launcher.core.PhantomProcessStatus
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.R

// Existing launcher preference owners, grouped into Settings categories.

private enum class CheckState { OK, WARN, BUSY }

/** One requirement in Setup's system check: a status mark, what it is, and at most one action. */
@Composable
private fun CheckRow(state: CheckState, title: String, detail: String?, divider: Boolean = true, action: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val tint = when (state) {
        CheckState.OK -> pal.good
        CheckState.WARN -> AttentionAmber
        CheckState.BUSY -> pal.signal
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth()
            .background(if (state == CheckState.WARN) AttentionAmber.copy(alpha = 0.07f) else Color.Transparent)
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f))) {
            Icon(
                when (state) {
                    CheckState.OK -> Icons.Filled.Check
                    CheckState.WARN -> Icons.Filled.PriorityHigh
                    CheckState.BUSY -> Icons.Filled.Refresh
                },
                contentDescription = when (state) {
                    CheckState.OK -> stringResource(R.string.setup_check_done)
                    CheckState.WARN -> stringResource(R.string.setup_check_warn)
                    CheckState.BUSY -> stringResource(R.string.setup_check_busy)                },
                tint = tint, modifier = Modifier.size(16.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            if (detail != null) Text(detail, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (action != null) action()
    }
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

/** Settings owned by Setup's existing state/actions, split into hub categories. */
@Composable
internal fun SetupContent(
    s: FrontEndState,
    a: FrontEndActions,
    category: SettingsCategory,
    host: MenuHost,
    onOpenDeveloperOptions: () -> Unit,
    onRequestWirelessAdb: (Boolean) -> Unit,
) {
    when (category) {
        SettingsCategory.GENERAL -> GeneralSettings(s, a, host)
        SettingsCategory.DISPLAY -> DisplaySettings(s, a, host)
        SettingsCategory.CONTROLS -> ControlsSettings(s, a, host)
        SettingsCategory.AUDIO -> Unit
        SettingsCategory.LIBRARY -> LibrarySettings(s, a)
        SettingsCategory.SESSIONS -> SessionSettings(s, a, host)
        SettingsCategory.COMPATIBILITY -> CompatibilitySettings(s, a)
        SettingsCategory.SUPPORT -> SupportSettings(s, a, onOpenDeveloperOptions, onRequestWirelessAdb)
    }
}

@Composable
private fun GeneralSettings(s: FrontEndState, a: FrontEndActions, host: MenuHost) {
    SettingsGroup(stringResource(R.string.setup_launcher)) {
        SettingsAnchor("theme") { SettingsRow(stringResource(R.string.setup_theme), null) {
            Box {
                ValueChip(Themes.byId(s.theme).label, host.open == "theme") { host.open = if (host.open == "theme") null else "theme" }
                AnchoredMenu(host.open == "theme", onDismiss = { if (host.open == "theme") host.open = null }, title = stringResource(R.string.setup_theme)) { firstItemFocus ->
                    Themes.all.forEachIndexed { index, theme ->
                        MenuItem(theme.label, checked = s.theme == theme.id, focusRequester = if (index == 0) firstItemFocus else null) {
                            a.onTheme(theme.id)
                            host.open = null
                        }
                    }
                }
            }
        } }
        ToggleRow(host, "home-screen", stringResource(R.string.setup_home),
            if (s.homeScreenEnabled) stringResource(R.string.setup_home_on) else stringResource(R.string.setup_home_off),
            s.homeScreenEnabled) { a.onHomeScreen(it) }
        ToggleRow(host, "launcher-fullscreen", stringResource(R.string.setup_fullscreen),
            if (s.launcherFullscreen) stringResource(R.string.setup_fullscreen_on) else stringResource(R.string.setup_fullscreen_off),
            s.launcherFullscreen) { a.onLauncherFullscreen(it) }
        ToggleRow(host, "launcher-animations", stringResource(R.string.setup_animations), null,
            s.animationsEnabled, onChange = a.onAnimationsEnabled)
        SettingsAnchor("default-home") {
            if (s.homeScreenEnabled) ActionRow(stringResource(R.string.setup_default_home),
                s.defaultHomeLabel ?: stringResource(R.string.setup_choose_home), stringResource(R.string.setup_choose), a.onHomeApp)
            else SettingsRow(stringResource(R.string.setup_default_home), "Enable Home screen above to choose DroidDeck as the default Home app.") {}
        }
    }
    SettingsGroup(stringResource(R.string.setup_linux_apps)) {
        ToggleRow(host, "store-enabled", stringResource(R.string.setup_store),
            if (s.storeEnabled) stringResource(R.string.setup_store_on) else stringResource(R.string.setup_store_off),
            s.storeEnabled, onChange = a.onStoreEnabled)
    }
}

@Composable
private fun DisplaySettings(s: FrontEndState, a: FrontEndActions, host: MenuHost) {
    SettingsGroup("Display and graphics") {
        SettingsAnchor("fg") { SettingsRow(stringResource(R.string.frame_gen_title), stringResource(R.string.frame_gen_hint)) {
            Box {
                ValueChip(s.frameGenLabel, host.open == "fg") { host.open = if (host.open == "fg") null else "fg" }
                FrameGenMenu(s, a, host)
            }
        } }
    }
}

@Composable
private fun ControlsSettings(s: FrontEndState, a: FrontEndActions, host: MenuHost) {
    val controller = s.controller
    if (controller != null && a.controller != null) SettingsAnchor("controller-actions") { SettingsGroup(stringResource(R.string.setup_controller)) {
        ControllerRows(host, s.oscMode, controller, a.controller)
    }
    } else Note(stringResource(R.string.setup_controller_unavailable))
    SettingsGroup(stringResource(R.string.setup_session)) {
        ChoiceRow(host, "back-actions", stringResource(R.string.mode_back), SessionPrefs.backActionsOrder(s.backActionsInverted),
            listOf(false to SessionPrefs.BACK_MENU_THEN_QAM, true to SessionPrefs.BACK_QAM_THEN_MENU),
            s.backActionsInverted, onPick = a.onBackActionsInverted)
    }
}

@Composable
private fun LibrarySettings(s: FrontEndState, a: FrontEndActions) {
    SettingsGroup("Library and files") {
        val roms = s.romsDir ?: stringResource(R.string.setup_tool_roms_hint)
        SettingsAnchor("files") { ActionRow(stringResource(R.string.setup_tool_files), null, "Open", a.onFiles) }
        SettingsAnchor("roms") { ActionRow(stringResource(R.string.setup_tool_roms), roms, "Open", a.onRoms) }
    }
}

@Composable
private fun SessionSettings(s: FrontEndState, a: FrontEndActions, host: MenuHost) {
    SettingsGroup(stringResource(R.string.setup_session)) {
        ToggleRow(host, "offline", stringResource(R.string.setup_offline),
            s.offlineAccount?.let { stringResource(R.string.setup_signed_in, it) } ?: stringResource(R.string.setup_sign_in_first),
            s.offline, enabled = s.offlineAccount != null, onChange = { a.onOffline() })
    }
}

@Composable
private fun CompatibilitySettings(s: FrontEndState, a: FrontEndActions) {
    SettingsGroup("Compatibility tools") {
        SettingsAnchor("components") { ActionRow(stringResource(R.string.setup_card_components), stringResource(R.string.setup_card_components_hint), "Open") { a.onComponents(true) } }
        SettingsAnchor("protons") { ActionRow(stringResource(R.string.setup_tool_protons), stringResource(R.string.setup_tool_protons_hint), "Open", a.onProtons) }
        SettingsAnchor("performance") { ActionRow(stringResource(R.string.setup_tool_performance), null, "Open", a.onPerformance) }
    }
}

@Composable
private fun SupportSettings(
    s: FrontEndState,
    a: FrontEndActions,
    onOpenDeveloperOptions: () -> Unit,
    onRequestWirelessAdb: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val host = rememberMenuHost()
    var processLimitBusy by remember { mutableStateOf(false) }
    var processLimitMessage by remember { mutableStateOf<String?>(null) }
    var showLimitDetails by rememberSaveable { mutableStateOf(false) }
    val limitBlocks = PhantomProcessLimit.blocksSteam(s.phantomProcessStatus)
    val gpu = remember { com.droiddeck.launcher.gpu.GpuInfo.detect() }
    val gpuOk = gpu.support == com.droiddeck.launcher.gpu.GpuInfo.Support.TESTED
    val gpuName = remember { DeviceSupport.gpuName() }
    val runtime = when {
        s.busy -> stringResource(R.string.common_working)
        s.removalPending -> stringResource(R.string.setup_retry_removal)
        !s.ready -> stringResource(R.string.setup_install)
        s.available != null && s.available != s.installed -> stringResource(R.string.common_update)
        else -> stringResource(R.string.setup_manage)
    }
    val setProcessLimit: (Boolean) -> Unit = { enabled ->
        processLimitBusy = true
        processLimitMessage = null
        a.onSetPhantomProcessLimit(enabled) { error ->
            processLimitBusy = false
            if (error == null) a.onRefreshPhantomStatus()
            else {
                processLimitMessage = context.getString(R.string.setup_limit_adb_failed)
                onRequestWirelessAdb(enabled)
            }
        }
    }
    SettingsGroup(stringResource(R.string.setup_system_check)) {
        Column(modifier = Modifier.fillMaxWidth().clip(Shape14).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, LocalPalette.current.line, Shape14)) {
            CheckRow(
                if (gpuOk) CheckState.OK else CheckState.WARN,
                when (gpu.support) {
                    com.droiddeck.launcher.gpu.GpuInfo.Support.TESTED -> stringResource(R.string.setup_gpu_supported)
                    com.droiddeck.launcher.gpu.GpuInfo.Support.UNTESTED -> stringResource(R.string.setup_gpu_untested)
                    else -> stringResource(R.string.setup_gpu_unsupported)
                },
                when (gpu.support) {
                    com.droiddeck.launcher.gpu.GpuInfo.Support.TESTED -> stringResource(R.string.setup_gpu_detail, gpu.name, gpuName)
                    com.droiddeck.launcher.gpu.GpuInfo.Support.UNTESTED -> stringResource(R.string.setup_gpu_untested_detail, gpu.name, gpu.supportText.replaceFirstChar { it.lowercase() })
                    else -> stringResource(R.string.setup_gpu_unsupported_detail, gpuName)
                },
            )
            SettingsAnchor("runtime") { CheckRow(
                when { s.busy -> CheckState.BUSY; !s.ready -> CheckState.WARN; else -> CheckState.OK },
                stringResource(R.string.setup_runtime),
                when {
                    s.busy -> null
                    s.removalPending -> stringResource(R.string.runtime_removal_incomplete)
                    !s.ready -> stringResource(R.string.setup_runtime_missing)
                    s.available != null && s.available != s.installed -> stringResource(R.string.setup_runtime_update, s.installed ?: stringResource(R.string.setup_installed))
                    else -> stringResource(R.string.setup_runtime_current, s.installed ?: stringResource(R.string.setup_installed))
                },
                divider = !s.busy,
            ) { SecondaryButton(runtime, enabled = !s.busy && !s.runtimeActionsBlocked && !s.sessionRunning, compact = true, onClick = a.onRuntime) } }
            if (s.busy) {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                    Text(if (s.percent >= 0) stringResource(R.string.setup_runtime_progress, s.stage, s.percent) else s.stage,
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
                    if (s.percent >= 0) LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp))
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                }
            }
            SettingsAnchor("phantom-process") { CheckRow(
                if (limitBlocks) CheckState.WARN else CheckState.OK,
                stringResource(R.string.setup_limit),
                when (s.phantomProcessStatus) {
                    PhantomProcessStatus.ENABLED -> stringResource(R.string.setup_limit_on)
                    PhantomProcessStatus.UNSET -> stringResource(R.string.setup_limit_unset)
                    PhantomProcessStatus.UNREADABLE -> stringResource(R.string.setup_limit_unknown)
                    else -> PhantomProcessLimit.title(s.phantomProcessStatus)
                },
            ) {
                if (limitBlocks) PrimaryButton(if (showLimitDetails) stringResource(R.string.common_hide) else stringResource(R.string.setup_fix_it), compact = true) { showLimitDetails = !showLimitDetails }
                else if (s.phantomProcessStatus != PhantomProcessStatus.NOT_APPLICABLE) SecondaryButton(
                    if (showLimitDetails) stringResource(R.string.common_hide) else stringResource(R.string.setup_details), compact = true,
                ) { showLimitDetails = !showLimitDetails }
            } }
            AnimatedVisibility(showLimitDetails, enter = expandVertically(Motion.sp(1f)) + fadeIn(Motion.sp(1f)), exit = shrinkVertically(Motion.sp(1f)) + fadeOut(Motion.sp(1f))) {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 14.dp, top = 4.dp, bottom = 10.dp)) {
                    Text(if (limitBlocks) PhantomProcessLimit.gateInstructions(s.phantomProcessStatus) else PhantomProcessLimit.instructions(s.phantomProcessStatus),
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
                    Actions {
                        if (limitBlocks) {
                            PrimaryButton(stringResource(R.string.setup_dev_options), compact = true, onClick = onOpenDeveloperOptions)
                            SecondaryButton(stringResource(R.string.setup_use_wireless), compact = true, enabled = !processLimitBusy) { setProcessLimit(false) }
                            SecondaryButton(stringResource(R.string.setup_check_again), compact = true, onClick = a.onRefreshPhantomStatus)
                        } else if (s.phantomProcessStatus == PhantomProcessStatus.DISABLED) {
                            SecondaryButton(stringResource(R.string.setup_limit_turn_on), compact = true, enabled = !processLimitBusy) { setProcessLimit(true) }
                        }
                    }
                    if (processLimitBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    processLimitMessage?.let { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp)) }
                }
            }
            CheckRow(
                if (s.offlineAccount != null) CheckState.OK else CheckState.WARN,
                stringResource(R.string.setup_account),
                s.offlineAccount?.let { if (s.offline) stringResource(R.string.setup_signed_in_offline, it) else stringResource(R.string.setup_signed_in, it) }
                    ?: stringResource(R.string.setup_sign_in),
                divider = false,
            )
        }
    }
    SettingsAnchor("session-logs") { SettingsGroup(stringResource(R.string.setup_session)) {
        ToggleRow(host, "logs", stringResource(R.string.setup_logs), stringResource(R.string.setup_logs_hint), s.logsEnabled) { a.onLogs() }
        SettingsAnchor("latest-logs") { ActionRow(stringResource(R.string.setup_latest_logs), stringResource(R.string.drawer_logs_hint), stringResource(R.string.drawer_share_logs), a.onShareLogs) }
        SettingsAnchor("saved-logs") { ActionRow(stringResource(R.string.setup_saved_logs), stringResource(R.string.setup_saved_logs_hint, com.droiddeck.launcher.session.SessionPaths.KEEP_SESSIONS), stringResource(R.string.setup_clear_logs), a.onClearLogs) }
    } }
    SettingsGroup("More support") {
        SettingsAnchor("updates") { ActionRow("DroidDeck updates", "Build information and release channel", "Open", a.onOpenUpdates) }
    }
}

/** The launcher's own tools as cards: four across, two by two on a narrow page. */
private class ToolSpec(val icon: ImageVector, val title: String, val detail: String, val onClick: () -> Unit)

@Composable
private fun ToolCard(t: ToolSpec, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "toolScale")
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.paneItem("tool:${t.title}")
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(Shape14)
            .background(if (hot) pal.signal.copy(alpha = 0.10f) else colors.surface)
            .glideBorder(hot, Shape14, pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = t.onClick)
            .controllerConfirm(onClick = t.onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(t.icon, contentDescription = null, tint = if (hot) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(t.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t.detail, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FrameGenMenu(s: FrontEndState, a: FrontEndActions, host: MenuHost) {
    FrameGenMenu(host, s.frameGen, s.lossless, a.onFrameGenPick, a.onImportLossless)
}

/**
 * What shapes a launch, beside the game rather than three screens away in Setup. They are the
 * app-wide settings - each card opens the same page or menu Setup does.
 */
@Composable
internal fun LaunchSettings(s: FrontEndState, a: FrontEndActions, host: MenuHost) {
    // Three across, two on a narrow page; each row's cards share one height.
    val columns = if (LocalNarrowPane.current) 2 else 3
    val controller = a.controller
    val cards = buildList<@Composable (Modifier) -> Unit> {
        add { m -> SettingCard(stringResource(R.string.setup_card_components), stringResource(R.string.setup_card_components_hint), "card:components", m) { a.onComponents(true) } }
        add { m ->
            Box(m) {
                SettingCard(stringResource(R.string.frame_gen_title), s.frameGenLabel, "card:fg", Modifier.fillMaxSize()) {
                    host.open = if (host.open == "fg") null else "fg"
                }
                FrameGenMenu(s, a, host)
            }
        }
        if (controller != null) add { m -> SettingCard(stringResource(R.string.setup_card_controls), stringResource(R.string.setup_card_controls_hint), "card:controls", m, controller.onMapping) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        for (row in cards.chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                for (card in row) card(Modifier.weight(1f).fillMaxHeight())
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SettingCard(label: String, value: String, id: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "cardScale")
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier.paneItem(id)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(Shape14)
            .background(if (hot) pal.signal.copy(alpha = 0.10f) else colors.surface)
            .glideBorder(hot, Shape14, pal.signal, pal.line2)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, fontSize = 13.sp, color = if (hot) pal.signal else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
