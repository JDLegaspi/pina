package dev.shulhi.rex

import android.app.Application
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class RexModel @JvmOverloads constructor(application: Application, preferenceName: String="rex") : AndroidViewModel(application) {
    internal var activityVisible by mutableStateOf(false)
        private set
    internal var activitySources by mutableStateOf<List<ActivitySource>>(emptyList())
        private set
    internal fun showActivity(open: Boolean) {
        if(open) {activeClient.cancelKeyRepeats();refreshActivity()}
        activityVisible=open;updateActivity()
    }
    /** Activity stays open on every connection: the home list and banners always need it. */
    internal fun refreshActivity() {connections.values.forEach {it.activity(true)}}
    internal var attentionCount by mutableIntStateOf(0)
        private set
    internal var networkAvailable by mutableStateOf<Boolean?>(null)
        private set
    private var paneObscured=true
    internal fun setPaneObscured(value: Boolean) {paneObscured=value;updateActivity()}
    private fun watchedPane(): WatchedPane? {
        val client=connections[activeServerId] ?: return null
        val state=client.state.value
        val app=getApplication<Application>()
        return ActivityFocusPolicy.watched(activeServer,state,foreground && windowFocused,paneObscured || activityVisible,client.hasScreen && state.screen!=null,client.navigationRequest.pending,
            app.getSystemService(android.os.PowerManager::class.java).isInteractive,app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
    }
    private fun updateActivity() {
        val watched=watchedPane()
        if(watched!=null) connections[watched.server]?.let {client->
            val profile=servers.firstOrNull {it.id==watched.server}
            if(profile!=null) ActivityFocusPolicy.acknowledge(profile,client.state.value.activity,watched,client::isActivitySeen).takeIf {it.isNotEmpty()}?.let(client::markActivitySeen)
        }
        attentionCount=connections.values.sumOf {client->if(client.state.value.connected) ActivityFocusPolicy.unread(client.state.value.activity,client::isActivitySeen) else 0}
        activitySources=servers.mapNotNull {server->connections[server.id]?.state?.value?.let {state->
            if(state.phase in listOf("disconnected","preview")) null else ActivitySource(server,state.connected,state.activityLoading,
                if(state.connected) state.activityError else "Connection interrupted",state.activityPartial,state.activity,state.activity.filter {connections[server.id]?.isActivitySeen(it)==true}.map {it.key}.toSet())
        }}
        val panes=servers.flatMap {server->connections[server.id]?.state?.value?.takeIf {it.connected}?.let {AgentSessions.panes(server,it.panes,it.activity)}.orEmpty()}
        if(panes!=agentPanes) agentPanes=panes
        val chrome=HomeChrome(servers.isNotEmpty(),activitySources.any {it.connected},activitySources.any {it.connected && it.loading},
            serverStatuses.values.firstOrNull {it.error.isNotBlank()}?.error.orEmpty())
        if(chrome!=homeChrome) homeChrome=chrome
        observeBanners()
    }
    private val bannerLedger=AttentionLedger()
    private var bannerQuiet=emptySet<String>()
    private var bannerSerial=0L
    internal var banner by mutableStateOf<AttentionBanner?>(null)
    private fun observeBanners() {
        val selected=connections[activeServerId]?.state?.value
        activitySources.filter {it.connected}.forEach {source->
            val client=connections[source.server.id] ?: return@forEach
            val complete=!source.loading && !source.partial && source.error.isBlank()
            val quiet=!foreground || homeVisible || source.server.id in bannerQuiet
            val alerts=bannerLedger.deliveries(source.server,source.items,{record->when {
                quiet->AlertSurface.HOME
                source.server.id==activeServerId && record.session==selected?.selectedSession && record.block==selected.selectedBlock->AlertSurface.SAME_PANE
                else->AlertSurface.OTHER_PANE
            }},complete,client::isActivitySeen)
            if(complete && foreground) bannerQuiet=bannerQuiet-source.server.id
            alerts.lastOrNull {it.delivery==AlertDelivery.BANNER}?.record?.let {record->
                banner=AttentionBanner.of(++bannerSerial,source.server,record)
            }
        }
    }
    internal fun markActivitySeen(target: ActivityTarget) {
        val client=connections[target.server.id] ?: return
        if(client.savedEndpoint!=target.server.endpoint) return
        val current=client.state.value.activity.firstOrNull {it.key==target.record.key && it.attentionVersion==target.record.attentionVersion} ?: return
        client.markActivitySeen(listOf(current));updateActivity()
    }
    internal fun markAllActivitySeen() {
        activitySources.filter {it.connected}.forEach {source->connections[source.server.id]?.markActivitySeen(source.items)};updateActivity()
    }
    internal fun openActivity(target: ActivityTarget) {
        val source=activitySources.firstOrNull {it.server.id==target.server.id && it.connected} ?: return
        val record=source.items.firstOrNull {it.key==target.record.key} ?: return
        markActivitySeen(target);openPane(PaneTarget(target.server,record.session,record.block))
    }
    internal fun openPane(target: PaneTarget) {
        val profile=servers.firstOrNull {it.id==target.server.id && it.endpoint==target.server.endpoint} ?: return
        if(activitySources.none {it.server.id==profile.id && it.connected}) return
        rememberLastSession();enterWorkspace();connectServer(profile.id)
        if(banner?.target==target) banner=null
        activeClient.openActivity(target.session,target.block);showActivity(false)
    }
    internal var homeVisible by mutableStateOf(true)
        private set
    private fun enterWorkspace() {if(homeVisible) {homeVisible=false;activeClient.visible=true}}
    internal fun showHome() {
        if(homeVisible) return
        rememberLastSession();activeClient.releaseControl()
        homeVisible=true;activeClient.visible=false;banner=null
        refreshActivity();updateActivity()
    }
    internal fun createSession(label: String): Boolean {
        val wasHome=homeVisible
        enterWorkspace()
        val created=activeClient.createSession(label)
        if(!created && wasHome) showHome()
        return created
    }
    internal var agentPanes by mutableStateOf<List<AgentPane>>(emptyList())
        private set
    internal var homeChrome by mutableStateOf(HomeChrome(hasServers=false,connected=false,loading=false))
        private set
    private val preferences=application.getSharedPreferences(preferenceName,0)
    internal var sessionsLayout by mutableStateOf(runCatching {SessionsLayout.valueOf(preferences.getString("sessions_layout","STATUS")!!)}.getOrDefault(SessionsLayout.STATUS))
        private set
    internal fun changeSessionsLayout(value: SessionsLayout) {sessionsLayout=value;preferences.edit {putString("sessions_layout",value.name)}}
    internal var notificationPromptPending by mutableStateOf(!preferences.getBoolean(BackgroundAlerts.PROMPTED,false))
        private set
    internal fun finishNotificationPrompt(granted: Boolean) {
        notificationPromptPending=false;preferences.edit {putBoolean(BackgroundAlerts.PROMPTED,true)}
        if(granted && BackgroundAlerts.allowed(getApplication())) setBackgroundAlerts(true)
    }
    private var serverSwitchSerial=0L
    internal var pendingUploadTarget: UploadTarget?=null
    internal val uploads=FileUploads(application,viewModelScope)
    internal fun uploadTarget(): UploadTarget? {
        val client=activeClient;val state=client.state.value
        if(!foreground || !state.connected || !state.controlled || state.creating || client.navigationRequest.pending || state.selectedBlock.isBlank()) return null
        val program=state.programs[state.selectedBlock] ?: ProgramIdentity.Terminal
        return UploadTarget(activeServerId,activeServer.endpoint,state.selectedSession,state.selectedBlock,client.selectionVersion,serverSwitchSerial,program.kind,"${program.title} · ${activeServer.label}")
    }
    internal fun attachFiles(uris: List<android.net.Uri>,target: UploadTarget?,onCopied: ()->Unit={}): Boolean {
        if(target==null) {onCopied();uploads.notice="Take control of a pane before attaching files.";return false}
        val profile=servers.firstOrNull {it.id==target.server && it.endpoint==target.endpoint}
        if(profile==null) {onCopied();uploads.notice="The original Rex server is no longer available.";return false}
        return uploads.start(uris,target,onCopied)
    }
    internal val slashCommands=SlashCommandCatalog(application.getSharedPreferences(SlashCommandCatalog.PREFERENCES,0),viewModelScope)
    internal fun slashTarget(): SlashTarget? {
        val state=activeClient.state.value
        if(!state.connected || state.selectedBlock.isBlank() || state.selectedSession.isBlank()) return null
        return SlashTarget(activeServerId,activeServer.endpoint,state.selectedSession,state.selectedBlock,state.directories[state.selectedBlock].orEmpty())
    }
    internal var experimentalCodexAttention by mutableStateOf(preferences.getBoolean(CodexAttentionPolicy.ENABLED,false))
        private set
    internal fun changeCodexAttention(enabled: Boolean) {
        experimentalCodexAttention=enabled;preferences.edit {putBoolean(CodexAttentionPolicy.ENABLED,enabled)}
        connections.values.forEach {it.refreshActivityPolicy()};updateActivity()
    }
    internal var backgroundAlerts by mutableStateOf(preferences.getBoolean(BackgroundAlerts.ENABLED,false))
        private set
    internal var notificationNotice by mutableStateOf("")
    private var pendingAlert: AlertDestination?=null
    private val alertsListener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->if(key==BackgroundAlerts.ENABLED) {
        viewModelScope.launch {backgroundAlerts=preferences.getBoolean(BackgroundAlerts.ENABLED,false)}
    }}
    internal fun setBackgroundAlerts(enabled: Boolean) {
        backgroundAlerts=enabled;preferences.edit {putBoolean(BackgroundAlerts.ENABLED,enabled)}
        BackgroundAlerts.reconcile(getApplication())
    }
    internal fun openAlert(target: AlertDestination) {
        val profile=servers.firstOrNull {it.id==target.serverId && it.endpoint==target.endpoint}
        if(profile==null) {notificationNotice="That server is no longer saved.";return}
        pendingAlert=target;enterWorkspace();connectServer(profile.id);tryOpenAlert()
    }
    private fun tryOpenAlert() {
        val target=pendingAlert ?: return
        val client=connections[target.serverId] ?: return
        val state=client.state.value
        if(!state.connected || !foreground) return
        pendingAlert=null
        if(state.sessions.none {it.id==target.session}) {notificationNotice="That session is no longer available.";return}
        client.markAttentionSeen(target.attentionKey,target.attentionVersion)
        enterWorkspace();client.openActivity(target.session,target.block);showActivity(false)
    }
    internal val themes by lazy {ThemeCatalog.load(application)}
    private var darkTheme by mutableStateOf(preferences.getString("dark_theme","rexlink-default-dark").orEmpty())
    private var lightTheme by mutableStateOf(preferences.getString("light_theme","rexlink-default-light").orEmpty())
    internal var keepScreenAwake by mutableStateOf(runCatching {KeepScreenAwake.valueOf(preferences.getString("keep_screen_awake","SYSTEM")!!)}.getOrDefault(KeepScreenAwake.SYSTEM))
        private set
    internal fun changeKeepScreenAwake(value: KeepScreenAwake) {keepScreenAwake=value;preferences.edit {putString("keep_screen_awake",value.name)}}
    var fontSize by mutableIntStateOf(preferences.getInt("font_size",10).coerceIn(8,20))
        private set
    internal fun theme(dark: Boolean): AppTheme {
        val id=if(dark) darkTheme else lightTheme
        return ThemeCatalog.defaults.firstOrNull {it.id==id && it.terminal.dark==dark} ?: ThemeCatalog.resolve(themes,dark,id)
    }
    internal fun chooseTheme(id: String) {
        val theme=themes.firstOrNull {it.id==id} ?: return
        if(theme.terminal.dark) darkTheme=id else lightTheme=id
        preferences.edit {putString(if(theme.terminal.dark) "dark_theme" else "light_theme",id)}
    }
    fun changeFontSize(size: Int) {fontSize=size.coerceIn(8,20);preferences.edit {putInt("font_size",fontSize)}}
    var appearance by mutableStateOf(runCatching {Appearance.valueOf(preferences.getString("appearance","SYSTEM")!!)}.getOrDefault(Appearance.SYSTEM))
        private set
    internal var minimizeFloatingPanes by mutableStateOf(preferences.getBoolean("minimize_floating_panes",false))
        private set
    internal fun changeMinimizeFloatingPanes(value: Boolean) {
        minimizeFloatingPanes=value
        preferences.edit {putBoolean("minimize_floating_panes",value)}
    }
    internal var controlItems by mutableStateOf(ControlShortcutConfig.restore(preferences.getString("control_shortcut_items",null)))
        private set
    internal fun saveControlShortcuts(text: String): String? = runCatching {
        val items=ControlShortcutConfig.parse(text)
        controlItems=items;preferences.edit {putString("control_shortcut_items",ControlShortcutConfig.editorText(items))}
    }.exceptionOrNull()?.message
    internal var toolbarKeys by mutableStateOf(ToolbarLayout.decode(preferences.getString("keyboard_toolbar",null)))
        private set
    internal fun changeToolbar(keys: List<ToolbarKey>) {
        toolbarKeys=ToolbarLayout.normalize(keys)
        preferences.edit {putString("keyboard_toolbar",ToolbarLayout.encode(toolbarKeys))}
    }
    internal var arrowJoystickEnabled by mutableStateOf(preferences.getBoolean("arrow_joystick_enabled",true))
        private set
    internal fun changeArrowJoystick(value: Boolean) {arrowJoystickEnabled=value;preferences.edit {putBoolean("arrow_joystick_enabled",value)}}
    internal var shortcutGesture by mutableStateOf(runCatching {ControlShortcutGesture.valueOf(preferences.getString("ctrl_shortcut_gesture","HOLD")!!)}.getOrDefault(ControlShortcutGesture.HOLD))
        private set
    internal fun changeShortcutGesture(value: ControlShortcutGesture) {shortcutGesture=value;preferences.edit {putString("ctrl_shortcut_gesture",value.name)}}
    internal var neovimShortcutLayout by mutableStateOf(runCatching {NeovimShortcutLayout.valueOf(preferences.getString("neovim_shortcut_layout","CIRCULAR")!!)}.getOrDefault(NeovimShortcutLayout.CIRCULAR))
        private set
    internal fun changeNeovimShortcutLayout(value: NeovimShortcutLayout) {neovimShortcutLayout=value;preferences.edit {putString("neovim_shortcut_layout",value.name)}}
    internal var neovimShortcutEffect by mutableStateOf(NeovimShortcutEffect.fromPreference(preferences.getString("neovim_shortcut_effect","FAN_OUT")))
        private set
    internal fun changeNeovimShortcutEffect(value: NeovimShortcutEffect) {neovimShortcutEffect=value;preferences.edit {putString("neovim_shortcut_effect",value.name)}}
    internal var neovimShortcutHaptics by mutableStateOf(preferences.getBoolean("neovim_shortcut_haptics",true))
        private set
    internal fun changeNeovimShortcutHaptics(value: Boolean) {neovimShortcutHaptics=value;preferences.edit {putBoolean("neovim_shortcut_haptics",value)}}
    internal var neovimShortcuts by mutableStateOf(NeovimShortcuts.decode(preferences.getString("neovim_shortcuts",null)))
        private set
    internal var neovimShortcutsEnabled by mutableStateOf(preferences.getBoolean("neovim_shortcuts_enabled",true))
        private set
    internal fun enableNeovimShortcuts(value: Boolean) {neovimShortcutsEnabled=value;preferences.edit {putBoolean("neovim_shortcuts_enabled",value)}}
    internal var neovimShortcutPosition by mutableStateOf(androidx.compose.ui.geometry.Offset(
        preferences.getFloat("neovim_shortcut_x",0f).takeIf {it.isFinite()}?.coerceIn(0f,1f) ?: 0f,
        preferences.getFloat("neovim_shortcut_y",1f).takeIf {it.isFinite()}?.coerceIn(0f,1f) ?: 1f))
        private set
    internal fun moveNeovimShortcut(value: androidx.compose.ui.geometry.Offset) {
        if(!value.x.isFinite() || !value.y.isFinite()) return
        neovimShortcutPosition=androidx.compose.ui.geometry.Offset(value.x.coerceIn(0f,1f),value.y.coerceIn(0f,1f))
        preferences.edit {putFloat("neovim_shortcut_x",neovimShortcutPosition.x);putFloat("neovim_shortcut_y",neovimShortcutPosition.y)}
    }
    internal fun saveNeovimShortcuts(text: String): String? = runCatching {
        val items=NeovimShortcuts.parse(text)
        neovimShortcuts=items;preferences.edit {putString("neovim_shortcuts",NeovimShortcuts.encode(items))}
    }.exceptionOrNull()?.message
    var servers by mutableStateOf(SavedServers.decode(preferences.getString("servers",null),preferences.getString("endpoint","").orEmpty()))
        private set
    var activeServerId by mutableStateOf(preferences.getString("active_server",null)?.takeIf {id->servers.any {it.id==id}} ?: servers.firstOrNull()?.id.orEmpty())
        private set
    var serverStatuses by mutableStateOf<Map<String,ServerStatus>>(emptyMap())
        private set
    internal var sessionTargets by mutableStateOf<List<SessionTarget>>(emptyList())
        private set
    internal var lastSessionKey by mutableStateOf(preferences.getString("last_global_session","").orEmpty())
        private set
    var closingSessionKey by mutableStateOf("")
        private set
    var sessionActionError by mutableStateOf("")
        private set
    internal var renamingSessionKey by mutableStateOf("")
        private set
    private var renameServerId=""
    private var renameSessionId=""
    private var renameLabel=""
    private var closingServerId=""
    private var closingSessionId=""
    private val connections=mutableMapOf<String,RexConnection>()
    private val observers=mutableMapOf<String,Job>()
    private var desired=preferences.getStringSet("connected_servers",emptySet()).orEmpty().filter {id->servers.any {it.id==id}}.toSet()
    private var palette: TerminalPalette?=null
    private var windowFocused=false
    internal fun windowFocusChanged(focused: Boolean) {windowFocused=focused;updateActivity()}
    private var foreground=true
    private var networkJob: Job?=null
    private val networkMonitor=NetworkMonitor(application) {change->viewModelScope.launch {
        val wasOffline=networkAvailable==false;networkAvailable=change.available
        if(!change.available || change.retry || wasOffline) {
            networkJob?.cancel();networkJob=viewModelScope.launch {
                if(change.available) delay(300)
                if(foreground) connections.filterKeys {it in desired}.values.forEach {it.networkChanged(change)}
            }
        }
    }}
    internal var activeClient by mutableStateOf(connection(activeServer))
        private set
    val activeServer: ServerProfile get()=servers.firstOrNull {it.id==activeServerId} ?: ServerProfile.Empty
    val connectedCount: Int get()=serverStatuses.values.count {it.connected}

    init {viewModelScope.launch {NetworkSettings.revision.drop(1).collect {connections.filterKeys {it in desired}.values.forEach {it.retryNow()}}};preferences.registerOnSharedPreferenceChangeListener(alertsListener);desired.toList().forEach {id->connectServer(id,select=false)}}

    private fun connection(profile: ServerProfile): RexConnection = connections.getOrPut(profile.id) {
        RexConnection(getApplication(),viewModelScope,profile.endpoint,preferences).also {client->
            client.visible=profile.id==activeServerId && !homeVisible
            client.activity(true)
            palette?.let(client::updateTerminalTheme)
            if(!foreground) client.pause()
            observers[profile.id]=viewModelScope.launch {
                client.chromeState.collect {state->
                    val next=ServerStatus(state.phase,state.error)
                    if(serverStatuses[profile.id]!=next) serverStatuses=serverStatuses+(profile.id to next)

                    updateSessionTargets()
                    if(profile.id==pendingAlert?.serverId) tryOpenAlert()
                    updateActivity()
                    if(renamingSessionKey.isNotBlank() && profile.id==renameServerId) {
                        if(!state.creating && state.sessions.any {it.id==renameSessionId && it.label==renameLabel}) renamingSessionKey=""
                        else if(state.error.isNotBlank() || !state.connected || state.sessions.none {it.id==renameSessionId}) {
                            renamingSessionKey="";sessionActionError=state.error.ifBlank {if(state.connected) "That session is no longer available" else "Connection interrupted. Check the session name before retrying."}
                        }
                    }
                    if(closingSessionKey.isNotBlank() && profile.id==closingServerId) {
                        if(state.sessions.none {it.id==closingSessionId}) closingSessionKey=""
                        else if(state.error.isNotBlank() || !state.connected) {
                            sessionActionError=state.error.ifBlank {"Connection interrupted. Refresh the session list before trying again."}
                            closingSessionKey=""
                        }
                    }
                }
            }
        }
    }
    private fun updateSessionTargets() {
        val next=SessionCatalog.collect(servers,connections.mapValues {it.value.state.value})
        if(next!=sessionTargets) sessionTargets=next
    }
    private fun rememberLastSession() {
        val session=activeClient.navigationRequest.session ?: activeClient.state.value.selectedSession
        if(session.isNotBlank()) {
            lastSessionKey=sessionKey(activeServerId,session)
            preferences.edit {putString("last_global_session",lastSessionKey)}
        }
    }
    internal fun selectSessionTarget(key: String) {
        val target=sessionTargets.firstOrNull {it.key==key} ?: return
        if(activeServerId==target.server.id) {
            val current=activeClient.navigationRequest.session ?: activeClient.state.value.selectedSession
            if(current==target.session.id) return
            rememberLastSession()
        }
        connectServer(target.server.id)
        activeClient.selectSession(target.session.id)
    }
    internal fun renameSession(target: SessionTarget,label: String): Boolean {
        if(closingSessionKey.isNotBlank() || renamingSessionKey.isNotBlank()) return false
        sessionActionError=""
        sessionNameError(label)?.let {sessionActionError=it;return false}
        val current=sessionTargets.firstOrNull {it.key==target.key && it.server.endpoint==target.server.endpoint}
        if(current==null) {sessionActionError="That session is no longer available";return false}
        renameServerId=target.server.id;renameSessionId=target.session.id;renameLabel=label.trim();renamingSessionKey=target.key
        if(connections[target.server.id]?.renameSession(target.session.id,renameLabel)!=true) {
            renamingSessionKey="";sessionActionError="Wait for the server to finish its current operation, then try again.";return false
        }
        return true
    }
    internal fun closeSession(target: SessionTarget) {
        if(closingSessionKey.isNotBlank() || renamingSessionKey.isNotBlank()) return
        sessionActionError=""
        val current=sessionTargets.firstOrNull {it.key==target.key && it.server.endpoint==target.server.endpoint}
        if(current==null) {sessionActionError="That session is no longer available";return}
        closingSessionKey=target.key;closingServerId=target.server.id;closingSessionId=target.session.id
        if(connections[target.server.id]?.closeSession(target.session.id)!=true) {
            closingSessionKey="";sessionActionError="Wait for the server to finish its current operation, then try again."
        }
    }
    internal fun jumpLastSession() {
        if(sessionTargets.any {it.key==lastSessionKey}) selectSessionTarget(lastSessionKey)
        else activeClient.jumpLastSession()
    }
    internal fun refreshSessionDetails() {connections.values.filter {it.state.value.connected}.forEach {it.refreshSessionDetails()}}
    private fun persist() {updateSessionTargets();preferences.edit {
        putString("servers",SavedServers.encode(servers));putString("active_server",activeServerId)
        putStringSet("connected_servers",desired)
    };if(foreground && BackgroundAlerts.foreground.value) BackgroundAlerts.reconcile(getApplication())}
    fun connectServer(id: String,select: Boolean=true) {
        val profile=servers.firstOrNull {it.id==id} ?: return
        val client=connection(profile)
        if(select && activeServerId!=id) {
            rememberLastSession();serverSwitchSerial++
            activeClient.releaseControl();activeClient.visible=false
            client.visible=!homeVisible;activeServerId=id;activeClient=client
        }
        desired=desired+id;persist()
        if(client.state.value.phase in listOf("disconnected","error","preview")) client.connect(profile.endpoint)
        else if(foreground && client.state.value.phase=="paused") client.resume()
    }
    /** The legacy URL form also adds a new host without disconnecting existing ones. */
    fun connect(endpoint: String): String? = runCatching {
        val normalized=ServerProfile.normalizeEndpoint(endpoint)
        val existing=servers.firstOrNull {it.endpoint==normalized}
        if(existing!=null) connectServer(existing.id)
        else saveServer(null,"",normalized)?.let {throw IllegalArgumentException(it)}
    }.exceptionOrNull()?.message

    internal fun forwardServers(): List<ForwardServer> {
        val knownSessions=sessionTargets
        return servers.map {server->
        val state=connections[server.id]?.state?.value
        ForwardServer(server,state?.selectedSession?.takeIf {it.isNotBlank()} ?: knownSessions.firstOrNull {it.server.id==server.id}?.session?.id.orEmpty(),serverStatuses[server.id]?.connected==true)
        }
    }
    fun saveServer(id: String?,label: String,endpoint: String,icon: ServerIcon?=null): String? = runCatching {
        val profile=ServerProfile.create(label,endpoint,id ?: java.util.UUID.randomUUID().toString(),icon ?: servers.firstOrNull {it.id==id}?.icon ?: ServerIcon.GLOBE)
        require(servers.none {it.id!=profile.id && it.endpoint==profile.endpoint}) {"That server is already saved"}
        val old=servers.firstOrNull {it.id==profile.id}
        if(old!=null && old.endpoint!=profile.endpoint) {
            PortForwards.forgetServer(getApplication(),old.id)
            uploads.forget(old.id)
            connections.remove(old.id)?.let {it.releaseControl();it.disconnect();it.close()}
            observers.remove(old.id)?.cancel();serverStatuses=serverStatuses-old.id
        }
        servers=if(old==null) servers+profile else servers.map {if(it.id==profile.id) profile else it}
        persist()
        val client=connection(profile)
        if(activeServerId==profile.id) {activeClient=client;client.visible=!homeVisible}
        connectServer(profile.id)
        updateActivity()
    }.exceptionOrNull()?.message

    fun disconnectServer(id: String) {
        desired=desired-id;persist()
        connections[id]?.let {it.releaseControl();it.disconnect()}
    }
    fun forgetServer(id: String) {
        PortForwards.forgetServer(getApplication(),id)
        servers.firstOrNull {it.id==id}?.let {profile->NetworkSettings.forget(profile.endpoint,servers.filter {it.id!=id}.map {it.endpoint})}
        uploads.forget(id)
        if(activeServerId==id) serverSwitchSerial++
        disconnectServer(id)
        observers.remove(id)?.cancel();connections.remove(id)?.close()
        servers=servers.filterNot {it.id==id};serverStatuses=serverStatuses-id
        if(activeServerId==id) {
            activeServerId=servers.firstOrNull()?.id.orEmpty()
            activeClient=connection(activeServer);activeClient.visible=!homeVisible
        }
        persist()
    }
    fun previewServer(id: String) {
        if(id!=activeServerId) return
        desired=desired-id;persist();activeClient.preview()
    }
    fun updateTerminalTheme(value: TerminalPalette) {palette=value;connections.values.forEach {it.updateTerminalTheme(value)}}
    fun changeAppearance(value: Appearance) {appearance=value;preferences.edit {putString("appearance",value.name)}}
    fun pause() {foreground=false;bannerQuiet=servers.map {it.id}.toSet();banner=null;updateActivity();networkJob?.cancel();networkMonitor.stop();connections.values.forEach {it.pause()}}
    fun resume() {foreground=true;networkMonitor.start();connections.values.forEach {it.resume()};tryOpenAlert();BackgroundAlerts.reconcile(getApplication())}
    override fun onCleared() {uploads.close();slashCommands.close();preferences.unregisterOnSharedPreferenceChangeListener(alertsListener);networkJob?.cancel();networkMonitor.stop();observers.values.forEach {it.cancel()};connections.values.forEach {it.close()}}
}
