package dev.shulhi.rex

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.app.NotificationManager
import org.json.JSONObject
import java.util.UUID

internal enum class RecordChange {UNCHANGED,BASELINE,NEW,CHANGED}
internal enum class AlertSurface {BACKGROUND,HOME,OTHER_PANE,SAME_PANE}
internal enum class AlertDelivery {NONE,BANNER,NOTIFICATION}
internal data class AttentionAlert(val record: ActivityRecord,val delivery: AlertDelivery)

/**
 * | change              | state                | background   | home | other pane | same pane |
 * | unchanged, or seen  | any                  | none         | none | none       | none      |
 * | any                 | working, idle        | none         | none | none       | none      |
 * | baseline            | blocked, error       | notification | none | none       | none      |
 * | baseline            | done                 | none         | none | none       | none      |
 * | new, changed        | blocked, error, done | notification | none | banner     | none      |
 */
internal object AlertPolicy {
    fun decide(change: RecordChange,record: ActivityRecord,surface: AlertSurface,seen: Boolean): AlertDelivery = when {
        change==RecordChange.UNCHANGED || seen || !record.notifiable->AlertDelivery.NONE
        surface==AlertSurface.BACKGROUND->if(change==RecordChange.BASELINE && record.state==ActivityState.DONE) AlertDelivery.NONE else AlertDelivery.NOTIFICATION
        surface==AlertSurface.OTHER_PANE && change!=RecordChange.BASELINE->AlertDelivery.BANNER
        else->AlertDelivery.NONE
    }
}

internal object AlertCopy {
    private val agentKinds=setOf(ProgramKind.CLAUDE,ProgramKind.CODEX)
    fun program(record: ActivityRecord)=if(record.program.kind in agentKinds) AgentSessions.displayTitle(record.program,record.blockLabel,record.title)
        else record.label.ifBlank {record.program.label}
    fun session(record: ActivityRecord)=record.sessionLabel.ifBlank {record.session}
    fun phrase(record: ActivityRecord)=when(record.state) {
        ActivityState.BLOCKED->BlockedKind.of(record.kind).title;ActivityState.DONE->"Finished";ActivityState.ERROR->"Error"
        ActivityState.WORKING->"Working";ActivityState.IDLE->"Idle"
    }
    fun title(record: ActivityRecord)="${program(record)} · ${session(record)}"
    fun text(record: ActivityRecord)=listOf(phrase(record),record.message).filter {it.isNotBlank()}.joinToString(": ")
    fun banner(record: ActivityRecord)="${session(record)} · ${phrase(record)}"
}

/** Consume foreground updates and baseline old completions without replaying them. */
internal class AttentionLedger(saved: String?=null) {
    private val stamps=linkedMapOf<String,String>()
    private val baselinedServers=mutableSetOf<String>()
    private val observedThisRun=mutableSetOf<String>()
    init {runCatching {JSONObject(saved ?: "{}").let {o->o.keys().forEach {key->stamps[key]=o.getString(key)}}}}
    fun key(server: ServerProfile,record: ActivityRecord)=listOf(server.id,server.endpoint,record.key).joinToString("") {"${it.length}:$it"}
    fun observe(server: ServerProfile,records: List<ActivityRecord>,background: Boolean,complete: Boolean=true,seen: (ActivityRecord)->Boolean): List<ActivityRecord> =
        deliveries(server,records,{if(background) AlertSurface.BACKGROUND else AlertSurface.HOME},complete,seen).filter {it.delivery==AlertDelivery.NOTIFICATION}.map {it.record}
    fun deliveries(server: ServerProfile,records: List<ActivityRecord>,surface: (ActivityRecord)->AlertSurface,complete: Boolean=true,seen: (ActivityRecord)->Boolean): List<AttentionAlert> {
        val alerts=mutableListOf<AttentionAlert>()
        val source=listOf(server.id,server.endpoint).joinToString("") {"${it.length}:$it"}
        records.forEach {record->
            val version=record.attentionVersion.ifBlank {listOf(record.state.wire,record.kind,record.updated,record.message).joinToString("\u0000")}
            val stamp=when {record.state==ActivityState.DONE->"done:$version";record.notifiable->"attention:$version";else->"state:${record.state.wire}"}
            val key=key(server,record)
            val previous=stamps[key]
            if(previous!=stamp) {
                stamps.remove(key);stamps[key]=stamp
                val change=when {source !in baselinedServers && key !in observedThisRun->RecordChange.BASELINE;previous==null->RecordChange.NEW;else->RecordChange.CHANGED}
                val delivery=AlertPolicy.decide(change,record,surface(record),record.notifiable && seen(record))
                if(delivery!=AlertDelivery.NONE) alerts+=AttentionAlert(record,delivery)
            }
            observedThisRun+=key
        }
        if(complete) baselinedServers+=source
        while(stamps.size>2048) {val oldest=stamps.keys.first();stamps.remove(oldest);observedThisRun.remove(oldest)}
        return alerts
    }
    fun encode()=JSONObject(stamps.toMap()).toString()
}

