package dev.shulhi.rex

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val NavigationBarHeight = 44.dp
private val LargeTitleHeight = 52.dp

@Composable internal fun LargeTitleScaffold(
    title: String,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    trailing: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val colors = LocalPinaColors.current
    val collapseDistance = with(LocalDensity.current) { LargeTitleHeight.toPx() }
    val progress by remember(state, collapseDistance) {
        derivedStateOf { if(state.firstVisibleItemIndex > 0) 1f else (state.firstVisibleItemScrollOffset / collapseDistance).coerceIn(0f, 1f) }
    }
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val direction = LocalLayoutDirection.current
    Box(modifier.fillMaxSize().background(colors.groupedBackground)) {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(
            start = insets.calculateStartPadding(direction), end = insets.calculateEndPadding(direction),
            top = insets.calculateTopPadding() + NavigationBarHeight, bottom = insets.calculateBottomPadding() + 24.dp,
        )) {
            item(key = "pina-large-title") {
                Box(Modifier.fillMaxWidth().height(LargeTitleHeight).padding(horizontal = 16.dp), contentAlignment = Alignment.BottomStart) {
                    Text(title, Modifier.padding(bottom = 8.dp), style = PinaType.largeTitle, color = colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            content()
        }
        Box(Modifier.fillMaxWidth()
            .drawBehind {
                drawRect(colors.groupedBackground.copy(alpha = .94f * progress))
                drawLine(colors.separator.copy(alpha = inlineTitleAlpha(progress)), Offset(0f, size.height), Offset(size.width, size.height), 1f)
            }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .height(NavigationBarHeight)) {
            Text(title, Modifier.align(Alignment.Center).padding(horizontal = 72.dp).graphicsLayer { alpha = inlineTitleAlpha(progress) },
                style = PinaType.headline, color = colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}

private fun inlineTitleAlpha(progress: Float) = ((progress - .7f) / .3f).coerceIn(0f, 1f)

/** CAPS is the iOS settings header, TITLE the Mac sidebar's folder header, PROMINENT a status heading. */
internal enum class SectionHeaderStyle(val text: TextStyle) {
    CAPS(PinaType.footnote),
    TITLE(PinaType.subheadline.copy(fontWeight = FontWeight.SemiBold)),
    PROMINENT(PinaType.title3),
}

@Composable internal fun InsetGroupedSection(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    horizontalInset: Dp = 16.dp,
    headerStyle: SectionHeaderStyle = SectionHeaderStyle.CAPS,
    headerBadge: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalPinaColors.current
    val prominent = headerStyle == SectionHeaderStyle.PROMINENT
    Column(modifier.fillMaxWidth().padding(horizontal = horizontalInset)) {
        header?.let {
            Row(Modifier.padding(start = if(prominent) 4.dp else 16.dp, end = 16.dp, bottom = if(prominent) 8.dp else 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if(headerStyle == SectionHeaderStyle.CAPS) it.uppercase() else it, Modifier.weight(1f, fill = false), style = headerStyle.text,
                    color = if(prominent) colors.label else colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                headerBadge?.let { badge -> Box(Modifier.size(8.dp).clip(CircleShape).background(badge)) }
            }
        }
        Column(Modifier.fillMaxWidth().clip(PinaShapes.section).background(colors.secondaryGroupedBackground), content = content)
        footer?.let { Text(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp), style = PinaType.footnote, color = colors.secondaryLabel) }
    }
}

internal object GroupedRowDefaults {
    val LeadingSize = 29.dp
    val TextInset = 16.dp
    val LeadingTextInset = 16.dp + LeadingSize + 15.dp
}

@Composable internal fun GroupedRow(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    subtitle: String? = null,
    titleSuffix: String? = null,
    titleMaxLines: Int = 2,
    subtitleMaxLines: Int = 3,
    titleTrailing: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalPinaColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = if(onClick != null || onLongClick != null)
        Modifier.combinedClickable(interaction, indication = null, role = Role.Button, onLongClick = onLongClick, onClick = onClick ?: {})
    else Modifier
    Row(modifier.fillMaxWidth().background(if(pressed) colors.fill else Color.Transparent).then(press)
        .heightIn(min = 44.dp).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        leading?.let {
            Box(Modifier.size(GroupedRowDefaults.LeadingSize), contentAlignment = Alignment.Center) { it() }
            Spacer(Modifier.width(GroupedRowDefaults.LeadingTextInset - GroupedRowDefaults.TextInset - GroupedRowDefaults.LeadingSize))
        }
        Column(Modifier.weight(1f).padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            val styledTitle = if(titleSuffix.isNullOrBlank()) AnnotatedString(title) else buildAnnotatedString {
                append(title)
                withStyle(SpanStyle(color = colors.secondaryLabel)) { append(" "); append(titleSuffix) }
            }
            if(titleTrailing == null) Text(styledTitle, style = PinaType.body, color = colors.label, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(styledTitle, Modifier.weight(1f).alignByBaseline(), style = PinaType.body, color = colors.label, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
                Box(Modifier.alignByBaseline()) { titleTrailing() }
            }
            subtitle?.let { Text(it, style = PinaType.subheadline, color = colors.secondaryLabel, maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis) }
        }
        trailing?.let {
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = it)
        }
    }
}

@Composable internal fun GroupedDivider(startInset: Dp = GroupedRowDefaults.TextInset) {
    val colors = LocalPinaColors.current
    val hairline = with(LocalDensity.current) { 1f.toDp() }
    Box(Modifier.fillMaxWidth().padding(start = startInset).height(hairline).background(colors.separator))
}

@Composable internal fun GroupedChevron() {
    ChromeSymbol(ChromeIcon.RIGHT, Modifier.size(14.dp), LocalPinaColors.current.tertiaryLabel)
}

@Composable internal fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinaColors.current
    val trackShape = RoundedCornerShape(9.dp)
    val thumbShape = RoundedCornerShape(7.dp)
    BoxWithConstraints(modifier.fillMaxWidth().height(32.dp).clip(trackShape).background(colors.fill).padding(2.dp)) {
        val segment = maxWidth / options.size.coerceAtLeast(1)
        val offset by animateDpAsState(segment * selectedIndex, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow), label = "segment")
        if(selectedIndex in options.indices) Box(Modifier.offset(x = offset).width(segment).fillMaxHeight()
            .shadow(2.dp, thumbShape, ambientColor = Color.Black.copy(alpha = .12f), spotColor = Color.Black.copy(alpha = .12f))
            .background(colors.segmentThumb, thumbShape))
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                val divider = index > 0 && !selected && index - 1 != selectedIndex
                Box(Modifier.weight(1f).fillMaxHeight()
                    .drawBehind { if(divider) drawLine(colors.separator, Offset(0f, size.height * .25f), Offset(0f, size.height * .75f), 1f) }
                    .semantics { role = Role.Tab; this.selected = selected }
                    .combinedClickable(remember { MutableInteractionSource() }, indication = null, onClick = { onSelect(index) }),
                    contentAlignment = Alignment.Center) {
                    Text(option, style = PinaType.footnote.copy(fontWeight = if(selected) FontWeight.SemiBold else FontWeight.Medium),
                        color = colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private val ShellTile = Color(0xff1c1c1e)
private val ProgramTileColors = mapOf(
    ProgramKind.CLAUDE to PinaColors.Light.claude,
    ProgramKind.CODEX to Color(0xff10a37f),
    ProgramKind.NEOVIM to Color(0xff57a143),
    ProgramKind.VIM to Color(0xff019733),
    ProgramKind.GIT to Color(0xfff05032),
    ProgramKind.PYTHON to Color(0xff3776ab),
    ProgramKind.NODE to Color(0xff5fa04e),
    ProgramKind.CLOUDFLARE to Color(0xfff38020),
)

@Composable internal fun ProgramIconTile(program: ProgramIdentity, size: Dp = GroupedRowDefaults.LeadingSize, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * .22f)
    val background = ProgramTileColors[program.kind] ?: ShellTile
    val edge = if(background == ShellTile && LocalPinaColors.current.dark) Modifier.border(1.dp, Color.White.copy(alpha = .14f), shape) else Modifier
    Box(modifier.size(size).clip(shape).background(background).then(edge), contentAlignment = Alignment.Center) {
        ProgramIcon(program, Modifier.size(size * .6f), Color.White)
    }
}

@Composable internal fun StatusCallout(title: String, message: String, tint: Color, modifier: Modifier = Modifier) {
    val colors = LocalPinaColors.current
    Column(modifier.fillMaxWidth().clip(PinaShapes.section).background(colors.tertiaryGroupedBackground).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = PinaType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = tint)
        if(message.isNotBlank()) Text(message, style = PinaType.subheadline, color = colors.label, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun Modifier.sheetSurface(): Modifier = clip(PinaShapes.sheet).background(LocalPinaColors.current.groupedBackground)

@Composable private fun PinaComponentsSample() {
    val colors = LocalPinaColors.current
    var appearance by remember { mutableIntStateOf(0) }
    LargeTitleScaffold("Pina", trailing = { GroupedChevron() }) {
        item {
            SegmentedControl(Appearance.entries.map { it.label }, appearance, { appearance = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        item {
            InsetGroupedSection("Blocked", "Rows press like a table view cell.", Modifier.padding(top = 16.dp)) {
                GroupedRow("Refactor auth flow", leading = { ProgramIconTile(ProgramIdentity(ProgramKind.CLAUDE, "Claude Code")) },
                    subtitle = "~/Dev/pina", trailing = { GroupedChevron() }, onClick = {})
                StatusCallout("Waiting for permission", "Allow Bash(gradle test)?", colors.tint,
                    Modifier.padding(start = GroupedRowDefaults.LeadingTextInset, end = 16.dp, bottom = 10.dp))
                GroupedDivider(GroupedRowDefaults.LeadingTextInset)
                GroupedRow("zsh", leading = { ProgramIconTile(ProgramIdentity.Terminal) }, subtitle = "Idle", onClick = {})
            }
        }
    }
}

@Preview(name = "Light", widthDp = 390, heightDp = 640)
@Composable private fun PinaComponentsLightPreview() = PinaTheme(dark = false) { PinaComponentsSample() }

@Preview(name = "Dark", widthDp = 390, heightDp = 640)
@Composable private fun PinaComponentsDarkPreview() = PinaTheme(dark = true) { PinaComponentsSample() }
