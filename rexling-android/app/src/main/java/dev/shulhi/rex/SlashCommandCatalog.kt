package dev.shulhi.rex

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class SlashTarget(val server: String,val endpoint: String,val session: String,val block: String,val directory: String) {
    val cacheKey: String get()="$server\u0000$endpoint\u0000$directory"
}

internal sealed interface SlashRefresh {
    data object Updating: SlashRefresh
    data object Current: SlashRefresh
    data class Failed(val message: String): SlashRefresh
}

/** `discovered` is null until a cached or fresh list exists for the target's server and directory. */
internal data class SlashCatalogState(val target: SlashTarget,val discovered: List<SlashCommand>?,val refresh: SlashRefresh)

internal fun interface SkillsBackend {
    suspend fun discover(target: SlashTarget): List<SlashCommand>
}

internal class NativeSkills: SkillsBackend {
    override suspend fun discover(target: SlashTarget): List<SlashCommand> = withContext(Dispatchers.IO) {
        val request=JSONObject().put("endpoint",target.endpoint).put("session",target.session).put("block",target.block)
        val handle=NativeBridge.skillsCreate(request.toString())
        try {
            var commands: List<SlashCommand>?=null
            while(commands==null) {
                coroutineContext.ensureActive()
                val status=JSONObject(NativeBridge.skillsPoll(handle))
                when(status.optString("phase")) {
                    "done"->commands=SlashCommand.parseAll(status.optJSONArray("commands"))
                    "failed"->error(status.optString("error").ifBlank {"Could not read skills from the Mac"})
                    "canceled"->throw CancellationException()
                    else->delay(30)
                }
            }
            commands
        } finally {NativeBridge.skillsDestroy(handle)}
    }
}

/** Stale-while-revalidate: open shows the last list for this server and directory, then refreshes it. */
internal class SlashCommandCatalog(private val preferences: SharedPreferences,private val scope: CoroutineScope,
    private val backend: SkillsBackend=NativeSkills()) {
    var state by mutableStateOf<SlashCatalogState?>(null)
        private set
    private val memory=mutableMapOf<String,List<SlashCommand>>()
    private var job: Job?=null

    fun open(target: SlashTarget) {
        job?.cancel()
        state=SlashCatalogState(target,cached(target.cacheKey),SlashRefresh.Updating)
        job=scope.launch {
            val refresh=try {
                val commands=backend.discover(target)
                memory[target.cacheKey]=commands
                persist(target.cacheKey,commands)
                SlashRefresh.Current
            } catch(error: CancellationException) {throw error}
            catch(error: Exception) {SlashRefresh.Failed(error.message?.takeIf {it.isNotBlank()} ?: "Could not read skills from the Mac")}
            val current=state
            if(current?.target==target) state=current.copy(discovered=memory[target.cacheKey] ?: current.discovered,refresh=refresh)
        }
    }

    fun close() {job?.cancel();job=null;state=null}

    private fun cached(key: String): List<SlashCommand>? = memory[key] ?: preferences.getString(entryKey(key),null)?.let {raw->
        runCatching {SlashCommand.parseAll(JSONObject(raw).optJSONArray("commands"))}.getOrNull()
    }?.also {memory[key]=it}

    private fun persist(key: String,commands: List<SlashCommand>) {
        val entry=JSONObject().put("at",System.currentTimeMillis()).put("commands",JSONArray(commands.map {it.toJson()}))
        val stale=preferences.all.filterKeys {it.startsWith(PREFIX) && it!=entryKey(key)}.entries
            .sortedByDescending {runCatching {JSONObject(it.value as String).optLong("at")}.getOrDefault(0L)}
            .drop(MAX_ENTRIES-1).map {it.key}
        preferences.edit().apply {stale.forEach(::remove);putString(entryKey(key),entry.toString())}.apply()
    }

    private fun entryKey(key: String)=PREFIX+key

    companion object {
        const val PREFERENCES="slash_commands"
        private const val PREFIX="commands:"
        private const val MAX_ENTRIES=12
    }
}
