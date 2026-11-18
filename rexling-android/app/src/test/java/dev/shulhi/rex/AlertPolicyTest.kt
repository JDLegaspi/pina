package dev.shulhi.rex

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertPolicyTest {
    private val mac=AgentFixtures.mac
    private fun record(state: String,message: String="",kind: String="",block: String="b")=AgentFixtures.record("s","quiet-meadow",block,state,1,message,kind)

    @Test fun transitionTable() {
        val surfaces=AlertSurface.entries
        fun row(change: RecordChange,state: String,seen: Boolean=false)=surfaces.map {AlertPolicy.decide(change,record(state),it,seen)}
        val none=surfaces.map {AlertDelivery.NONE}
        for(state in listOf("blocked","error","done","working","idle")) {
            assertEquals(none,row(RecordChange.UNCHANGED,state))
            for(change in RecordChange.entries) assertEquals(none,row(change,state,seen=true))
        }
        for(state in listOf("working","idle")) for(change in RecordChange.entries) assertEquals(none,row(change,state))
        val fresh=listOf(AlertDelivery.NOTIFICATION,AlertDelivery.NONE,AlertDelivery.BANNER,AlertDelivery.NONE)
        for(state in listOf("blocked","error","done")) for(change in listOf(RecordChange.NEW,RecordChange.CHANGED)) assertEquals(fresh,row(change,state))
        assertEquals(listOf(AlertDelivery.NOTIFICATION,AlertDelivery.NONE,AlertDelivery.NONE,AlertDelivery.NONE),row(RecordChange.BASELINE,"blocked"))
        assertEquals(listOf(AlertDelivery.NOTIFICATION,AlertDelivery.NONE,AlertDelivery.NONE,AlertDelivery.NONE),row(RecordChange.BASELINE,"error"))
        assertEquals(none,row(RecordChange.BASELINE,"done"))
    }

    @Test fun ledgerBannersOnlyForOtherPanesAfterTheBaseline() {
        val ledger=AttentionLedger()
        val other={_: ActivityRecord->AlertSurface.OTHER_PANE}
        assertEquals(emptyList<AttentionAlert>(),ledger.deliveries(mac,listOf(record("blocked","approve","permission")),other) {false})
        assertEquals(emptyList<AttentionAlert>(),ledger.deliveries(mac,listOf(record("working")),other) {false})
        val done=ledger.deliveries(mac,listOf(record("done").copy(attentionVersion="v9")),other) {false}
        assertEquals(listOf(AlertDelivery.BANNER),done.map {it.delivery})
        assertEquals(emptyList<AttentionAlert>(),ledger.deliveries(mac,listOf(record("done").copy(attentionVersion="v9")),other) {false})
        val mine=ledger.deliveries(mac,listOf(record("blocked","again","permission").copy(attentionVersion="v10")),{AlertSurface.SAME_PANE}) {false}
        assertEquals(emptyList<AttentionAlert>(),mine)
    }

    @Test fun copyNamesTheProgramSessionAndState() {
        val blocked=record("blocked","approve plan","permission")
        assertEquals("Claude Code · quiet-meadow",AlertCopy.title(blocked))
        assertEquals("Android harness for Rex · quiet-meadow",AlertCopy.title(blocked.copy(title="Android harness for Rex")))
        assertEquals("Waiting for permission: approve plan",AlertCopy.text(blocked))
        assertEquals("quiet-meadow · Waiting for permission",AlertCopy.banner(blocked))
        assertEquals("Waiting for your answer",AlertCopy.text(record("blocked",kind="question")))
        assertEquals("Finished",AlertCopy.text(record("done")))
        assertEquals("Finished: Tests pass",AlertCopy.text(record("done","Tests pass")))
        assertEquals("Error: exit 1",AlertCopy.text(record("error","exit 1")))
        assertEquals("Error",AlertCopy.text(record("error")))
    }
}
