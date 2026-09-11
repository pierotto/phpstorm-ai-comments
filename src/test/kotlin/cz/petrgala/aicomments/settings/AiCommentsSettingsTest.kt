package cz.petrgala.aicomments.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AiCommentsSettingsTest : BasePlatformTestCase() {

    fun testDefaults() {
        val state = AiCommentsSettings.getInstance(project).state

        assertTrue(state.showGutterIcons)
        assertTrue(state.autoRefresh)
        assertEquals(500, state.debounceMs)
        assertEquals(500, state.maxCommentLength)
        assertTrue(state.showToolWindow)
        assertFalse(state.hideResolved)
    }

    fun testPublishChangedNotifiesListeners() {
        var calls = 0
        project.messageBus.connect(testRootDisposable).subscribe(AiCommentsSettingsListener.TOPIC, object : AiCommentsSettingsListener {
            override fun settingsChanged() { calls++ }
        })

        AiCommentsSettings.getInstance(project).publishChanged(project)

        assertEquals(1, calls)
    }
}
