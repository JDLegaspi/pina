package dev.shulhi.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant

internal data class HomeChrome(val hasServers: Boolean,val connected: Boolean,val loading: Boolean,val error: String="")

internal data class AttentionBanner(val id: Long,val target: PaneTarget,val program: ProgramIdentity,val session: String,val phrase: String,val message: String,val status: AgentStatus) {
    companion object {
        fun of(id: Long,server: ServerProfile,record: ActivityRecord)=AttentionBanner(id,PaneTarget(server,record.session,record.block),record.program,
            AlertCopy.session(record),AlertCopy.phrase(record),record.message,AgentStatus.of(record.state))
    }
}

@Composable internal fun AgentSessionsScreen(model: RexModel,onNewSession: ()->Unit,onSettings: ()->Unit,onAddServer: ()->Unit) {
    val now by produceState(Instant.now()) {while(true) {delay(30_000);value=Instant.now()}}
    var refreshing by remember {mutableStateOf(false)}
    LaunchedEffect(refreshing,model.homeChrome.loading) {if(refreshing) {delay(600);if(!model.homeChrome.loading) refreshing=false}}
    AgentSessionsContent(model.agentPanes,model.servers,model.sessionsLayout,model::changeSessionsLayout,model.homeChrome,now,
        refreshing,{refreshing=true;model.refreshActivity()},model::openPane,onNewSession,onSettings,onAddServer)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AgentSessionsContent(panes: List<AgentPane>,servers: List<ServerProfile>,layout: SessionsLayout,onLayout: (SessionsLayout)->Unit,
    chrome: HomeChrome,now: Instant,refreshing: Boolean,onRefresh: ()->Unit,onOpen: (PaneTarget)->Unit,
    onNewSession: ()->Unit,onSettings: ()->Unit,onAddServer: ()->Unit,modifier: Modifier=Modifier) {
    val colors=LocalPinaColors.current
    val sections=remember(panes,layout,servers) {if(layout==SessionsLayout.STATUS) AgentSessions.statusSections(panes) else AgentSessions.folderSections(panes,servers)}
    val multiServer=remember(panes) {panes.map {it.target.server.id}.distinct().size>1}
    PullToRefreshBox(refreshing,onRefresh,modifier.fillMaxSize().background(colors.groupedBackground)) {
        LargeTitleScaffold("Sessions",trailing={
            NavigationAction(ChromeIcon.ADD,"New session",chrome.connected,onNewSession)
            NavigationAction(ChromeIcon.SETTINGS,"Preferences and servers",true,onSettings)
        }) {
            item(key="layout") {
                SegmentedControl(SessionsLayout.entries.map {it.label},layout.ordinal,{onLayout(SessionsLayout.entries[it])},
                    Modifier.padding(start=16.dp,end=16.dp,top=4.dp,bottom=8.dp))
            }
            when {
                !chrome.hasServers->item(key="empty") {EmptyHome("Connect to your Mac","Pina lists the Rex sessions on your Mac and the agents running in them.","Add server",onAddServer)}
                sections.isEmpty() && (chrome.loading || !chrome.connected)->item(key="empty") {LoadingHome(if(chrome.connected) "Looking for agents…" else chrome.error.ifBlank {"Connecting…"})}
                sections.isEmpty()->item(key="empty") {
                    if(layout==SessionsLayout.STATUS) EmptyHome("No agents are running","Agents that report their status appear here. Folders lists every terminal.",null,null)
                    else EmptyHome("No terminals","Start a session to see its terminals here.","New session",onNewSession.takeIf {chrome.connected})
                }
            }
            sections.forEach {section->
                item(key=section.key) {
                    InsetGroupedSection(section.title,modifier=Modifier.padding(top=if(layout==SessionsLayout.STATUS) 20.dp else 16.dp),
                        headerStyle=if(layout==SessionsLayout.STATUS) SectionHeaderStyle.PROMINENT else SectionHeaderStyle.TITLE,
                        headerBadge=colors.red.takeIf {section.status==AgentStatus.BLOCKED}) {
                        section.panes.forEachIndexed {index,pane->
                            if(index>0) GroupedDivider(GroupedRowDefaults.LeadingTextInset)
                            if(layout==SessionsLayout.STATUS) StatusPaneRow(pane,now,multiServer,onOpen) else FolderPaneRow(pane,onOpen)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun NavigationAction(icon: ChromeIcon,label: String,enabled: Boolean,onClick: ()->Unit) {
    val colors=LocalPinaColors.current
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled=enabled,role=Role.Button,onClick=onClick).semantics {contentDescription=label},contentAlignment=Alignment.Center) {
        ChromeSymbol(icon,Modifier.size(24.dp),if(enabled) colors.tint else colors.tertiaryLabel)
    }
}

@Composable private fun StatusPaneRow(pane: AgentPane,now: Instant,multiServer: Boolean,onOpen: (PaneTarget)->Unit) {
    val colors=LocalPinaColors.current
    val subtitle=listOf(pane.sessionLabel,if(multiServer) pane.target.server.label else "").filter {it.isNotBlank()}.joinToString(" · ")
    Column(Modifier.fillMaxWidth()) {
        GroupedRow(pane.title,leading={ProgramIconTile(pane.program)},subtitle=subtitle.ifBlank {null},titleSuffix=pane.directory,titleMaxLines=1,subtitleMaxLines=1,
            titleTrailing={
                val progress=pane.progress.takeIf {pane.status==AgentStatus.WORKING}
                if(progress!=null) Text("$progress%",style=PinaType.subheadline,color=colors.tint)
                else pane.updatedAt?.let {Text(AgentSessions.relativeAge(it,now),style=PinaType.subheadline,color=colors.secondaryLabel)}
            },onClick={onOpen(pane.target)})
        if(pane.status==AgentStatus.WORKING && pane.progress!=null) LinearProgressIndicator({pane.progress/100f},
            Modifier.fillMaxWidth().padding(start=GroupedRowDefaults.LeadingTextInset,end=16.dp,bottom=10.dp).height(3.dp).clip(CircleShape),
            color=colors.tint,trackColor=colors.fill,gapSize=0.dp,drawStopIndicator={})
        if(pane.status==AgentStatus.BLOCKED) StatusCallout(pane.blockedKind.title,pane.message,colors.tint,
            Modifier.padding(start=GroupedRowDefaults.LeadingTextInset,end=16.dp,bottom=12.dp)
                .clickable(remember {MutableInteractionSource()},indication=null) {onOpen(pane.target)})
    }
}

@Composable private fun FolderPaneRow(pane: AgentPane,onOpen: (PaneTarget)->Unit) {
    GroupedRow(pane.title,leading={ProgramIconTile(pane.program)},titleSuffix=pane.directory,titleMaxLines=1,
        trailing=pane.status?.let {status->{StatusDot(status)}},onClick={onOpen(pane.target)})
}

@Composable private fun StatusDot(status: AgentStatus) {
    val colors=LocalPinaColors.current
    Box(Modifier.size(10.dp).clip(CircleShape).background(status.tint(colors)).semantics {contentDescription=status.title})
}

@Composable private fun EmptyHome(title: String,detail: String,action: String?,onAction: (()->Unit)?) {
    val colors=LocalPinaColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal=32.dp).padding(top=96.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=PinaType.title3,color=colors.label,textAlign=TextAlign.Center)
        Text(detail,style=PinaType.subheadline,color=colors.secondaryLabel,textAlign=TextAlign.Center)
        if(action!=null && onAction!=null) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.clip(RoundedCornerShape(50)).background(colors.tint).clickable(role=Role.Button,onClick=onAction).heightIn(min=44.dp).padding(horizontal=22.dp),contentAlignment=Alignment.Center) {
                Text(action,style=PinaType.headline,color=Color.White)
            }
        }
    }
}

@Composable private fun LoadingHome(label: String) {
    val colors=LocalPinaColors.current
    Column(Modifier.fillMaxWidth().padding(top=120.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(22.dp),color=colors.secondaryLabel,strokeWidth=2.dp)
        Text(label,style=PinaType.subheadline,color=colors.secondaryLabel,textAlign=TextAlign.Center)
    }
}

@Composable internal fun AttentionBannerView(banner: AttentionBanner,onOpen: ()->Unit,onDismiss: ()->Unit,modifier: Modifier=Modifier) {
    val colors=LocalPinaColors.current
    LaunchedEffect(banner.id) {delay(5_000);onDismiss()}
    val shape=RoundedCornerShape(18.dp)
    Row(modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal=10.dp,vertical=6.dp).fillMaxWidth()
        .shadow(16.dp,shape,ambientColor=Color.Black.copy(alpha=.3f),spotColor=Color.Black.copy(alpha=.3f))
        .clip(shape).background(if(colors.dark) colors.tertiaryGroupedBackground else colors.secondaryGroupedBackground)
        .border(1.dp,colors.separator.copy(alpha=.5f),shape)
        .clickable(role=Role.Button,onClick=onOpen).padding(horizontal=12.dp,vertical=10.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        ProgramIconTile(banner.program,size=34.dp)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                val style=PinaType.subheadline.copy(fontWeight=FontWeight.SemiBold)
                Row(Modifier.weight(1f)) {
                    Text(banner.session,Modifier.weight(1f,fill=false),style=style,color=colors.label,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(" · ",style=style,color=colors.secondaryLabel,maxLines=1)
                    Text(banner.phrase,style=style,color=if(banner.status==AgentStatus.BLOCKED) colors.tint else banner.status.tint(colors),maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                Text("now",Modifier.padding(start=8.dp),style=PinaType.footnote,color=colors.secondaryLabel,maxLines=1)
            }
            if(banner.message.isNotBlank()) Text(banner.message,style=PinaType.subheadline,color=colors.secondaryLabel,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}
