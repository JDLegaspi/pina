package dev.shulhi.rex

import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SetupScreenshotTest {
    private val directory = "../../docs/pina/screenshots"

    @Composable private fun EmptyHome() = AgentSessionsContent(emptyList(),emptyList(),SessionsLayout.STATUS,{},
        HomeChrome(hasServers=false,connected=false,loading=false),AgentFixtures.now,false,{},{},{},{},{})

    @Test fun onboardingLight() = captureRoboImage("$directory/onboarding-light.png") {PinaTheme(dark=false) {EmptyHome()}}

    @Test fun addServerLight() {
        NetworkSettings::class.java.getDeclaredField("context").apply {isAccessible=true}.set(null,RuntimeEnvironment.getApplication())
        val draft = ServerProfile.create("MacBook","https://rex.your-tailnet.ts.net",icon=ServerIcon.MACBOOK)
        captureRoboImage("$directory/add-server-light.png") {PinaTheme(dark=false) {
            EmptyHome()
            ServerEditor(null,{},{_,_,_->null},draft=draft)
        }}
    }
}
