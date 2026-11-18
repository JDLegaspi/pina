package dev.shulhi.rex

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AgentSessionsTest {
    private val now=AgentFixtures.now
    private val mac=AgentFixtures.mac

    @Test fun statusSectionsFollowTheMacSidebarOrderAndSkipPanesWithoutStatus() {
        val records=listOf(
            AgentFixtures.record("atlas","atlas","a1","idle",1),
            AgentFixtures.record("mobile","mobile-app","m1","error",2,"exit 1"),
            AgentFixtures.record("docs","Docs site","d1","done",3),
            AgentFixtures.record("docs","Docs site","d2","working",4),
            AgentFixtures.record("checkout","checkout-redesign","c1","blocked",5,"approve plan","permission"))
        val sections=AgentSessions.statusSections(AgentSessions.panes(mac,AgentFixtures.panes,records))
        assertEquals(listOf("Blocked","Working","Error","Done","Idle"),sections.map {it.title})
        assertEquals(5,sections.sumOf {it.panes.size})
        assertTrue(sections.flatMap {it.panes}.none {it.target.block=="n1"})
    }

    @Test fun rowsInASectionShowTheMostRecentUpdateFirst() {
        val done=AgentSessions.statusSections(AgentFixtures.agentPanes).single {it.status==AgentStatus.DONE}
        assertEquals(listOf("q1","d1","d2","s1","s2","m1"),done.panes.map {it.target.block})
    }

    @Test fun blockedRowsCarryKindTitleMessageAndAge() {
        val blocked=AgentFixtures.agentPanes.single {it.status==AgentStatus.BLOCKED}
        assertEquals("Waiting for permission",blocked.blockedKind.title)
        assertEquals("approve plan",blocked.message)
        assertEquals("29m",AgentSessions.relativeAge(blocked.updatedAt!!,now))
        assertEquals("Address form validation",blocked.title)
        assertEquals("~/code/worktrees/checkout-redesign",blocked.directory)
        assertEquals(listOf("Waiting for your answer","Waiting for sign-in","Waiting for input","Waiting for input"),
            listOf("question","auth","","future").map {BlockedKind.of(it).title})
    }

    @Test fun theRootRecordDescribesThePaneButABlockedChildStillBlocksIt() {
        val root=AgentFixtures.record("s","S","b","working",1,"root task")
        val child=AgentFixtures.record("s","S","b","done",0,"child",id="build")
        assertEquals("root task",AgentSessions.representative(listOf(child,root))!!.message)
        val blockedChild=AgentFixtures.record("s","S","b","blocked",2,"approve",id="build")
        assertEquals(ActivityState.BLOCKED,AgentSessions.representative(listOf(root,blockedChild))!!.state)
        val blockedRoot=AgentFixtures.record("s","S","b","blocked",9,"root approval","permission")
        assertEquals("root approval",AgentSessions.representative(listOf(blockedChild,blockedRoot))!!.message)
        assertEquals(ActivityState.DONE,AgentSessions.representative(listOf(AgentFixtures.record("s","S","b","done",0),AgentFixtures.record("s","S","b","error",5,id="x")))!!.state)
        assertEquals(ActivityState.ERROR,AgentSessions.representative(listOf(AgentFixtures.record("s","S","b","done",0,id="y"),AgentFixtures.record("s","S","b","error",5,id="x")))!!.state)
        assertNull(AgentSessions.representative(emptyList()))
    }

    @Test fun mostUrgentRecordWinsInBlockedErrorWorkingDoneIdleOrder() {
        val states=listOf("idle","done","working","error","blocked")
        for(count in 1..states.size) {
            val records=states.take(count).map {AgentFixtures.record("s","S","b",it,1,id="task-$it")}
            assertEquals(states[count-1],AgentSessions.representative(records.shuffled(java.util.Random(count.toLong())))!!.state.wire)
        }
    }

    @Test fun folderSectionsUseServerSessionOrderAndPaneOrder() {
        val sections=AgentSessions.folderSections(AgentFixtures.agentPanes.shuffled(java.util.Random(4)),listOf(mac))
        assertEquals(listOf("atlas","mobile-app","Docs site","notes","search-api","checkout-redesign","quiet-meadow"),sections.map {it.title})
        assertEquals(listOf("Search index rebuild","Relay plugin auth","vite"),sections[2].panes.map {it.title})
        assertEquals(listOf("zsh"),sections[0].panes.map {it.title})
        assertEquals(null,sections[0].panes.single().status)
        assertEquals(11,sections.sumOf {it.panes.size})
    }

    @Test fun secondServerPrefixesFolderHeaders() {
        val other=ServerProfile.create("Studio","https://studio.example","studio")
        val panes=AgentFixtures.agentPanes.take(1)+AgentFixtures.agentPanes.first().copy(target=PaneTarget(other,"x","y"),sessionLabel="lab")
        assertEquals(listOf("Mac · atlas","Studio · lab"),AgentSessions.folderSections(panes,listOf(mac,other)).map {it.title})
    }

    @Test fun relativeTimesMatchTheSidebar() {
        assertEquals("now",AgentSessions.relativeAge(now.minusSeconds(59),now))
        assertEquals("now",AgentSessions.relativeAge(now.plusSeconds(30),now))
        assertEquals("2m",AgentSessions.relativeAge(now.minusSeconds(150),now))
        assertEquals("59m",AgentSessions.relativeAge(now.minusSeconds(3599),now))
        assertEquals("4h",AgentSessions.relativeAge(now.minusSeconds(4*3600+59),now))
        assertEquals("3d",AgentSessions.relativeAge(now.minusSeconds(3*86400+5),now))
    }

    @Test fun directoriesAbbreviateTheServersHome() {
        assertEquals("~",AgentSessions.abbreviateDirectory("/Users/dev","/Users/dev/"))
        assertEquals("~/code/notes",AgentSessions.abbreviateDirectory("/Users/dev/code/notes","/Users/dev"))
        assertEquals("/Users/devon/x",AgentSessions.abbreviateDirectory("/Users/devon/x","/Users/dev"))
        assertEquals("~/src",AgentSessions.abbreviateDirectory("/home/dev/src",""))
        assertEquals("~/My Files",AgentSessions.abbreviateDirectory("file://mac.local/Users/dev/My%20Files","/Users/dev"))
        assertEquals("/opt/work",AgentSessions.abbreviateDirectory("/opt/work",""))
    }

    @Test fun serverTimestampsWithOffsetsParse() {
        assertEquals(Instant.parse("2026-11-18T03:40:58.596896Z"),AgentSessions.parseInstant("2026-11-18T13:40:58.596896+10:00"))
        assertNull(AgentSessions.parseInstant(""))
        assertNull(AgentSessions.parseInstant("yesterday"))
    }

    @Test fun scanPanesParseFromTheNativeShape() {
        val pane=PaneInfo.parse(JSONObject("""{"session":"s","sessionLabel":"quiet-meadow","sessionOrder":6,"window":"w","windowLabel":"","block":"b",
            "blockLabel":"shell","order":10,"programs":["claude-code","claude","/Users/dev/.local/bin/claude"],"foreground":"2.1.327",
            "title":"Android harness for Rex","cwd":"/Users/dev/code/pina","pwd":"","home":"/Users/dev"}"""))!!
        assertEquals(ProgramKind.CLAUDE,pane.program.kind)
        assertEquals("~/code/pina",pane.directory)
        assertEquals(6,pane.sessionOrder)
        val shell=PaneInfo.parse(JSONObject().put("session","s").put("block","z").put("programs",JSONArray(listOf("-zsh","/bin/zsh"))).put("pwd","file:///Users/dev/a").put("home","/Users/dev"))!!
        assertEquals("zsh",AgentSessions.displayTitle(shell.program,"shell",""))
        val claude=ProgramIdentity.resolve(listOf("claude-code","claude"))
        assertEquals("Android harness for Rex",AgentSessions.displayTitle(claude,"shell","Android harness for Rex"))
        assertEquals("Reviewer",AgentSessions.displayTitle(claude,"Reviewer","Android harness for Rex"))
        assertEquals("Claude Code",AgentSessions.displayTitle(claude,"shell",""))
        assertEquals("~/a",shell.directory)
        assertNull(PaneInfo.parse(JSONObject().put("session","s")))
    }

    @Test fun recordsWithoutAScannedPaneStillAppear() {
        val orphan=AgentFixtures.record("gone","old-session","x","done",1)
        val panes=AgentSessions.panes(mac,emptyList(),listOf(orphan))
        assertEquals(listOf("old-session"),panes.map {it.sessionLabel})
        assertEquals(AgentStatus.DONE,panes.single().status)
    }
}
