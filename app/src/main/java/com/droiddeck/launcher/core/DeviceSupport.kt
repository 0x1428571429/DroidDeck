package com.droiddeck.launcher.core

import android.os.Build
import java.io.File

/**
 * Whether the bundled Linux runtime's Vulkan renderer is likely to work.
 *
 * The runtime currently ships the Freedreno Vulkan ICD, which targets Adreno. The Android
 * compositor uses a separate driver and can use a non-Adreno system Vulkan implementation. This
 * check gates the guest-driver warning. Adreno is recognised by what only Qualcomm's stack has:
 * the KGSL node, or the vendor's own Vulkan driver at its usual path.
 */
object DeviceSupport {
    fun adreno(): Boolean =
        File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()

    /** The chip as the device names it, for the card that explains the refusal. */
    fun gpuName(): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null
        return soc?.let { "$it (${Build.HARDWARE})" } ?: Build.HARDWARE.ifBlank { "this GPU" }
    }
}
