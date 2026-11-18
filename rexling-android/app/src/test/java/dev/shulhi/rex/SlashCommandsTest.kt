package dev.shulhi.rex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal object SlashFixtures {
    val probe: List<SlashCommand> by lazy {
        val text=SlashFixtures::class.java.classLoader!!.getResource("skills-probe.json")!!.readText()
        SlashCommand.parseAll(JSONObject(text).getJSONArray("commands"))
    }
}

class SlashCommandsTest {
    private fun command(name: String,description: String="",source: SlashSource=SlashSource.USER,plugin: String?=null)=SlashCommand(name,description,source,plugin)

    @Test fun parsesTheProbeOutput() {
        val commands=SlashFixtures.probe
        assertEquals(39,commands.size)
        assertEquals(22,commands.count {it.source==SlashSource.USER})
        assertEquals(setOf("commit-commands","figma","superpowers"),commands.filter {it.source==SlashSource.PLUGIN}.mapNotNull {it.plugin}.toSet())
    }

    @Test fun rejectsNamesThatCannotBeTyped() {
        assertNull(SlashCommand.parse(JSONObject().put("name","two words").put("source","user")))
        assertNull(SlashCommand.parse(JSONObject().put("name","ok").put("source","bogus")))
        assertEquals("review",SlashCommand.parse(JSONObject().put("name","/review").put("source","project"))!!.name)
    }

    @Test fun sectionsRunProjectPersonalPluginsThenBuiltIns() {
        val sections=SlashCommands.sections(listOf(
            command("zeta"),command("deploy",source=SlashSource.PROJECT),
            command("superpowers:brainstorming",source=SlashSource.PLUGIN,plugin="superpowers"),
            command("figma:figma-use",source=SlashSource.PLUGIN,plugin="figma"),command("alpha")))
        assertEquals(listOf("Project","Personal","figma","superpowers","Commands"),sections.map {it.title})
        assertEquals(listOf("alpha","zeta"),sections[1].commands.map {it.name})
        assertEquals(SlashCommands.builtIns.size,sections.last().commands.size)
    }

    @Test fun builtInsCoverTheClaudeCodeCommandsWithDescriptions() {
        val names=SlashCommands.builtIns.map {it.name}
        listOf("add-dir","clear","compact","context","model","resume","review","rewind","usage","vim").forEach {assertTrue(it,it in names)}
        assertTrue(SlashCommands.builtIns.all {it.description.isNotBlank() && it.source==SlashSource.BUILT_IN})
    }

    @Test fun rankingPrefersPrefixThenWordStartThenNameThenDescription() {
        val prefix=command("brainstorm")
        val wordStart=command("setup-brain")
        val inside=command("superbrainy")
        val described=command("notes",description="Keeps a second brain")
        val unrelated=command("deploy",description="Ship it")
        val ranked=SlashCommands.search(listOf(described,inside,unrelated,wordStart,prefix),"brain")
        assertEquals(listOf("brainstorm","setup-brain","superbrainy","notes"),ranked.map {it.name})
    }

    @Test fun pluginNamespacesDoNotHideAPrefixMatch() {
        assertEquals(0,SlashCommands.matchRank(command("superpowers:brainstorming",source=SlashSource.PLUGIN),"brain"))
        assertEquals(0,SlashCommands.matchRank(command("review"),"/REV"))
    }

    @Test fun probeBrainSearchPutsBrainstormingFirst() {
        val ranked=SlashCommands.search(SlashFixtures.probe,"brain").map {it.name}
        assertEquals("superpowers:brainstorming",ranked.first())
        assertTrue(ranked.containsAll(listOf("setup-gbrain","sync-gbrain")))
    }

    @Test fun emptyQueryKeepsEverything() {
        assertEquals(SlashCommands.builtIns.size+2,SlashCommands.search(listOf(command("a"),command("b")),"  ").size)
    }

