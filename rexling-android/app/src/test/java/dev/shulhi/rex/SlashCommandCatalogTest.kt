package dev.shulhi.rex

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SlashCommandCatalogTest {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
    private val preferences=RuntimeEnvironment.getApplication().getSharedPreferences(SlashCommandCatalog.PREFERENCES,Context.MODE_PRIVATE)
    private val target=SlashTarget("mac","https://mac.example","session:a","block:a","~/code/pina")
    private val skill=SlashCommand("ship","Ship it",SlashSource.PROJECT)
    private val other=SlashCommand("land","Land it",SlashSource.USER)

    private class Backend: SkillsBackend {
        var reply=CompletableDeferred<List<SlashCommand>>()
        override suspend fun discover(target: SlashTarget)=reply.await()
    }

    @After fun close() {scope.cancel();preferences.edit().clear().commit()}

    @Test fun firstOpenShowsUpdatingThenTheDiscoveredList() {
        val backend=Backend();val catalog=SlashCommandCatalog(preferences,scope,backend)
        catalog.open(target)
        assertEquals(SlashCatalogState(target,null,SlashRefresh.Updating),catalog.state)
        backend.reply.complete(listOf(skill))
        assertEquals(SlashCatalogState(target,listOf(skill),SlashRefresh.Current),catalog.state)
    }

    @Test fun reopeningShowsTheCachedListWhileItRefreshes() {
        val backend=Backend()
        SlashCommandCatalog(preferences,scope,backend).apply {open(target);backend.reply.complete(listOf(skill))}
        val restarted=SlashCommandCatalog(preferences,scope,backend.apply {reply=CompletableDeferred()})
        restarted.open(target)
        assertEquals(SlashCatalogState(target,listOf(skill),SlashRefresh.Updating),restarted.state)
        backend.reply.complete(listOf(skill,other))
        assertEquals(listOf(skill,other),restarted.state?.discovered)
    }

    @Test fun aFailedRefreshKeepsTheCachedList() {
        val backend=Backend();val catalog=SlashCommandCatalog(preferences,scope,backend)
        catalog.open(target);backend.reply.complete(listOf(skill))
        backend.reply=CompletableDeferred();catalog.open(target)
        backend.reply.completeExceptionally(IllegalStateException("connection timeout"))
        assertEquals(SlashCatalogState(target,listOf(skill),SlashRefresh.Failed("connection timeout")),catalog.state)
    }

    @Test fun anotherDirectoryDoesNotShareTheCache() {
        val backend=Backend();val catalog=SlashCommandCatalog(preferences,scope,backend)
        catalog.open(target);backend.reply.complete(listOf(skill))
        backend.reply=CompletableDeferred()
        val elsewhere=target.copy(directory="~/code/atlas")
        catalog.open(elsewhere)
        assertNull(catalog.state?.discovered)
    }

    @Test fun closingDropsALateResult() {
        val backend=Backend();val catalog=SlashCommandCatalog(preferences,scope,backend)
        catalog.open(target);catalog.close()
        backend.reply.complete(listOf(skill))
        assertNull(catalog.state)
    }
}
