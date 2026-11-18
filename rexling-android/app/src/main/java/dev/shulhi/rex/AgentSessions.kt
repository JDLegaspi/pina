package dev.shulhi.rex

import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** Declaration order is the status view's section order. */
internal enum class AgentStatus(val title: String,val urgency: Int) {
    BLOCKED("Blocked",0),WORKING("Working",2),ERROR("Error",1),DONE("Done",3),IDLE("Idle",4);
    fun tint(colors: PinaColors): Color = when(this) {
        BLOCKED->colors.orange;WORKING->colors.tint;ERROR->colors.red;DONE->colors.green;IDLE->colors.gray
    }
    companion object {
        fun of(state: ActivityState)=when(state) {
            ActivityState.BLOCKED->BLOCKED;ActivityState.WORKING->WORKING;ActivityState.ERROR->ERROR;ActivityState.DONE->DONE;ActivityState.IDLE->IDLE
        }
    }
}

internal enum class BlockedKind(val wire: String,val title: String) {
    PERMISSION("permission","Waiting for permission"),QUESTION("question","Waiting for your answer"),
    AUTH("auth","Waiting for sign-in"),NONE("","Waiting for input");
    companion object {fun of(kind: String)=entries.firstOrNull {it.wire.isNotEmpty() && it.wire==kind} ?: NONE}
}

internal enum class SessionsLayout(val label: String) {STATUS("Status"),FOLDERS("Folders")}

internal data class PaneTarget(val server: ServerProfile,val session: String,val block: String)

/** One terminal pane as the Rust activity scan reports it, with or without program status. */
data class PaneInfo(val session: String,val sessionLabel: String,val sessionOrder: Int,val window: String,val windowLabel: String,
    val block: String,val blockLabel: String,val order: Int,val program: ProgramIdentity,val title: String,val directory: String?) {
    companion object {
        private fun JSONObject.clean(name: String,max: Int)=optString(name).filterNot {it.isISOControl() || Character.getType(it)==Character.FORMAT.toInt()}.take(max)
        fun parse(value: JSONObject): PaneInfo? {
            val session=value.clean("session",256);val block=value.clean("block",256)
            if(session.isBlank() || block.isBlank()) return null
            val programs=value.optJSONArray("programs") ?: JSONArray()
            val candidates=(0 until programs.length()).map {programs.optString(it).take(512)}.filter {it.isNotBlank()}
            val directory=value.clean("cwd",1024).ifBlank {value.clean("pwd",1024)}
            return PaneInfo(session,value.clean("sessionLabel",256),value.optInt("sessionOrder",Int.MAX_VALUE),value.clean("window",256),value.clean("windowLabel",256),
                block,value.clean("blockLabel",256),value.optInt("order",Int.MAX_VALUE),
                if(candidates.isEmpty()) ProgramIdentity.Terminal else ProgramIdentity.resolve(candidates),value.clean("title",256),
                directory.takeIf {it.isNotBlank()}?.let {AgentSessions.abbreviateDirectory(it,value.clean("home",1024))})
        }
    }
}

internal data class AgentPane(val target: PaneTarget,val sessionLabel: String,val sessionOrder: Int,val order: Int,val windowLabel: String,
    val program: ProgramIdentity,val title: String,val directory: String?,val status: AgentStatus?,val blockedKind: BlockedKind=BlockedKind.NONE,
    val message: String="",val progress: Int?=null,val updatedAt: Instant?=null) {
    val key: String get()=listOf(target.server.id,target.session,target.block).joinToString("") {"${it.length}:$it"}
}

internal data class AgentSection(val key: String,val title: String,val status: AgentStatus?,val panes: List<AgentPane>)

internal object AgentSessions {
    private val agentKinds=setOf(ProgramKind.CLAUDE,ProgramKind.CODEX)

    fun displayTitle(program: ProgramIdentity,blockLabel: String,title: String): String {
        val named=program.paneTitle(blockLabel,title)
        return if(program.kind in agentKinds && named==program.title) program.label else named
    }

    /** Root reports describe the pane; a blocked child still blocks it. */
    fun representative(records: List<ActivityRecord>): ActivityRecord? {
        val roots=records.filter {it.id.isEmpty() || it.progressOnly}
        val pool=(roots.ifEmpty {records}+records.filter {it.state==ActivityState.BLOCKED}).distinctBy {it.key}
        return pool.minWithOrNull(compareBy<ActivityRecord> {AgentStatus.of(it.state).urgency}
            .thenBy {if(it.id.isEmpty()) 0 else if(it.progressOnly) 2 else 1}.thenByDescending {it.timestamp})
    }

