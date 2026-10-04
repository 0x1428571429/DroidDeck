package com.droiddeck.launcher.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DisplaySettings
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.core.PhantomProcessLimit
import kotlinx.coroutines.flow.filterNotNull

/** The settings taxonomy is shared by the hub, category pages, and the searchable index. */
enum class SettingsCategory(val title: String, val description: String) {
    GENERAL("General", "Launcher appearance and home-screen behavior"),
    DISPLAY("Display & graphics", "Resolution, effects, scaling, and frame generation"),
    CONTROLS("Controls & input", "Controller layout, touch behavior, and button mapping"),
    AUDIO("Audio & microphone", "Session audio and microphone options"),
    LIBRARY("Library & storage", "Game folders, storage, ROMs, and file access"),
    SESSIONS("Sessions", "Session startup, suspend, and client behavior"),
    COMPATIBILITY("Compatibility & performance", "Proton, components, and performance tools"),
    SUPPORT("Support & diagnostics", "System checks, logs, runtime, and app updates"),
}

data class SettingsEntry(
    val target: String,
    val category: SettingsCategory,
    val title: String,
    val description: String,
    val aliases: List<String> = emptyList(),
    /** Null means the target is available regardless of Steam/Desktop mode. */
    val mode: String? = null,
) {
    internal fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return false
        return sequenceOf(title, description, category.title).plus(aliases.asSequence())
            .any { it.lowercase().contains(q) }
    }
}

