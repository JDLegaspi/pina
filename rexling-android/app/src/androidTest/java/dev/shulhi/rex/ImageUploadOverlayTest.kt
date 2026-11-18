package dev.shulhi.rex

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import dev.chrisbanes.haze.hazeSource

@RunWith(AndroidJUnit4::class)
class ImageUploadOverlayTest {
    @get:Rule val ui=createComposeRule()
    @Test fun pillKeepsItsRightEdgeAcrossPhasesAndExposesSafeActionsInBothThemes() {
        val target=UploadTarget("fixture","https://fixture","s","b",1,1,ProgramKind.CODEX,"Fixture")
        val state=mutableStateOf(UploadStatus(target,UploadPhase.PREPARING,listOf("image.png")))
        val dark=mutableStateOf(true);val retryable=mutableStateOf(true)
        var canceled=0;var retried=0
        val visible=mutableStateOf(true)
        ui.mainClock.autoAdvance=false
        ui.setContent {PinaTheme(dark.value) {
            val haze=remember {dev.chrisbanes.haze.HazeState()}
            Box(Modifier.size(360.dp,420.dp).background(MaterialTheme.colorScheme.background).testTag("upload-host")) {
                val backdrop=MaterialTheme.colorScheme.background
                val text=MaterialTheme.colorScheme.onBackground
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize().hazeSource(haze)) {
                    drawRect(backdrop)
                    repeat(30) {row->drawRect(text.copy(alpha=.5f),androidx.compose.ui.geometry.Offset(4.dp.toPx(),row*14.dp.toPx()),androidx.compose.ui.geometry.Size(size.width*.8f,1.dp.toPx()))}
                }
                if(visible.value) UploadOverlay(state.value,{retried++},{canceled++},Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("upload-pill"),retryable.value)
            }
        }}
        fun settle() {ui.mainClock.advanceTimeBy(1000);ui.waitForIdle()}
        fun right()=ui.onNodeWithTag("upload-pill").fetchSemanticsNode().boundsInRoot.right
        fun capture(name:String) {
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,name).outputStream().use {
                ui.onNodeWithTag("upload-pill").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
            }
        }
        settle();val anchor=right()
        val actionAnchor=ui.onNodeWithContentDescription("Cancel upload").fetchSemanticsNode().boundsInRoot.right
        ui.onNodeWithText("Preparing image.png").assertIsDisplayed()
        ui.runOnIdle {state.value=state.value.copy(phase=UploadPhase.UPLOADING,progress=0f)};settle()
        ui.onNodeWithText("Uploading image.png").assertIsDisplayed()
        ui.onNodeWithContentDescription("0%").assertDoesNotExist()
        for(isDark in listOf(true,false)) {
            ui.runOnIdle {dark.value=isDark;state.value=state.value.copy(phase=UploadPhase.UPLOADING,progress=.42f)};settle()
            ui.onNodeWithContentDescription("42%").assertIsDisplayed();assertEquals(anchor,right(),.5f)
            capture("upload-pill-${if(isDark) "dark" else "light"}.png")
            ui.runOnIdle {state.value=state.value.copy(progress=.73f)};settle()
            ui.onNodeWithContentDescription("73%").assertIsDisplayed();assertEquals(anchor,right(),.5f)
            ui.runOnIdle {state.value=state.value.copy(phase=UploadPhase.FINISHING)};settle()
            ui.onNodeWithText("Finishing upload").assertIsDisplayed();assertEquals(anchor,right(),.5f)
            ui.onNodeWithContentDescription("Cancel upload").performClick()
            ui.runOnIdle {state.value=state.value.copy(phase=UploadPhase.FAILED,error="The image may have been inserted. Check the original pane before trying again.");retryable.value=false};settle()
            ui.onNodeWithText("Retry").assertDoesNotExist();assertEquals(anchor,right(),.5f)
            assertEquals(actionAnchor,ui.onNodeWithContentDescription("Dismiss upload error").fetchSemanticsNode().boundsInRoot.right,.5f)
            capture("upload-pill-error-${if(isDark) "dark" else "light"}.png")
            ui.onNodeWithContentDescription("Dismiss upload error").performClick()
            ui.runOnIdle {retryable.value=true};settle();ui.onNodeWithText("Retry").performClick()
        }
        ui.runOnIdle {assertEquals(4,canceled);assertEquals(2,retried)}
        ui.runOnIdle {state.value=state.value.copy(phase=UploadPhase.SUCCESS,progress=1f)};settle()
        ui.onNodeWithText("image.png uploaded").assertIsDisplayed()
        ui.onNodeWithContentDescription("Cancel upload").assertDoesNotExist()
        assertEquals(anchor,right(),.5f);capture("upload-pill-success.png")
        if(InstrumentationRegistry.getArguments().getString("uploadAnimationCapture")=="true") {
            var frame=0
            fun frames(count:Int) {repeat(count) {
                ui.mainClock.advanceTimeBy(40);ui.waitForIdle()
                File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"upload-motion-%03d.png".format(frame++)).outputStream().use {
                    ui.onNodeWithTag("upload-host").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
                }
            }}
            ui.runOnIdle {dark.value=true;state.value=state.value.copy(phase=UploadPhase.UPLOADING,progress=.90f)};settle()
            for(progress in listOf(.91f,.92f,.93f,.94f)) {ui.runOnIdle {state.value=state.value.copy(progress=progress)};frames(7)}
            ui.runOnIdle {state.value=state.value.copy(phase=UploadPhase.FINISHING,progress=1f)};frames(8)
            ui.runOnIdle {state.value=state.value.copy(phase=UploadPhase.SUCCESS,progress=1f)};frames(18)
            ui.runOnIdle {visible.value=false};frames(4)
        }

    }
}
