package dev.shulhi.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class AgentSessionsScreenshotTest {
    private val directory = "../../docs/pina/screenshots"

    @Composable private fun Home(layout: SessionsLayout) = AgentSessionsContent(AgentFixtures.agentPanes,listOf(AgentFixtures.mac),layout,{},
        HomeChrome(hasServers=true,connected=true,loading=false),AgentFixtures.now,false,{},{},{},{},{})

    @Test fun statusLight() = captureRoboImage("$directory/home-status-light.png") {PinaTheme(dark=false) {Home(SessionsLayout.STATUS)}}
    @Test fun statusDark() = captureRoboImage("$directory/home-status-dark.png") {PinaTheme(dark=true) {Home(SessionsLayout.STATUS)}}
    @Test fun foldersLight() = captureRoboImage("$directory/home-folders-light.png") {PinaTheme(dark=false) {Home(SessionsLayout.FOLDERS)}}
    @Test fun foldersDark() = captureRoboImage("$directory/home-folders-dark.png") {PinaTheme(dark=true) {Home(SessionsLayout.FOLDERS)}}

    @Test fun bannerLight() = captureRoboImage("$directory/banner-light.png") {
        PinaTheme(dark=false) {
            Box(Modifier.fillMaxSize().background(Color(TerminalPalette.Light.background))) {
                Column(Modifier.padding(start=12.dp,top=120.dp)) {
                    listOf("~/code/pina main", "> ./scripts/gradle.sh :app:testDebugUnitTest", "", "BUILD SUCCESSFUL in 41s", "86 actionable tasks: 12 executed", "", "~/code/pina main", "> ").forEach {
                        Text(it,fontFamily=MonoUi,fontSize=13.sp,color=Color(TerminalPalette.Light.foreground))
                    }
                }
                AttentionBannerView(AttentionBanner.of(1,AgentFixtures.mac,AgentFixtures.record("meadow","quiet-meadow","q1","blocked",0,"Allow Bash(./gradlew test)?","permission")),{},{})
            }
        }
    }
}
