package dev.shulhi.rex

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Declaration order is the picker's section order. */
internal enum class SlashSource(val wire: String,val title: String) {
    PROJECT("project","Project"),USER("user","Personal"),PLUGIN("plugin","Plugins"),BUILT_IN("built_in","Commands");
    companion object {fun parse(value: String)=entries.firstOrNull {it.wire==value}}
}

internal data class SlashCommand(val name: String,val description: String,val source: SlashSource,val plugin: String?=null) {
    fun toJson(): JSONObject=JSONObject().put("name",name).put("description",description).put("source",source.wire).put("plugin",plugin ?: JSONObject.NULL)
    companion object {
        fun parse(value: JSONObject): SlashCommand? {
            val name=value.optString("name").trim().removePrefix("/")
            val source=SlashSource.parse(value.optString("source")) ?: return null
            if(name.isEmpty() || name.any {it.isWhitespace() || it.isISOControl()}) return null
            val plugin=if(value.isNull("plugin")) null else value.optString("plugin").ifBlank {null}
            return SlashCommand(name,value.optString("description").filterNot {it.isISOControl()}.trim(),source,plugin)
        }
        fun parseAll(array: JSONArray?): List<SlashCommand> =
            if(array==null) emptyList() else (0 until array.length()).mapNotNull {array.optJSONObject(it)?.let(::parse)}
    }
}

internal data class SlashSection(val key: String,val title: String,val commands: List<SlashCommand>)

internal object SlashCommands {
    val builtIns: List<SlashCommand> = listOf(
        "add-dir" to "Add a working directory",
        "agents" to "Manage custom subagents",
        "clear" to "Clear the conversation history",
        "compact" to "Summarize the conversation to free up context",
        "config" to "Open settings",
        "context" to "Show how the context window is used",
        "cost" to "Show token usage and cost for this session",
        "doctor" to "Check the health of your Claude Code install",
        "exit" to "Exit Claude Code",
        "export" to "Export the conversation to a file or the clipboard",
        "help" to "Show help and available commands",
        "hooks" to "Manage hooks for tool events",
        "init" to "Create a CLAUDE.md guide for this project",
        "login" to "Sign in to your Anthropic account",
        "logout" to "Sign out of your Anthropic account",
        "mcp" to "Manage MCP server connections",
        "memory" to "Edit CLAUDE.md memory files",
        "model" to "Choose the AI model",
        "permissions" to "View or change tool permissions",
        "plan" to "Switch to plan mode",
        "pr-comments" to "Show comments on a GitHub pull request",
        "release-notes" to "Show the release notes",
        "resume" to "Resume an earlier conversation",
        "review" to "Review a pull request",
        "rewind" to "Rewind the conversation or code to an earlier point",
        "security-review" to "Review pending changes for security issues",
        "status" to "Show version, model, account and connection status",
        "statusline" to "Set up the status line",
        "terminal-setup" to "Set up Shift+Enter for newlines",
        "todos" to "List the current todo items",
        "usage" to "Show plan usage limits",
        "vim" to "Toggle Vim editing mode",
    ).map {(name,description)->SlashCommand(name,description,SlashSource.BUILT_IN)}

    fun sections(discovered: List<SlashCommand>): List<SlashSection> {
        val all=builtIns+discovered.filter {it.source!=SlashSource.BUILT_IN}
        val plugins=all.filter {it.source==SlashSource.PLUGIN}.groupBy {it.plugin ?: it.name.substringBefore(':',"Plugins")}
            .toSortedMap(String.CASE_INSENSITIVE_ORDER).map {(plugin,commands)->
                SlashSection("plugin:$plugin",plugin,commands.sortedBy {it.name.lowercase(Locale.ROOT)})
            }
        return SlashSource.entries.flatMap {source->
            if(source==SlashSource.PLUGIN) plugins
            else listOfNotNull(all.filter {it.source==source}.sortedBy {it.name.lowercase(Locale.ROOT)}.takeIf {it.isNotEmpty()}
                ?.let {SlashSection(source.wire,source.title,it)})
        }
    }

    /** Lower is better; null drops the command. */
    fun matchRank(command: SlashCommand,query: String): Int? {
        val needle=query.trim().removePrefix("/").lowercase(Locale.ROOT)
        if(needle.isEmpty()) return 0
        val name=command.name.lowercase(Locale.ROOT)
        return when {
            name.startsWith(needle) || name.substringAfter(':').startsWith(needle) -> 0
            name.split(':','-','_','.','/').any {it.startsWith(needle)} -> 1
            name.contains(needle) -> 2
            command.description.lowercase(Locale.ROOT).contains(needle) -> 3
            else -> null
        }
    }

    fun search(discovered: List<SlashCommand>,query: String): List<SlashCommand> =
        (builtIns+discovered.filter {it.source!=SlashSource.BUILT_IN}).mapNotNull {command->matchRank(command,query)?.let {command to it}}
            .sortedWith(compareBy<Pair<SlashCommand,Int>> {it.second}.thenBy {it.first.name.length}.thenBy {it.first.name.lowercase(Locale.ROOT)})
            .map {it.first}

    fun insertion(command: SlashCommand)="/${command.name} "
}

internal enum class PromptState { EMPTY, NON_EMPTY, UNKNOWN }

internal sealed interface PromptEvent {
    data class Keys(val input: String): PromptEvent
    data object Paste: PromptEvent
}

/** Pina's belief about a Claude Code prompt, from the input it sent. Input from other clients is invisible. */
internal object PromptLine {
    private const val ESC='\u001b'
    private const val SHIFT_ENTER=TerminalInput.SHIFT_ENTER

    fun reduce(state: PromptState,event: PromptEvent): PromptState = when(event) {
        PromptEvent.Paste->PromptState.NON_EMPTY
        is PromptEvent.Keys->keys(state,event.input)
    }

    private fun keys(initial: PromptState,input: String): PromptState {
        var state=initial
        var i=0
        while(i<input.length) {
            val c=input[i]
            when {
                input.startsWith(SHIFT_ENTER,i)->{state=PromptState.NON_EMPTY;i+=SHIFT_ENTER.length;continue}
                c==ESC && i+1==input.length->state=PromptState.EMPTY
                c==ESC && input[i+1]=='['->{
                    var end=i+2
                    while(end<input.length && input[end].code !in 0x40..0x7e) end++
                    state=PromptState.UNKNOWN;i=end+1;continue
                }
                c==ESC->{state=PromptState.UNKNOWN;i+=if(input[i+1]=='O') 3 else 2;continue}
                c=='\r' || c=='\n' || c=='\u0003' || c=='\u0015'->state=PromptState.EMPTY
                c=='\u007f' || c=='\b'->if(state!=PromptState.EMPTY) state=PromptState.UNKNOWN
                c<' '->state=PromptState.UNKNOWN
                else->state=PromptState.NON_EMPTY
            }
            i++
        }
        return state
    }

    /** A typed "/" opens the picker only where Claude Code would open its own menu. */
    fun opensPicker(input: String,program: ProgramKind?,state: PromptState)=
        input=="/" && program==ProgramKind.CLAUDE && state==PromptState.EMPTY
}
