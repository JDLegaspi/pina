package dev.shulhi.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SlashCommandSheet(state: SlashCatalogState,onPick: (SlashCommand)->Unit,onRetry: ()->Unit,onDismiss: ()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=Color.Transparent,tonalElevation=0.dp,dragHandle=null,contentWindowInsets={WindowInsets(0,0,0,0)}) {
        SlashCommandPicker(state.discovered,state.refresh,onPick,onRetry,onDismiss,
            Modifier.fillMaxHeight(.92f).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)))
    }
}

@Composable internal fun SlashCommandPicker(discovered: List<SlashCommand>?,refresh: SlashRefresh,onPick: (SlashCommand)->Unit,
    onRetry: ()->Unit,onCancel: ()->Unit,modifier: Modifier=Modifier,initialQuery: String="",autofocus: Boolean=true) {
    val colors=LocalPinaColors.current
    var query by rememberSaveable {mutableStateOf(initialQuery)}
    val commands=discovered.orEmpty()
    val sections=remember(commands) {SlashCommands.sections(commands)}
    val results=remember(commands,query) {if(query.isBlank()) emptyList() else SlashCommands.search(commands,query)}
    Column(modifier.fillMaxWidth().sheetSurface()) {
        DrawerHandle()
        Row(Modifier.fillMaxWidth().padding(start=16.dp,end=8.dp,top=2.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
            SearchField(query,{query=it},"Search commands and skills",Modifier.weight(1f),autofocus) {results.firstOrNull()?.let(onPick)}
            Text("Cancel",Modifier.clip(RoundedCornerShape(8.dp)).clickable(role=Role.Button,onClick=onCancel).padding(horizontal=8.dp,vertical=8.dp),
                style=PinaType.body,color=colors.tint)
        }
        RefreshLine(refresh,discovered==null)
        LazyColumn(Modifier.fillMaxWidth().weight(1f),contentPadding=PaddingValues(bottom=24.dp)) {
            if(refresh is SlashRefresh.Failed) item(key="refresh-error") {RefreshError(refresh.message,discovered!=null,onRetry)}
            if(query.isBlank()) sections.forEach {section->commandSection(section.key,section.title,section.commands,showSource=false,onPick)}
            else if(results.isEmpty()) item(key="empty") {
                Text("No Results for “${query.trim()}”",Modifier.fillMaxWidth().padding(top=48.dp,start=16.dp,end=16.dp),
                    style=PinaType.headline,color=colors.secondaryLabel,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            }
            else commandSection("results",null,results,showSource=true,onPick)
        }
    }
}

private fun LazyListScope.commandSection(key: String,title: String?,commands: List<SlashCommand>,showSource: Boolean,onPick: (SlashCommand)->Unit) {
    item(key="header:$key") {
        val colors=LocalPinaColors.current
        if(title==null) Spacer(Modifier.height(8.dp))
        else Text(title,Modifier.padding(start=32.dp,end=32.dp,top=18.dp,bottom=7.dp),style=SectionHeaderStyle.TITLE.text,
            color=colors.secondaryLabel,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
    itemsIndexed(commands,key={_,command->"$key:${command.source}:${command.name}"}) {index,command->
        val colors=LocalPinaColors.current
        val shape=RoundedCornerShape(topStart=if(index==0) 10.dp else 0.dp,topEnd=if(index==0) 10.dp else 0.dp,
            bottomStart=if(index==commands.lastIndex) 10.dp else 0.dp,bottomEnd=if(index==commands.lastIndex) 10.dp else 0.dp)
        Column(Modifier.padding(horizontal=16.dp).clip(shape).background(colors.secondaryGroupedBackground)) {
            if(index>0) GroupedDivider()
            SlashCommandRow(command,if(showSource) sourceLabel(command) else null) {onPick(command)}
        }
    }
}

private fun sourceLabel(command: SlashCommand)=if(command.source==SlashSource.PLUGIN) command.plugin ?: SlashSource.PLUGIN.title else command.source.title

@Composable private fun SlashCommandRow(command: SlashCommand,source: String?,onClick: ()->Unit) {
    val colors=LocalPinaColors.current
    val interaction=remember {MutableInteractionSource()}
    val pressed by interaction.collectIsPressedAsState()
    val namespace=command.name.substringBefore(':',"")
    val name=buildAnnotatedString {
        withStyle(SpanStyle(color=colors.secondaryLabel)) {append("/");if(namespace.isNotEmpty()) append("$namespace:")}
        append(command.name.removePrefix(if(namespace.isEmpty()) "" else "$namespace:"))
    }
    Column(Modifier.fillMaxWidth().background(if(pressed) colors.fill else Color.Transparent)
        .clickable(interaction,indication=null,role=Role.Button,onClick=onClick)
        .semantics {contentDescription="/${command.name}"}
        .padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(name,Modifier.weight(1f),style=CommandNameStyle,color=colors.label,maxLines=1,overflow=TextOverflow.Ellipsis)
            source?.let {Text(it,style=PinaType.footnote,color=colors.secondaryLabel,maxLines=1)}
        }
        if(command.description.isNotBlank()) Text(command.description,style=PinaType.subheadline,color=colors.secondaryLabel,
            maxLines=2,overflow=TextOverflow.Ellipsis)
    }
}

private val CommandNameStyle=TextStyle(fontFamily=MonoUi,fontSize=15.sp,lineHeight=20.sp,fontWeight=FontWeight.Normal,letterSpacing=0.sp)

@Composable private fun SearchField(value: String,onValue: (String)->Unit,placeholder: String,modifier: Modifier,autofocus: Boolean,onSubmit: ()->Unit) {
    val colors=LocalPinaColors.current
    val focus=remember {FocusRequester()}
    if(autofocus) LaunchedEffect(Unit) {runCatching {focus.requestFocus()}}
    Row(modifier.height(36.dp).clip(RoundedCornerShape(10.dp)).background(colors.fill.copy(alpha=colors.fill.alpha*.6f)).padding(horizontal=8.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        ChromeSymbol(ChromeIcon.SEARCH,Modifier.size(17.dp),colors.secondaryLabel)
        Box(Modifier.weight(1f),contentAlignment=Alignment.CenterStart) {
            if(value.isEmpty()) Text(placeholder,style=PinaType.body,color=colors.secondaryLabel,maxLines=1,overflow=TextOverflow.Ellipsis)
            BasicTextField(value,onValue,Modifier.fillMaxWidth().focusRequester(focus).semantics {contentDescription=placeholder},
                textStyle=PinaType.body.copy(color=colors.label),singleLine=true,cursorBrush=SolidColor(colors.tint),
                keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,imeAction=ImeAction.Go),keyboardActions=KeyboardActions(onGo={onSubmit()}))
        }
        if(value.isNotEmpty()) Box(Modifier.size(18.dp).clip(CircleShape).background(colors.secondaryLabel.copy(alpha=.5f))
            .clickable(role=Role.Button) {onValue("")}.semantics {contentDescription="Clear search"},contentAlignment=Alignment.Center) {
            ChromeSymbol(ChromeIcon.CLOSE,Modifier.size(10.dp),colors.secondaryGroupedBackground)
        }
    }
}

@Composable private fun RefreshLine(refresh: SlashRefresh,firstLoad: Boolean) {
    val colors=LocalPinaColors.current
    if(refresh!=SlashRefresh.Updating) return
    Row(Modifier.fillMaxWidth().padding(horizontal=32.dp).padding(bottom=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        CircularProgressIndicator(Modifier.size(11.dp),color=colors.secondaryLabel,strokeWidth=1.5.dp)
        Text(if(firstLoad) "Loading skills from your Mac…" else "Updating…",style=PinaType.footnote,color=colors.secondaryLabel)
    }
}

@Composable private fun RefreshError(message: String,stale: Boolean,onRetry: ()->Unit) {
    val colors=LocalPinaColors.current
    Row(Modifier.padding(horizontal=16.dp).padding(top=8.dp).fillMaxWidth().clip(PinaShapes.section).background(colors.secondaryGroupedBackground)
        .padding(start=16.dp,end=8.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(if(stale) "Couldn’t refresh skills" else "Couldn’t load skills",style=PinaType.subheadline.copy(fontWeight=FontWeight.SemiBold),color=colors.red)
            Text(message.replaceFirstChar {it.uppercase()}.let {if(stale) "Showing the last list. $it" else it},style=PinaType.footnote,color=colors.secondaryLabel,maxLines=3,overflow=TextOverflow.Ellipsis)
        }
        Text("Retry",Modifier.clip(RoundedCornerShape(8.dp)).clickable(role=Role.Button,onClick=onRetry).padding(8.dp),style=PinaType.body,color=colors.tint)
    }
}