    fun panes(server: ServerProfile,panes: List<PaneInfo>,records: List<ActivityRecord>): List<AgentPane> {
        val byPane=records.groupBy {it.session to it.block}
        val known=panes.distinctBy {it.session to it.block}
        val listed=known.map {pane->agentPane(server,pane,byPane[pane.session to pane.block].orEmpty())}
        val orphans=byPane.filterKeys {key->known.none {it.session to it.block==key}}.values.mapNotNull {group->
            val record=representative(group) ?: return@mapNotNull null
            agentPane(server,PaneInfo(record.session,record.sessionLabel,Int.MAX_VALUE,record.window,record.windowLabel,record.block,record.blockLabel,Int.MAX_VALUE,record.program,record.title,null),group)
        }
        return listed+orphans
    }

    private fun agentPane(server: ServerProfile,pane: PaneInfo,records: List<ActivityRecord>): AgentPane {
        val record=representative(records)
        val program=if(pane.program==ProgramIdentity.Terminal && record!=null) record.program else pane.program
        return AgentPane(PaneTarget(server,pane.session,pane.block),pane.sessionLabel.ifBlank {record?.sessionLabel.orEmpty()},pane.sessionOrder,pane.order,
            pane.windowLabel,program,displayTitle(program,pane.blockLabel,pane.title),pane.directory,record?.let {AgentStatus.of(it.state)},
            if(record?.state==ActivityState.BLOCKED) BlockedKind.of(record.kind) else BlockedKind.NONE,record?.message.orEmpty(),record?.progress,record?.updated?.let(::parseInstant))
    }

    fun statusSections(panes: List<AgentPane>): List<AgentSection> = AgentStatus.entries.mapNotNull {status->
        panes.filter {it.status==status}.sortedWith(compareByDescending<AgentPane> {it.updatedAt ?: Instant.EPOCH}.thenBy {it.sessionOrder}.thenBy {it.order})
            .takeIf {it.isNotEmpty()}?.let {AgentSection("status:${status.name}",status.title,status,it)}
    }

    fun folderSections(panes: List<AgentPane>,servers: List<ServerProfile>): List<AgentSection> {
        val multiServer=panes.map {it.target.server.id}.distinct().size>1
        val serverOrder=servers.withIndex().associate {it.value.id to it.index}
        return panes.groupBy {it.target.server to it.target.session}.entries
            .sortedWith(compareBy<Map.Entry<Pair<ServerProfile,String>,List<AgentPane>>> {serverOrder[it.key.first.id] ?: Int.MAX_VALUE}
                .thenBy {group->group.value.minOf {it.sessionOrder}}.thenBy {it.value.first().sessionLabel})
            .map {(key,group)->
                val label=group.first().sessionLabel.ifBlank {"Session"}
                AgentSection("folder:${key.first.id}:${key.second}",if(multiServer) "${key.first.label} · $label" else label,null,group.sortedBy {it.order})
            }
    }

    fun relativeAge(then: Instant,now: Instant): String {
        val seconds=Duration.between(then,now).seconds.coerceAtLeast(0)
        return when {seconds<60->"now";seconds<3600->"${seconds/60}m";seconds<86400->"${seconds/3600}h";else->"${seconds/86400}d"}
    }

    fun abbreviateDirectory(path: String,home: String): String {
        val absolute=if(path.startsWith("file://")) runCatching {URI(path).path}.getOrNull() ?: path else path
        val root=home.trimEnd('/')
        if(root.isNotEmpty()) {
            if(absolute==root) return "~"
            if(absolute.startsWith("$root/")) return "~/"+absolute.substring(root.length+1)
        }
        if(root.isNotEmpty()) return absolute
        val guessed=Regex("^/(?:Users|home)/[^/]+").find(absolute) ?: return absolute
        return "~"+absolute.substring(guessed.range.last+1)
    }

    fun parseInstant(value: String): Instant? = if(value.isBlank()) null else
        runCatching {Instant.parse(value)}.recoverCatching {OffsetDateTime.parse(value).toInstant()}.getOrNull()
}
