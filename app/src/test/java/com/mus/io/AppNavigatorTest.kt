package com.juni.io

import com.juni.io.ui.navigation.AppNavigator
import com.juni.io.ui.navigation.AppRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The app's one back stack, with a list standing in for the view model's
 * release pages.
 *
 * The cases are the ones the old per-page flags got wrong: a page opened from
 * a route has to come back to that route, and a route opened over a page has
 * to come back to that page, however deep the two are interleaved.
 */
class AppNavigatorTest {
    private val releases = mutableListOf<String>()
    private lateinit var navigator: AppNavigator

    @Before
    fun setUp() {
        navigator = AppNavigator(
            detailDepth = { releases.size },
            popDetail = {
                if (releases.isEmpty()) {
                    false
                } else {
                    releases.removeAt(releases.lastIndex)
                    navigator.onDetailStackShrunk(releases.size)
                    true
                }
            },
            clearDetails = {
                releases.clear()
                navigator.onDetailStackShrunk(0)
            },
        )
    }

    @Test
    fun backUnwindsAPlaylistOpenedFromReplayUnderSettings() {
        navigator.push(AppRoute.Settings)
        navigator.push(AppRoute.AccountScrobbling)
        navigator.push(AppRoute.Replay)
        releases += "local:playlist"
        releases += "artist"
        assertNull(navigator.visibleEntry())

        assertTrue(navigator.pop())
        assertEquals(listOf("local:playlist"), releases)
        assertNull(navigator.visibleRoute())
        assertTrue(navigator.pop())
        assertEquals(AppRoute.Replay, navigator.visibleRoute())
        assertTrue(navigator.pop())
        assertEquals(AppRoute.AccountScrobbling, navigator.visibleRoute())
        assertTrue(navigator.pop())
        assertEquals(AppRoute.Settings, navigator.visibleRoute())
        assertTrue(navigator.pop())
        assertNull(navigator.visibleRoute())
        assertFalse(navigator.pop())
    }

    @Test
    fun settingsOverAReleaseComesBackToIt() {
        releases += "album"
        navigator.push(AppRoute.Settings)
        assertEquals(AppRoute.Settings, navigator.visibleRoute())
        // Already on screen: a second tap on the account button stays put.
        navigator.push(AppRoute.Settings)
        assertEquals(1, navigator.entries.size)

        assertTrue(navigator.pop())
        assertNull(navigator.visibleRoute())
        assertEquals(listOf("album"), releases)
        assertTrue(navigator.pop())
        assertTrue(releases.isEmpty())
    }

    @Test
    fun aReleaseRemovedUnderARouteDoesNotHideTheNextOne() {
        releases += "playlist"
        navigator.push(AppRoute.History)
        // Deleted from under the route, not popped.
        releases.clear()
        navigator.onDetailStackShrunk(0)

        releases += "album"
        assertNull(navigator.visibleRoute())
        assertTrue(navigator.pop())
        assertEquals(AppRoute.History, navigator.visibleRoute())
    }

    @Test
    fun pushAllStartsFromWhicheverRouteIsShowing() {
        navigator.push(AppRoute.Settings)
        navigator.pushAll(AppRoute.Settings, AppRoute.Equalizer)
        assertEquals(listOf(AppRoute.Settings, AppRoute.Equalizer), navigator.entries.map { it.route })
        navigator.pushAll(AppRoute.Settings, AppRoute.Equalizer)
        assertEquals(2, navigator.entries.size)
    }

    @Test
    fun clearAndResetLeaveNothingBehind() {
        navigator.push(AppRoute.Replay)
        releases += "local:playlist"
        navigator.clear()
        assertTrue(navigator.entries.isEmpty())
        assertTrue(releases.isEmpty())
        assertFalse(navigator.pop())

        releases += "album"
        navigator.reset(AppRoute.Settings, AppRoute.Equalizer)
        assertTrue(releases.isEmpty())
        assertEquals(AppRoute.Equalizer, navigator.visibleRoute())
    }
}
