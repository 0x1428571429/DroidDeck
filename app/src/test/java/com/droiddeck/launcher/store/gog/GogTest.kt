package com.droiddeck.launcher.store.gog

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GogTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun game(): File = tmp.newFolder("Example Game").apply {
        File(this, "Bin").mkdirs()
        File(this, "Bin/Game.EXE").writeText("x")
        File(this, "Launcher.exe").writeText("x")
        File(this, "Data").mkdirs()
    }

    private fun info(vararg tasks: String) =
        JSONObject("""{"gameId":"42","rootGameId":"42","name":"Example: The Game","playTasks":[${tasks.joinToString(",")}]}""")

    @Test fun primaryGameTaskWinsWithWindowsPathsMatchedCaselessly() {
        val dir = game()
        val parsed = GogGameInfo.parse(dir, info(
            """{"type":"FileTask","category":"launcher","isPrimary":true,"path":"Launcher.exe"}""",
            """{"type":"URLTask","category":"document","link":"https://example.com"}""",
            """{"type":"FileTask","category":"game","isPrimary":true,"path":"bin\\game.exe","workingDir":"data","arguments":"-windowed -lang \"en\""}""",
        ))!!
        assertEquals("42", parsed.id)
        assertEquals("Example: The Game", parsed.name)
        assertEquals(File(dir, "Bin/Game.EXE"), parsed.task!!.exe)
        assertEquals(File(dir, "Data"), parsed.task!!.workingDir)
        assertEquals("-windowed -lang \"en\"", parsed.task!!.arguments)
    }

    @Test fun noWorkingDirStartsInTheGameFolder() {
        val dir = game()
        val parsed = GogGameInfo.parse(dir, info("""{"type":"FileTask","category":"game","path":"Bin\\Game.exe"}"""))!!
        assertEquals(dir, parsed.task!!.workingDir)
        assertEquals("", parsed.task!!.arguments)
    }

    @Test fun missingExeMeansNoTask() {
        val dir = game()
        assertNull(GogGameInfo.parse(dir, info("""{"type":"FileTask","category":"game","isPrimary":true,"path":"Missing.exe"}"""))!!.task)
    }

    @Test fun readPicksTheRootGameOverDlcInfoFiles() {
        val dir = game()
        File(dir, "goggame-7.info").writeText("""{"gameId":"7","rootGameId":"42","name":"DLC","playTasks":[]}""")
        File(dir, "goggame-42.info").writeText(info("""{"type":"FileTask","category":"game","isPrimary":true,"path":"Bin\\Game.exe"}""").toString())
        assertEquals("42", GogGameInfo.read(dir)!!.id)
    }

    @Test fun incompleteUntilTheDownloadFinishes() {
        val dir = game()
        File(dir, GogManager.MARKER).writeText("""{"id":"42"}""")
        assertTrue(GogManager.incomplete(dir))
        File(dir, "goggame-42.info").writeText(info("""{"type":"FileTask","category":"game","isPrimary":true,"path":"Bin\\Game.exe"}""").toString())
        File(dir, ".gogdl-resume").writeText("")
        assertTrue(GogManager.incomplete(dir))
        File(dir, ".gogdl-resume").delete()
        assertEquals(false, GogManager.incomplete(dir))
    }

    @Test fun libraryKeepsWindowsGamesAndPageCount() {
        val (games, pages) = GogApi.parseLibraryPage("""{"totalPages":3,"products":[
            {"id":1207658924,"title":"Unreal Tournament","image":"//images-1.gog-statics.com/abc","worksOn":{"Windows":true,"Mac":false,"Linux":false}},
            {"id":2,"title":"Mac Only","image":"//images-1.gog-statics.com/def","worksOn":{"Windows":false,"Mac":true}}
        ]}""")!!
        assertEquals(3, pages)
        assertEquals(listOf("1207658924"), games.map { it.id })
        assertEquals("https://images-1.gog-statics.com/abc_392.jpg", games[0].image)
    }

    @Test fun artFillsGamesDbUrlTemplates() {
        val art = GogApi.parseArt("""{"game":{"vertical_cover":{"url_format":"https://images.gog.com/v{formatter}.{ext}"},
            "logo":{"url_format":"https://images.gog.com/l{formatter}.{ext}"}}}""")!!
        assertEquals("https://images.gog.com/v.jpg", art.cover)
        assertEquals("https://images.gog.com/l.png", art.logo)
        assertNull(art.hero)
    }
}
