package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.gpu.ScreenEffectLooks
import com.droiddeck.launcher.gpu.ScreenEffects
import com.droiddeck.launcher.session.SessionPrefs
import java.util.Locale
import kotlin.math.roundToInt

/** Persistent graphics defaults shown in the launcher's settings page. */
@Composable
fun GraphicsSettingsContent(host: MenuHost, upscaler: Int, onUpscaler: (Int) -> Unit) {
    val context = LocalContext.current
    var effects by remember(context) { mutableStateOf(SessionPrefs.screenEffects(context)) }
    var anisotropy by remember(context) { mutableIntStateOf(SessionPrefs.textureAnisotropy(context)) }
    var lodBias by remember(context) { mutableStateOf(SessionPrefs.textureLodBias(context)) }

    SettingsAdvanced("graphics-effects", stringResource(R.string.drawer_effects), targets = EFFECT_SETTING_IDS) {
        ScreenEffectsSettings(
            host = host,
            effects = effects,
            upscaler = upscaler,
            onEffects = { value -> SessionPrefs.setScreenEffects(context, value); effects = value },
            onUpscaler = onUpscaler,
            grouped = false,
        )
    }
    SettingsAdvanced("graphics-texture", stringResource(R.string.drawer_texture), targets = TEXTURE_SETTING_IDS) {
        TextureFilteringSettings(
            host = host,
            anisotropy = anisotropy,
            lodBias = lodBias,
            onAnisotropy = { value -> SessionPrefs.setTextureAnisotropy(context, value); anisotropy = value },
            onLodBias = { value -> SessionPrefs.setTextureLodBias(context, value); lodBias = value },
            grouped = false,
        )
    }
}

/** Screen post-processing settings, shared by new sessions and applied live in the session drawer. */
@Composable
internal fun ScreenEffectsSettings(
    host: MenuHost,
    effects: ScreenEffects,
    upscaler: Int,
    onEffects: (ScreenEffects) -> Unit,
    onUpscaler: (Int) -> Unit,
    grouped: Boolean = true,
    track: (String) -> Modifier = { Modifier },
) {
    val look = ScreenEffectLooks.match(effects, upscaler)
    val rows: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        val looks = ScreenEffectLooks.LOOKS.map { it.name to it.name }
        ChoiceRow(host, "effects-look", stringResource(R.string.drawer_look),
            look?.desc ?: stringResource(R.string.drawer_effects_hint),
            if (look == null) listOf(LOOK_CUSTOM to stringResource(R.string.drawer_look_custom)) + looks else looks,
            look?.name ?: LOOK_CUSTOM, chipModifier = track("look"), hintLines = 2) { name ->
            ScreenEffectLooks.LOOKS.firstOrNull { it.name == name }?.let { picked ->
                picked.scalingMode?.let(onUpscaler)
                onEffects(picked.effects)
            }
        }
        ToggleRow(host, "effects-cas", stringResource(R.string.drawer_cas), null, effects.cas, chipModifier = track("cas")) { onEffects(effects.copy(cas = it)) }
        SettingsAnchor("effects-cas-level") {
            SliderRow(stringResource(R.string.drawer_cas_level), null, effects.casLevel, 0..100, step = 5, enabled = effects.cas,
                format = { "$it%" }, modifier = track("cas-level").settingsTarget("effects-cas-level")) { onEffects(effects.copy(casLevel = it)) }
        }
        ToggleRow(host, "effects-fake-hdr", stringResource(R.string.drawer_fake_hdr), null, effects.hdr, chipModifier = track("fake-hdr")) { onEffects(effects.copy(hdr = it)) }
        ToggleRow(host, "effects-deband", stringResource(R.string.drawer_deband), null, effects.deband, chipModifier = track("deband")) { onEffects(effects.copy(deband = it)) }
        SettingsAnchor("effects-deband-strength") {
            SliderRow(stringResource(R.string.drawer_deband_strength), null, effects.debandStrength, 0..200, step = 5, enabled = effects.deband,
                format = { "$it%" }, modifier = track("deband-strength").settingsTarget("effects-deband-strength")) { onEffects(effects.copy(debandStrength = it)) }
        }
        SettingsAnchor("effects-brightness") {
            SliderRow(stringResource(R.string.drawer_brightness), null, effects.brightness, -100..100, step = 2,
                format = ::signed, modifier = track("brightness").settingsTarget("effects-brightness")) { onEffects(effects.copy(brightness = it)) }
        }
        SettingsAnchor("effects-contrast") {
            SliderRow(stringResource(R.string.drawer_contrast), null, effects.contrast, -100..100, step = 2,
                format = ::signed, modifier = track("contrast").settingsTarget("effects-contrast")) { onEffects(effects.copy(contrast = it)) }
        }
        SettingsAnchor("effects-gamma") {
            SliderRow(stringResource(R.string.drawer_gamma), null, (effects.gamma * 100f).roundToInt(), 50..300, step = 5,
                format = { String.format(Locale.US, "%.2f", it / 100f) }, modifier = track("gamma").settingsTarget("effects-gamma")) { onEffects(effects.copy(gamma = it / 100f)) }
        }
        SettingsAnchor("effects-saturation") {
            SliderRow(stringResource(R.string.drawer_saturation), null, effects.saturation, 0..200, step = 5,
                format = { "$it%" }, modifier = track("saturation").settingsTarget("effects-saturation")) { onEffects(effects.copy(saturation = it)) }
        }
        ToggleRow(host, "effects-fxaa", stringResource(R.string.drawer_fxaa), null, effects.fxaa, chipModifier = track("fxaa")) { onEffects(effects.copy(fxaa = it)) }
        ToggleRow(host, "effects-toon", stringResource(R.string.drawer_toon), null, effects.toon, chipModifier = track("toon")) { onEffects(effects.copy(toon = it)) }
        ToggleRow(host, "effects-crt", stringResource(R.string.drawer_crt), null, effects.crt, chipModifier = track("crt")) { onEffects(effects.copy(crt = it)) }
        ToggleRow(host, "effects-ntsc", stringResource(R.string.drawer_ntsc), null, effects.ntsc, chipModifier = track("ntsc")) { onEffects(effects.copy(ntsc = it)) }
    }
    if (grouped) SettingsGroup(stringResource(R.string.drawer_effects), content = rows)
    else androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth(), content = rows)
}