internal data class AlertDestination(val serverId: String,val endpoint: String,val session: String,val block: String,val attentionKey: String,val attentionVersion: String) {
    fun json()=JSONObject().put("server",serverId).put("endpoint",endpoint).put("session",session).put("block",block).put("key",attentionKey).put("version",attentionVersion)
    companion object {
        fun parse(o: JSONObject)=AlertDestination(o.getString("server"),o.getString("endpoint"),o.getString("session"),o.getString("block"),o.optString("key"),o.optString("version"))
    }
}

/** An exported launcher accepts only opaque, one-use tokens issued by this app. */
internal class AlertDestinations(private val preferences: SharedPreferences) {
    private fun entries()=runCatching {JSONObject(preferences.getString("alert_destinations","{}")!!)}.getOrDefault(JSONObject())
    fun issue(target: AlertDestination): Uri {
        val entries=entries();while(entries.length()>=128) entries.remove(entries.keys().next())
        val token=UUID.randomUUID().toString();entries.put(token,target.json())
        preferences.edit().putString("alert_destinations",entries.toString()).apply()
        return Uri.Builder().scheme("pina-alert").authority("open").appendPath(token).build()
    }
    fun consume(uri: Uri?): AlertDestination? {
        if(uri?.scheme!="pina-alert" || uri.authority!="open" || uri.pathSegments.size!=1) return null
        val entries=entries();val target=entries.optJSONObject(uri.lastPathSegment ?: return null) ?: return null
        entries.remove(uri.lastPathSegment);preferences.edit().putString("alert_destinations",entries.toString()).apply()
        return runCatching {AlertDestination.parse(target)}.getOrNull()
    }
}

internal object BackgroundAlerts {
    const val ENABLED="background_alerts"
    const val MONITOR_CHANNEL="background_monitor"
    const val ATTENTION_CHANNEL="attention"
    val foreground=kotlinx.coroutines.flow.MutableStateFlow(false)
    fun preferences(context: Context)=context.getSharedPreferences("rex",0)
    fun allowed(context: Context): Boolean {
        val manager=context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && (manager.getNotificationChannel(ATTENTION_CHANNEL)?.importance ?: 1)!=NotificationManager.IMPORTANCE_NONE
    }
    fun servers(preferences: SharedPreferences): List<ServerProfile> {
        val desired=preferences.getStringSet("connected_servers",emptySet()).orEmpty()
        return SavedServers.decode(preferences.getString("servers",null),preferences.getString("endpoint","").orEmpty()).filter {it.id in desired}
    }
    /** Called only from visible UI, never from a receiver or a background restart. */
    const val PROMPTED="notification_prompted"
    fun reconcile(context: Context) {
        val prefs=preferences(context)
        if(prefs.getBoolean(ENABLED,false) && allowed(context) && servers(prefs).isNotEmpty()) {
            context.startForegroundService(Intent(context,BackgroundMonitorService::class.java))
        } else context.stopService(Intent(context,BackgroundMonitorService::class.java))
    }
}
