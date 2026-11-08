package be.bendardenne.jellyfin.aaos

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CacheQueueWindowTest {
    @Test
    fun selectsCurrentAndNextTwoItems() {
        assertWindow(listOf(item("A"), item("B"), item("C"), item("D")), 0, listOf("A", "B", "C"))
    }

    @Test
    fun stopsAtEndWithoutRepeat() {
        assertWindow(listOf(item("A"), item("B"), item("C"), item("D")), 2, listOf("C", "D"))
    }

    @Test
    fun wrapsAtEndWithRepeatAll() {
        assertWindow(
            listOf(item("A"), item("B"), item("C"), item("D")),
            3,
            listOf("D", "A", "B"),
            repeatMode = Player.REPEAT_MODE_ALL
        )
    }

    @Test
    fun followsShuffleOrder() {
        assertWindow(
            listOf(item("A"), item("B"), item("C"), item("D")),
            0,
            listOf("A", "C", "B"),
            shuffleOrder = ShuffleOrder.DefaultShuffleOrder(intArrayOf(0, 2, 1, 3), 0L)
        )
    }

    @Test
    fun repeatOneUsesNextItemNavigationSemantics() {
        assertWindow(
            listOf(item("A"), item("B"), item("C"), item("D")),
            0,
            listOf("A", "B", "C"),
            repeatMode = Player.REPEAT_MODE_ONE
        )
    }

    @Test
    fun repeatAllWindowDeduplicatesUrisWithinPositionBudget() {
        assertWindow(
            listOf(item("B"), item("A")),
            1,
            listOf("A", "B"),
            repeatMode = Player.REPEAT_MODE_ALL
        )
    }

    @Test
    fun emptyQueueProducesEmptyWindow() {
        assertWindow(emptyList(), 0, emptyList())
    }

    @Test
    fun duplicateUriDoesNotExtendPositionBudget() {
        assertWindow(
            listOf(item("A"), item("A-duplicate", "A"), item("B"), item("C")),
            0,
            listOf("A", "B")
        )
    }

    private fun assertWindow(
        items: List<MediaItem>,
        currentIndex: Int,
        expectedIds: List<String>,
        repeatMode: Int = Player.REPEAT_MODE_OFF,
        shuffleOrder: ShuffleOrder? = null
    ) {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        var actualIds: List<String>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = ExoPlayer.Builder(context).build()
            try {
                player.setMediaItems(items)
                if (shuffleOrder != null) {
                    player.setShuffleOrder(shuffleOrder)
                    player.shuffleModeEnabled = true
                }
                player.repeatMode = repeatMode
                player.seekToDefaultPosition(currentIndex)
                actualIds = getCacheWindow(player).map(MediaItem::mediaId)
            } finally {
                player.release()
            }
        }
        assertEquals(expectedIds, actualIds)
    }

    private fun item(id: String, uriId: String = id): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri("https://example.test/$uriId")
            .build()
}