    @Test fun insertionLeavesRoomForArguments() {
        assertEquals("/superpowers:brainstorming ",SlashCommands.insertion(command("superpowers:brainstorming")))
    }
}

class PromptLineTest {
    private fun after(vararg inputs: String,from: PromptState=PromptState.EMPTY)=
        inputs.fold(from) {state,input->PromptLine.reduce(state,PromptEvent.Keys(input))}

    @Test fun printableInputFillsThePrompt() {
        assertEquals(PromptState.NON_EMPTY,after("f"))
        assertEquals(PromptState.NON_EMPTY,after("fix the bug"))
    }

    @Test fun submitInterruptAndClearEmptyIt() {
        listOf("\r","\u0003","\u0015","\u001b").forEach {assertEquals(it,PromptState.EMPTY,after("draft",it))}
        assertEquals(PromptState.EMPTY,after("draft\r"))
    }

    @Test fun editingKeysMakeItUnknown() {
        assertEquals(PromptState.UNKNOWN,after("draft","\u007f"))
        assertEquals(PromptState.UNKNOWN,after("\u001b[A"))
        assertEquals(PromptState.UNKNOWN,after("draft","\u001b[D"))
        assertEquals(PromptState.UNKNOWN,after("\u001bOP"))
        assertEquals(PromptState.UNKNOWN,after("\t"))
    }

    @Test fun backspaceOnAnEmptyPromptLeavesItEmpty() {
        assertEquals(PromptState.EMPTY,after("\u007f"))
    }

    @Test fun shiftEnterAddsALineInsteadOfSubmitting() {
        assertEquals(PromptState.NON_EMPTY,after(TerminalInput.SHIFT_ENTER))
    }

    @Test fun pasteAndPickerInsertionFillThePromptUntilSubmitted() {
        val inserted=PromptLine.reduce(PromptState.EMPTY,PromptEvent.Paste)
        assertEquals(PromptState.NON_EMPTY,inserted)
        assertEquals(PromptState.EMPTY,after("\r",from=inserted))
    }

    @Test fun onlyAnEmptyClaudePromptOpensThePicker() {
        assertTrue(PromptLine.opensPicker("/",ProgramKind.CLAUDE,PromptState.EMPTY))
        listOf(PromptState.NON_EMPTY,PromptState.UNKNOWN).forEach {assertEquals(false,PromptLine.opensPicker("/",ProgramKind.CLAUDE,it))}
        assertEquals(false,PromptLine.opensPicker("/",ProgramKind.TERMINAL,PromptState.EMPTY))
        assertEquals(false,PromptLine.opensPicker("/",null,PromptState.EMPTY))
        assertEquals(false,PromptLine.opensPicker("/x",ProgramKind.CLAUDE,PromptState.EMPTY))
    }
}

class UploadNamesTest {
    private val png=byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10,0,0,0,0)
    private val text="hello world!".toByteArray()

    @Test fun keepsTheRealNameOfAnyFile() {
        assertEquals("design review.pdf",UploadNames.of("design review.pdf",text))
        assertEquals("notes.txt",UploadNames.of("Download/notes.txt",text))
    }

    @Test fun stripsControlCharactersAndPathTricks() {
        assertEquals("evil.sh",UploadNames.of("../../evil.sh",text))
        assertEquals("ab.txt",UploadNames.of("a\u0000‮b.txt",text))
        assertEquals("file",UploadNames.of("..",text))
        assertEquals("file",UploadNames.of(null,text))
    }

    @Test fun imagesWithoutUsableNamesGetOneFromTheirBytes() {
        assertEquals("image.png",UploadNames.of(null,png))
        assertEquals("1000012345.png",UploadNames.of("1000012345",png))
        assertEquals("shot.jpeg",UploadNames.of("shot.jpeg",png))
    }

    @Test fun longNamesKeepTheirExtension() {
        val name=UploadNames.of("x".repeat(300)+".pdf",text)
        assertEquals(120,name.length)
        assertTrue(name.endsWith(".pdf"))
    }
}
