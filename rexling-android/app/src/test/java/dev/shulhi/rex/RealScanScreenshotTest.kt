package dev.shulhi.rex

import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/** Renders a local live scan from `live_home_scan_dump` into the gitignored evidence directory; skipped when no dump exists. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class RealScanScreenshotTest {
    private val directory = File("../../docs/pina/evidence")
    private val evidence = File(directory,"home-scan.json")

    private fun panes(): List<AgentPane> {
        val root = JSONObject(evidence.readText())
        val panes = root.getJSONArray("panes").let {a->(0 until a.length()).mapNotNull {PaneInfo.parse(a.getJSONObject(it))}}
        val records = root.getJSONArray("items").let {a->(0 until a.length()).mapNotNull {ActivityRecord.parse(a.getJSONObject(it))}}
        return AgentSessions.panes(AgentFixtures.mac,panes,records)
    }

    @Test fun liveScanSummary() {
        assumeTrue(evidence.exists())
        val panes = panes()
        val folders = AgentSessions.folderSections(panes,listOf(AgentFixtures.mac))
        assertEquals(panes.size,folders.sumOf {it.panes.size})
        val summary = AgentStatus.entries.associate {status->status.title to panes.count {it.status==status}} + ("No status" to panes.count {it.status==null})
        if(System.getenv("PINA_WRITE_EVIDENCE")!="1") return
        File(directory,"home-scan-summary.txt").writeText(
            "panes=${panes.size} sessions=${folders.size}\n"+summary.entries.joinToString("\n") {"${it.key}=${it.value}"}+"\n")
    }

    @Test fun liveStatus() {
        assumeTrue(evidence.exists())
        val now = panes().mapNotNull {it.updatedAt}.maxOrNull()?.plusSeconds(60) ?: Instant.now()
        captureRoboImage(File(directory,"home-live-status-light.png").path) {PinaTheme(dark=false) {
            AgentSessionsContent(panes(),listOf(AgentFixtures.mac),SessionsLayout.STATUS,{},HomeChrome(true,true,false),now,false,{},{},{},{},{})
        }}
        captureRoboImage(File(directory,"home-live-folders-light.png").path) {PinaTheme(dark=false) {
            AgentSessionsContent(panes(),listOf(AgentFixtures.mac),SessionsLayout.FOLDERS,{},HomeChrome(true,true,false),now,false,{},{},{},{},{})
        }}
    }
}
