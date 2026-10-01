package com.droiddeck.launcher.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZramSupportTest {
    @Test fun kernelVersions() {
        assertTrue(ZramSupport.kernelAtLeast("6.12.38-android16-5-g665eafb62659-ab14778838-4k", 5, 4))
        assertTrue(ZramSupport.kernelAtLeast("5.4.0", 5, 4))
        assertTrue(ZramSupport.kernelAtLeast("5.10.198-android12", 5, 4))
        assertFalse(ZramSupport.kernelAtLeast("4.19.157-perf", 5, 4))
        assertFalse(ZramSupport.kernelAtLeast("5.3.18", 5, 4))
        assertFalse(ZramSupport.kernelAtLeast("", 5, 4))
        assertFalse(ZramSupport.kernelAtLeast("unknown", 5, 4))
    }

    @Test fun supportNeedsSwapAndKernel() {
        assertTrue(ZramSupport.Status(12582908, 10000000, true).supported)
        assertFalse(ZramSupport.Status(0, 0, true).supported)
        assertFalse(ZramSupport.Status(12582908, 10000000, false).supported)
    }
}
