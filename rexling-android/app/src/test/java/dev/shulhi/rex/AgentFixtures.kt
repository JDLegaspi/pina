package dev.shulhi.rex

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal object AgentFixtures {
    val now: Instant = Instant.parse("2026-11-18T04:00:00Z")
    val mac = ServerProfile.create("Mac","https://mac.example","mac")
    private const val HOME = "/Users/dev"
    private var order = 0

    fun pane(session: String,label: String,sessionOrder: Int,block: String,programs: List<String>,cwd: String,title: String="")=PaneInfo.parse(JSONObject()
        .put("session",session).put("sessionLabel",label).put("sessionOrder",sessionOrder).put("window","w-$session").put("windowLabel","")
        .put("block",block).put("blockLabel","shell").put("order",order++).put("programs",JSONArray(programs)).put("title",title)
        .put("cwd",cwd).put("home",HOME))!!

    fun record(session: String,label: String,block: String,state: String,minutesAgo: Long,message: String="",kind: String="",id: String="")=ActivityRecord.parse(JSONObject()
        .put("session",session).put("sessionLabel",label).put("block",block).put("id",id).put("state",state).put("kind",kind)
        .put("message",message).put("app","claude-code").put("owner","claude").put("updatedAt",now.minusSeconds(minutesAgo*60).toString())
        .put("attentionKey","[\"$session\",\"$block\",\"$id\"]").put("attentionVersion","v$minutesAgo"))!!

    private val claude = listOf("claude-code","claude")
    private val zsh = listOf("-zsh","/bin/zsh","zsh")
    private val node = listOf("node")

    val panes: List<PaneInfo> by lazy {
        order = 0
        listOf(
            pane("atlas","atlas",0,"a1",zsh,"$HOME/code/atlas"),
            pane("mobile","mobile-app",1,"m1",node,"$HOME/code/mobile-app","react-native"),
            pane("docs","Docs site",2,"d1",claude,"$HOME/code/docs-site","Search index rebuild"),
            pane("docs","Docs site",2,"d2",claude,"$HOME/code/docs-site/plugins/relay","Relay plugin auth"),
            pane("docs","Docs site",2,"d3",node,"$HOME/code/docs-site/web","vite"),
            pane("notes","notes",3,"n1",zsh,"$HOME/code/notes"),
            pane("search","search-api",4,"s1",claude,"$HOME/code/worktrees/atlas/search-api","Paginate search results"),
            pane("search","search-api",4,"s2",claude,"$HOME/code/worktrees/atlas/search-api"),
            pane("checkout","checkout-redesign",5,"c1",claude,"$HOME/code/worktrees/checkout-redesign","Address form validation"),
            pane("checkout","checkout-redesign",5,"c2",zsh,"$HOME/code/worktrees/checkout-redesign"),
            pane("meadow","quiet-meadow",6,"q1",claude,"$HOME/code/pina","Android harness for Rex"),
        )
    }

    val records: List<ActivityRecord> by lazy {
        listOf(
            record("checkout","checkout-redesign","c1","blocked",29,"approve plan","permission"),
            record("meadow","quiet-meadow","q1","done",2),
            record("docs","Docs site","d1","done",8),
            record("docs","Docs site","d2","done",10),
            record("search","search-api","s1","done",4*60),
            record("search","search-api","s2","done",4*60+12),
            record("mobile","mobile-app","m1","done",3*24*60),
        )
    }

    val agentPanes: List<AgentPane> by lazy {AgentSessions.panes(mac,panes,records)}
}