/** DXVK filtering options saved as shared defaults and consumed by each game's next launch. */
@Composable
internal fun TextureFilteringSettings(
    host: MenuHost,
    anisotropy: Int,
    lodBias: String,
    onAnisotropy: (Int) -> Unit,
    onLodBias: (String) -> Unit,
    grouped: Boolean = true,
    track: (String) -> Modifier = { Modifier },
) {
    val rows: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        ChoiceRow(host, "textures-anisotropy", stringResource(R.string.drawer_anisotropy), stringResource(R.string.drawer_texture_hint),
            SessionPrefs.textureAnisotropyChoices, anisotropy, chipModifier = track("anisotropy"), onPick = onAnisotropy)
        ChoiceRow(host, "textures-sharpness", stringResource(R.string.drawer_texture_sharpness), stringResource(R.string.drawer_texture_hint),
            SessionPrefs.textureLodBiasChoices, lodBias, note = stringResource(R.string.drawer_texture_sharpness_note),
            chipModifier = track("texture-sharpness"), onPick = onLodBias)
    }
    if (grouped) SettingsGroup(stringResource(R.string.drawer_texture), content = rows)
    else androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth(), content = rows)
}

private const val LOOK_CUSTOM = "__custom__"

private val EFFECT_SETTING_IDS = setOf(
    "effects-look", "effects-cas", "effects-cas-level", "effects-fake-hdr", "effects-deband",
    "effects-deband-strength", "effects-brightness", "effects-contrast", "effects-gamma",
    "effects-saturation", "effects-fxaa", "effects-toon", "effects-crt", "effects-ntsc",
)

private val TEXTURE_SETTING_IDS = setOf("textures-anisotropy", "textures-sharpness")

private fun signed(value: Int) = if (value > 0) "+$value" else value.toString()