/** Search data points into the existing settings rows and specialist pages; it stores no values. */
object SettingsCatalog {
    val entries: List<SettingsEntry> = listOf(
        SettingsEntry("theme", SettingsCategory.GENERAL, "Theme", "Choose the launcher color theme", listOf("appearance", "colors")),
        SettingsEntry("home-screen", SettingsCategory.GENERAL, "Home screen", "Enable DroidDeck as an Android home app", listOf("launcher", "default launcher")),
        SettingsEntry("default-home", SettingsCategory.GENERAL, "Default home app", "Choose DroidDeck as the Android home app", listOf("home app", "launcher app")),
        SettingsEntry("launcher-fullscreen", SettingsCategory.GENERAL, "Fullscreen", "Show the launcher without Android system bars", listOf("immersive")),
        SettingsEntry("launcher-animations", SettingsCategory.GENERAL, "Animations", "Enable or disable launcher motion", listOf("motion")),
        SettingsEntry("store-enabled", SettingsCategory.GENERAL, "Linux app store", "Show the Flathub Store in the launcher", listOf("store", "flathub")),
        SettingsEntry("res", SettingsCategory.DISPLAY, "Resolution", "Set the next session resolution", listOf("720p", "900p", "1080p", "native"), "steam"),
        SettingsEntry("shape", SettingsCategory.DISPLAY, "Aspect ratio", "Choose how the session fits the screen", listOf("shape", "ratio"), "steam"),
        SettingsEntry("fps", SettingsCategory.DISPLAY, "Frame-rate limit", "Limit the session frame rate", listOf("fps", "refresh rate"), "steam"),
        SettingsEntry("upscaler", SettingsCategory.DISPLAY, "Upscaling", "Choose the session image scaling filter", listOf("sharpness", "scaling"), "steam"),
        SettingsEntry("upscale-sharpness", SettingsCategory.DISPLAY, "Upscaling sharpness", "Adjust image sharpening", listOf("sharpening"), "steam"),
        SettingsEntry("hdr", SettingsCategory.DISPLAY, "HDR", "Enable HDR output when supported", listOf("high dynamic range"), "steam"),
        SettingsEntry("fill", SettingsCategory.DISPLAY, "Fill screen", "Stretch the session to fill the display", listOf("force fullscreen"), "steam"),
        SettingsEntry("stretch169", SettingsCategory.DISPLAY, "Stretch to 16:9", "Stretch the session image to a 16:9 shape", listOf("widescreen stretch"), "steam"),
        SettingsEntry("gpu-drivers", SettingsCategory.COMPATIBILITY, "GPU drivers", "Manage Android and Linux display drivers", listOf("turnip", "adreno", "vulkan"), "steam"),
        SettingsEntry("fg", SettingsCategory.DISPLAY, "Frame generation", "Choose a frame-generation mode or import Lossless Scaling", listOf("lossless scaling", "lsfg")),
        SettingsEntry("effects-look", SettingsCategory.DISPLAY, "Screen effects preset", "Choose a preset for image effects", listOf("look", "post-processing")),
        SettingsEntry("effects-cas", SettingsCategory.DISPLAY, "Contrast adaptive sharpening", "Enable contrast adaptive sharpening", listOf("cas", "sharpness")),
        SettingsEntry("effects-cas-level", SettingsCategory.DISPLAY, "Sharpening strength", "Adjust contrast adaptive sharpening", listOf("cas level")),
        SettingsEntry("effects-fake-hdr", SettingsCategory.DISPLAY, "Fake HDR", "Add an HDR-style image effect", listOf("hdr effect")),
        SettingsEntry("effects-deband", SettingsCategory.DISPLAY, "Debanding", "Reduce color banding in gradients", listOf("banding")),
        SettingsEntry("effects-deband-strength", SettingsCategory.DISPLAY, "Debanding strength", "Adjust color banding reduction", listOf("banding strength")),
        SettingsEntry("effects-brightness", SettingsCategory.DISPLAY, "Brightness", "Adjust the session image brightness", listOf("light")),
        SettingsEntry("effects-contrast", SettingsCategory.DISPLAY, "Contrast", "Adjust the session image contrast", listOf("image contrast")),
        SettingsEntry("effects-gamma", SettingsCategory.DISPLAY, "Gamma", "Adjust the session image gamma", listOf("midtones")),
        SettingsEntry("effects-saturation", SettingsCategory.DISPLAY, "Saturation", "Adjust the session image saturation", listOf("color intensity")),
        SettingsEntry("effects-fxaa", SettingsCategory.DISPLAY, "FXAA", "Enable fast approximate anti-aliasing", listOf("anti-aliasing")),
        SettingsEntry("effects-toon", SettingsCategory.DISPLAY, "Toon effect", "Enable the toon image effect", listOf("cartoon")),
        SettingsEntry("effects-crt", SettingsCategory.DISPLAY, "CRT effect", "Enable the CRT image effect", listOf("scanlines")),
        SettingsEntry("effects-ntsc", SettingsCategory.DISPLAY, "NTSC effect", "Enable the NTSC image effect", listOf("composite video")),
        SettingsEntry("textures-anisotropy", SettingsCategory.DISPLAY, "Texture anisotropy", "Choose texture filtering anisotropy", listOf("anisotropic filtering")),
        SettingsEntry("textures-sharpness", SettingsCategory.DISPLAY, "Texture sharpness", "Adjust texture level-of-detail bias", listOf("lod bias", "texture filtering")),
        SettingsEntry("touch", SettingsCategory.CONTROLS, "Touch mode", "Choose how touch input behaves in a session", listOf("touchpad", "direct touch"), "steam"),
        SettingsEntry("controller-osc", SettingsCategory.CONTROLS, "On-screen controls", "Choose when the touch controls appear", listOf("overlay", "controller overlay"), "steam"),
        SettingsEntry("controller", SettingsCategory.CONTROLS, "Controller type", "Choose the controller profile exposed to Steam", listOf("xbox", "steam deck"), "steam"),
        SettingsEntry("back-actions", SettingsCategory.CONTROLS, "Back-button actions", "Choose the order of the Steam menu and Quick Access Menu", listOf("b button", "menu button", "qam")),
        SettingsEntry("controller-mapping", SettingsCategory.CONTROLS, "Button mapping", "Remap controller buttons", listOf("mapping", "remap")),
        SettingsEntry("controller-tint", SettingsCategory.CONTROLS, "Controller color", "Choose the on-screen controller color", listOf("tint")),
        SettingsEntry("controller-layout", SettingsCategory.CONTROLS, "Controller layout", "Edit or reset the touch controller layout", listOf("custom layout")),
        SettingsEntry("controller-reset", SettingsCategory.CONTROLS, "Reset controller settings", "Reset the on-screen controller configuration", listOf("reset all")),
        SettingsEntry("controller-opacity", SettingsCategory.CONTROLS, "Controller opacity", "Adjust the on-screen controller opacity", listOf("transparency")),
        SettingsEntry("controller-size", SettingsCategory.CONTROLS, "Controller size", "Adjust the on-screen controller size", listOf("scale")),
        SettingsEntry("controller-stick-click", SettingsCategory.CONTROLS, "Stick click", "Enable stick-click controls", listOf("l3", "r3")),
        SettingsEntry("controller-adaptive", SettingsCategory.CONTROLS, "Adaptive sticks", "Adjust stick behavior to the active game", listOf("stick sensitivity")),
        SettingsEntry("controller-rumble", SettingsCategory.CONTROLS, "Controller rumble", "Enable controller vibration", listOf("vibration")),
        SettingsEntry("controller-steam", SettingsCategory.CONTROLS, "Steam button", "Show the Steam button on the virtual controller", listOf("steam menu")),
        SettingsEntry("controller-qam", SettingsCategory.CONTROLS, "Quick Access button", "Show the Quick Access button on the virtual controller", listOf("qam")),
        SettingsEntry("controller-keyboard", SettingsCategory.CONTROLS, "On-screen keyboard button", "Show the keyboard button on the virtual controller", listOf("keyboard")),
        SettingsEntry("controller-actions", SettingsCategory.CONTROLS, "Controller layout and mapping", "Edit the on-screen layout, remap buttons, or reset controller settings", listOf("color", "opacity", "size", "stick click", "adaptive sticks", "rumble", "steam button", "qam button", "keyboard button", "layout", "mapping", "reset")),
        SettingsEntry("da", SettingsCategory.AUDIO, "Direct audio", "Configure direct game audio", listOf("audio output"), "steam"),
        SettingsEntry("clientAudio", SettingsCategory.AUDIO, "Steam-menu audio", "Choose the audio mode for Steam menus", listOf("client audio", "steam client audio"), "steam"),
        SettingsEntry("mic", SettingsCategory.AUDIO, "Microphone", "Allow session microphone input", listOf("recording", "voice chat"), "steam"),
        SettingsEntry("added-games", SettingsCategory.LIBRARY, "Added games", "Manage imported game folders and launch files", listOf("non-steam games", "exe"), "steam"),
        SettingsEntry("addedArt", SettingsCategory.LIBRARY, "Added game artwork", "Show artwork for imported games", listOf("game covers", "covers"), "steam"),
        SettingsEntry("storage", SettingsCategory.LIBRARY, "Game storage", "Choose where Steam stores games", listOf("second library", "sd card"), "steam"),
        SettingsEntry("storage-location", SettingsCategory.LIBRARY, "Second library", "Import or remove a second Steam library", listOf("library location", "sd card folder"), "steam"),
        SettingsEntry("added-games-add", SettingsCategory.LIBRARY, "Add a game folder", "Import another folder of games", listOf("add games directory", "import folder"), "steam"),
        SettingsEntry("roms", SettingsCategory.LIBRARY, "ROM folders", "Choose the folder scanned for ROMs", listOf("emulator games")),
        SettingsEntry("files", SettingsCategory.LIBRARY, "File manager", "Browse files in the Linux runtime", listOf("file browser")),
        SettingsEntry("suspend", SettingsCategory.SESSIONS, "Suspend behavior", "Choose how sessions respond to suspend", listOf("sleep", "suspend policy"), "steam"),
        SettingsEntry("offline", SettingsCategory.SESSIONS, "Offline mode", "Use the signed-in account in offline mode", listOf("offline account")),
        SettingsEntry("pip-auto", SettingsCategory.SESSIONS, "Picture-in-picture", "Automatically enter picture-in-picture", listOf("pip"), "steam"),
        SettingsEntry("steam-startup", SettingsCategory.SESSIONS, "Start Steam on launch", "Start Steam automatically with DroidDeck", listOf("autostart"), "steam"),
        SettingsEntry("steamdeck", SettingsCategory.SESSIONS, "Steam Deck mode", "Use the Steam Deck client interface", listOf("big picture", "deck mode"), "steam"),
        SettingsEntry("mangoapp", SettingsCategory.DISPLAY, "Performance overlay", "Show the MangoHud performance overlay", listOf("mangohud", "mangoapp"), "steam"),
        SettingsEntry("channel", SettingsCategory.SESSIONS, "Steam branch", "Choose the Steam client update branch", listOf("beta", "client channel"), "steam"),
        SettingsEntry("wifi", SettingsCategory.SESSIONS, "Wi-Fi discovery", "Enable local network discovery for Steam", listOf("network", "remote play"), "steam"),
        SettingsEntry("renderer", SettingsCategory.SESSIONS, "Desktop renderer", "Choose the Linux desktop renderer", listOf("desktop graphics"), "desktop"),
        SettingsEntry("decky", SettingsCategory.SESSIONS, "Decky Loader", "Install and manage Decky Loader", listOf("decky loader", "plugin loader"), "steam"),
        SettingsEntry("decky-enabled", SettingsCategory.SESSIONS, "Enable Decky Loader", "Start or stop Decky Loader with Steam", listOf("decky toggle"), "steam"),
        SettingsEntry("decky-loader", SettingsCategory.SESSIONS, "Decky installation", "Install or repair the Decky loader", listOf("decky install", "decky status"), "steam"),
        SettingsEntry("decky-plugins", SettingsCategory.SESSIONS, "Decky plugins", "Install a Decky plugin ZIP", listOf("plugin zip"), "steam"),
        SettingsEntry("components", SettingsCategory.COMPATIBILITY, "Compatibility components", "Manage FEX, DXVK, and VKD3D-Proton", listOf("dxvk", "vkd3d", "fex")),
        SettingsEntry("default-proton", SettingsCategory.COMPATIBILITY, "Default Proton", "Choose the synchronized Steam default", listOf("default-proton")),
        SettingsEntry("editing-proton", SettingsCategory.COMPATIBILITY, "Editing components for", "Choose which Proton build to edit", listOf("component editing target")),
        SettingsEntry("protons", SettingsCategory.COMPATIBILITY, "Proton versions", "Install and manage Proton builds", listOf("wine", "proton-ge", "installed proton")),
        SettingsEntry("performance", SettingsCategory.COMPATIBILITY, "Performance", "Tune CPU cores and graphics compatibility options", listOf("cpu", "cores", "zink", "fsync")),
        SettingsEntry("performance:override", SettingsCategory.COMPATIBILITY, "Per-app core limits", "Override the Steam client CPU core selection", listOf("client core override", "cpu affinity")),
        SettingsEntry("performance:clientCores", SettingsCategory.COMPATIBILITY, "Steam client CPU cores", "Choose CPU cores available to the Steam client", listOf("steam cores", "client affinity")),
        SettingsEntry("performance:gameCores", SettingsCategory.COMPATIBILITY, "Game CPU cores", "Choose CPU cores available to games", listOf("game affinity")),
        SettingsEntry("performance:glthread", SettingsCategory.COMPATIBILITY, "GL threading", "Change GL threading behavior", listOf("gl thread")),
        SettingsEntry("performance:zink", SettingsCategory.COMPATIBILITY, "Zink", "Change Zink lazy pipeline behavior", listOf("lazy pipeline")),
        SettingsEntry("performance:noglerror", SettingsCategory.COMPATIBILITY, "Ignore GL errors", "Change OpenGL error handling", listOf("opengl errors")),
        SettingsEntry("performance:gsrealtime", SettingsCategory.COMPATIBILITY, "Gamescope real-time priority", "Set Gamescope scheduling priority", listOf("realtime scheduling")),
        SettingsEntry("performance:gpuclock", SettingsCategory.COMPATIBILITY, "Pin GPU clock", "Set the GPU clock policy", listOf("gpu clock")),
        SettingsEntry("performance:sysmem", SettingsCategory.COMPATIBILITY, "Turnip system memory", "Change Turnip system memory behavior", listOf("tu sysmem")),
        SettingsEntry("performance:xalia", SettingsCategory.COMPATIBILITY, "Xalia", "Change Xalia input behavior", listOf("no xalia")),
        SettingsEntry("performance:syncfallback", SettingsCategory.COMPATIBILITY, "Esync fallback", "Change synchronization fallback behavior", listOf("esync")),
        SettingsEntry("performance:fsyncfirst", SettingsCategory.COMPATIBILITY, "Fsync first", "Try fsync before other synchronization backends", listOf("fsync")),
        SettingsEntry("performance:fastsync", SettingsCategory.COMPATIBILITY, "Fast sync", "Enable fast synchronization", listOf("wine sync")),
        SettingsEntry("performance:seccomp", SettingsCategory.COMPATIBILITY, "Proot seccomp", "Change the proot seccomp setting", listOf("syscall filtering")),
        SettingsEntry("performance:fastpath", SettingsCategory.COMPATIBILITY, "Proot fast path", "Enable the proot fast path", listOf("fast path")),
        SettingsEntry("performance:hostname", SettingsCategory.COMPATIBILITY, "Guest hostname", "Set the Linux guest hostname", listOf("computer name")),
        SettingsEntry("fex", SettingsCategory.COMPATIBILITY, "FEX preset", "Choose CPU translation settings for games", listOf("translation", "x86"), "steam"),
        SettingsEntry("syncBackend", SettingsCategory.COMPATIBILITY, "Synchronization backend", "Choose the game synchronization backend", listOf("ntsync", "fsync", "esync"), "steam"),
        SettingsEntry("game-env", SettingsCategory.COMPATIBILITY, "Game environment", "Edit per-game environment options", listOf("environment variables", "launch options"), "steam"),
        SettingsEntry("storageDiagnostics", SettingsCategory.SUPPORT, "Storage diagnostics", "Collect diagnostics for game storage", listOf("storage logs"), "steam"),
        SettingsEntry("runtime", SettingsCategory.SUPPORT, "Linux runtime", "Install, update, or manage the Linux runtime", listOf("runtime install", "container")),
        SettingsEntry("logs", SettingsCategory.SUPPORT, "Session logs", "Enable, share, or clear session logs", listOf("diagnostics", "debug logs")),
        SettingsEntry("updates", SettingsCategory.SUPPORT, "DroidDeck updates", "View build information and release channel", listOf("version", "build")),
        SettingsEntry("phantom-process", SettingsCategory.SUPPORT, "Child-process limit", "Diagnose Android's process limit that can block Steam", listOf("phantom process", "developer options", "wireless debugging", "adb")),
    )

