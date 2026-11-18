package dev.shulhi.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SlashPickerScreenshotTest {
    private val directory = "../../docs/pina/screenshots"
    private val target = UploadTarget("mac","https://mac.example","s","b",1,1,ProgramKind.CLAUDE,"Claude Code · Mac")

    @Composable private fun Terminal(dark: Boolean, content: @Composable BoxScope.() -> Unit) {
        val palette = if(dark) TerminalPalette.Dark else TerminalPalette.Light
        Box(Modifier.fillMaxSize().background(Color(palette.background))) {
            Column(Modifier.padding(start=12.dp,top=60.dp)) {
                listOf("╭─────────────────────────────────────╮","│ ✻ Welcome to Claude Code!           │","╰─────────────────────────────────────╯","","> ").forEach {
                    Text(it,fontFamily=MonoUi,fontSize=13.sp,color=Color(palette.foreground))
                }
            }
            content()
        }
    }

    @Composable private fun Sheet(dark: Boolean, query: String = "", refresh: SlashRefresh = SlashRefresh.Updating) = Terminal(dark) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.32f)))
        SlashCommandPicker(SlashFixtures.probe,refresh,{},{},{},Modifier.align(Alignment.BottomCenter).fillMaxHeight(.92f),initialQuery=query,autofocus=false)
    }

    @Test fun pickerLight() = captureRoboImage("$directory/picker-light.png") {PinaTheme(dark=false) {Sheet(dark=false)}}
    @Test fun pickerDark() = captureRoboImage("$directory/picker-dark.png") {PinaTheme(dark=true) {Sheet(dark=true,refresh=SlashRefresh.Current)}}
    @Test fun pickerSearchLight() = captureRoboImage("$directory/picker-search-light.png") {PinaTheme(dark=false) {Sheet(dark=false,query="brain",refresh=SlashRefresh.Current)}}
    @Test fun pickerRefreshFailedLight() = captureRoboImage("$directory/picker-refresh-failed-light.png") {
        PinaTheme(dark=false) {Sheet(dark=false,refresh=SlashRefresh.Failed("connection timeout"))}
    }

    @Test fun keyboardBarLight() = captureRoboImage("$directory/keyboard-bar-light.png") {
        PinaTheme(dark=false) {
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical=8.dp)) {
                KeyboardBar(controlled=true,enabled=true,control=false,alt=false,shift=false,keyboardVisible=true,onControl={},onAlt={},onShift={},
                    onShiftPress={},onShiftRelease={},onKey={},onSlash={},onAttach={},onKeyboard={})
            }
        }
    }

    @Test fun uploadProgressLight() = captureRoboImage("$directory/upload-progress-light.png") {
        PinaTheme(dark=false) {
            Terminal(dark=false) {
                Column(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                    UploadOverlay(UploadStatus(target,UploadPhase.UPLOADING,listOf("design review.pdf","meeting notes 2026.txt","trace.json"),progress=.46f,current=1),{},{})
                    Box(Modifier.padding(top=12.dp))
                    UploadOverlay(UploadStatus(target,UploadPhase.FAILED,listOf("design review.pdf"),error="design review.pdf exceeds the Rex server's upload limit"),{},{})
                }
            }
        }
    }
}
