package dev.shulhi.rex

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

/** Shared chrome for secondary settings drawers; scrolling stays in their content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SettingsDrawer(title: String,closeDescription: String,onClose: ()->Unit,
    hazeState: HazeState?=null,onBack: (() -> Unit)?=null,content: @Composable ColumnScope.()->Unit) {
    ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface,tonalElevation=0.dp,
        dragHandle=null,contentWindowInsets={WindowInsets(0,0,0,0)}) {
        Column(Modifier.drawerFrost(hazeState,BottomSheetDefaults.ExpandedShape).fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
            DrawerHandle()
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                onBack?.let {ChromeButton(ChromeIcon.LEFT,"Back to appearance",onClick=it)}
                Text(title,Modifier.weight(1f),style=SettingsType.row,fontWeight=FontWeight.Bold)
                ChromeButton(ChromeIcon.CLOSE,closeDescription,onClick=onClose)
            }
            content()
        }
    }
}