    fun search(query: String): List<SettingsEntry> = entries.filter { it.matches(query) }
}

private data class CategoryCard(val category: SettingsCategory, val icon: ImageVector)

@Composable
fun SettingsHub(s: FrontEndState, a: FrontEndActions) {
    var query by rememberSaveable { mutableStateOf("") }
    val frontFocus = LocalFrontFocus.current
    var hasFocus by remember { mutableStateOf(false) }
    var lastItem by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(frontFocus) {
        snapshotFlow { if (hasFocus) frontFocus?.last else null }.filterNotNull().collect { lastItem = it }
    }
    LaunchedEffect(Unit) {
        if (frontFocus != null) focusWithinFrames({ hasFocus }) {
            val id = lastItem
            if (id != null && (frontFocus.attached[id] ?: 0) > 0) frontFocus.items.getValue(id)
            else frontFocus.primary
        }
    }
    DisposableEffect(frontFocus) {
        frontFocus?.let { it.primaryAttached++ }
        onDispose { frontFocus?.let { it.primaryAttached-- } }
    }
    val cards = listOf(
        CategoryCard(SettingsCategory.GENERAL, Icons.Outlined.Home),
        CategoryCard(SettingsCategory.DISPLAY, Icons.Outlined.DisplaySettings),
        CategoryCard(SettingsCategory.CONTROLS, Icons.Outlined.Gamepad),
        CategoryCard(SettingsCategory.AUDIO, Icons.Outlined.GraphicEq),
        CategoryCard(SettingsCategory.LIBRARY, Icons.Outlined.FolderOpen),
        CategoryCard(SettingsCategory.SESSIONS, Icons.Outlined.Settings),
        CategoryCard(SettingsCategory.COMPATIBILITY, Icons.Outlined.Speed),
        CategoryCard(SettingsCategory.SUPPORT, Icons.Outlined.HelpOutline),
    )
    Column(
        modifier = Modifier.fillMaxSize().onFocusChanged { hasFocus = it.hasFocus }.focusGroup()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeader("Settings")
        HubSearchField(query = query, onQuery = { query = it }, frontFocus = frontFocus)
        if (!s.ready || s.removalPending || PhantomProcessLimit.blocksSteam(s.phantomProcessStatus)) {
            val (title, detail, target) = when {
                s.removalPending -> Triple("Runtime needs attention", "Runtime removal needs to be completed before using the session.", "runtime")
                !s.ready -> Triple("Linux runtime is not ready", "Install or repair the runtime to launch Steam.", "runtime")
                else -> Triple("Android process limit may block Steam", "Review the child-process setting and available fixes.", "phantom-process")
            }
            SettingsAnchor(target) { ActionRow(title, detail, "Review") { a.onSettingsCategory(SettingsCategory.SUPPORT, target) } }
        }
        if (query.isBlank()) {
            val columns = if (LocalNarrowPane.current) 1 else 2
            cards.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
                    row.forEach { card ->
                        SettingsCategoryCard(card, Modifier.weight(1f).paneItem("settings-category:${card.category.name}"), onClick = { a.onSettingsCategory(card.category, null) })
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            val results = SettingsCatalog.search(query)
            if (results.isEmpty()) {
                Note("No settings found for “${query.trim()}”.")
            } else {
                results.forEach { entry ->
                    SettingsSearchResult(entry, Modifier.paneItem("settings-search:${entry.target}")) { a.onSettingsCategory(entry.category, entry.target) }
                }
            }
        }
    }
}

@Composable
private fun HubSearchField(query: String, onQuery: (String) -> Unit, frontFocus: FrontFocus?) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().clip(shape).background(colors.surface)
            .border(1.dp, pal.line2, shape).padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        val onQueryLatest = rememberUpdatedState(onQuery)
        AndroidView(
            modifier = Modifier.weight(1f).height(30.dp)
                .then(if (frontFocus == null) Modifier else Modifier.focusRequester(frontFocus.primary)),
            factory = { context ->
                EditText(context).apply {
                    isSingleLine = true
                    background = null
                    setPadding(0, 0, 0, 0)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                    hint = "Search settings"
                    contentDescription = "Search settings"
                    // Keep the hub visible while typing on landscape handhelds.
                    imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    setOnFocusChangeListener { _, focused ->
                        if (focused) frontFocus?.last = FrontFocus.PRIMARY
                    }
                    setOnEditorActionListener { _, action, _ ->
                        if (action == EditorInfo.IME_ACTION_SEARCH) {
                            (context.getSystemService(InputMethodManager::class.java)).hideSoftInputFromWindow(windowToken, 0)
                            clearFocus()
                            true
                        } else false
                    }
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            onQueryLatest.value(s?.toString().orEmpty())
                        }
                        override fun afterTextChanged(s: Editable?) = Unit
                    })
                }
            },
            update = { field ->
                field.setTextColor(colors.onBackground.toArgb())
                field.setHintTextColor(colors.onSurfaceVariant.toArgb())
                if (field.text.toString() != query) {
                    field.setText(query)
                    field.setSelection(query.length)
                }
            },
        )
        if (query.isNotEmpty()) Text(
            "Clear", color = pal.signal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { onQuery("") }.padding(4.dp),
        )
    }
}

@Composable
private fun SettingsCategoryCard(card: CategoryCard, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val focused = source.collectIsFocusedAsState().value
    val hovered = source.collectIsHoveredAsState().value
    val shape = RoundedCornerShape(14.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxSize().clip(shape)
            .background(if (focused || hovered) pal.signal.copy(alpha = 0.10f) else colors.surface)
            .glideBorder(focused, shape, pal.signal, pal.line)
            .hoverable(source)
            .clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(14.dp),
    ) {
        Icon(card.icon, contentDescription = null, tint = if (focused || hovered) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Text(card.category.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingsSearchResult(entry: SettingsEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val focused = source.collectIsFocusedAsState().value
    val hovered = source.collectIsHoveredAsState().value
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().clip(shape)
            .background(if (focused || hovered) pal.signal.copy(alpha = 0.10f) else colors.surface)
            .glideBorder(focused, shape, pal.signal, pal.line)
            .hoverable(source)
            .clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
        }
        Spacer(Modifier.width(12.dp))
        Text(entry.category.title, fontSize = 12.sp, color = pal.signal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
