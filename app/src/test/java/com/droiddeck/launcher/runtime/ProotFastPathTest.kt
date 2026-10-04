package com.droiddeck.launcher.runtime

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProotFastPathTest {
    private val root = File("/data/user/0/com.droiddeck.launcher/files/linuxfs")

    private fun binds(n: Int) = (0 until n).map { "/data/user/0/com.droiddeck.launcher/files/steam-libraries/x/steamapps/common/tool$it:/mnt/droiddeck-sd/steamapps/common/tool$it" }

    @Test fun aSessionWithASecondLibraryAndGamesFoldersKeepsTheFastPath() {
        assertNotNull(ProotFastPath.key(root, binds(96)))
        assertNotNull(ProotFastPath.key(root, binds(ProotFastPath.MAX_BINDS)))
    }

    @Test fun moreBindsThanTheLibraryHoldsTurnTheFastPathOff() {
        assertNull(ProotFastPath.key(root, binds(ProotFastPath.MAX_BINDS + 1)))
    }

    @Test fun aPathTooLongForTheLibraryTurnsTheFastPathOff() {
        val long = "/storage/emulated/0/" + "d".repeat(ProotFastPath.MAX_BIND_PATH)
        assertNull(ProotFastPath.key(root, binds(3) + "$long:/root/Games/Games"))
        assertNull(ProotFastPath.key(root, binds(3) + "/storage/emulated/0/Games:/root/Games/$long"))
        assertNotNull(ProotFastPath.key(root, binds(3) + "/storage/emulated/0/Games:/root/Games/Games"))
    }
}
