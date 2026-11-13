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
    fun selectsNextTwoItemsNotCurrentlyPlaying() {
        assertWindow(listOf(item("A"), item("B"), item("C"), item("D")), 0, listOf("B", "C"))
    }

    @Test
    fun updatesWindowWhenNavigatingToNextTrack() {
        val items = listOf(item("A"), item("B"), item("C"), item("D"), item("E"))
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = ExoPlayer.Builder(context).build()
            try {
                player.setMediaItems(items)
                player.prepare()
                assertEquals(listOf("B", "C"), getCacheWindow(player).map(MediaItem::mediaId))

                player.seekToNextMediaItem()
                assertEquals(listOf("C", "D"), getCacheWindow(player).map(MediaItem::mediaId))

                player.seekToNextMediaItem()
                assertEquals(listOf("D", "E"), getCacheWindow(player).map(MediaItem::mediaId))
            } finally {
                player.release()
            }
        }
    }

    @Test
    fun updatesWindowWhenNavigatingToPreviousTrack() {
        val items = listOf(item("A"), item("B"), item("C"), item("D"), item("E"))
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = ExoPlayer.Builder(context).build()
            try {
                player.setMediaItems(items)
                player.seekToDefaultPosition(3)
                assertEquals(listOf("E"), getCacheWindow(player).map(MediaItem::mediaId))

                player.seekToPreviousMediaItem()
                assertEquals(listOf("D", "E"), getCacheWindow(player).map(MediaItem::mediaId))

                player.seekToPreviousMediaItem()
                assertEquals(listOf("C", "D"), getCacheWindow(player).map(MediaItem::mediaId))
            } finally {
                player.release()
            }
        }
    }

    @Test
    fun wrapsAtEndWithRepeatAll() {
        assertWindow(
            listOf(item("A"), item("B"), item("C"), item("D")),
            3,
            listOf("A", "B"),
            repeatMode = Player.REPEAT_MODE_ALL
        )
    }

    @Test
    fun followsShuffleOrder() {
        assertWindow(
            listOf(item("A"), item("B"), item("C"), item("D")),
            0,
            listOf("C", "B"),
            shuffleOrder = ShuffleOrder.DefaultShuffleOrder(intArrayOf(0, 2, 1, 3), 0L)
        )
    }

    @Test
    fun survivesRepeatToggle() {
        val items = listOf(item("A"), item("B"), item("C"), item("D"))
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = ExoPlayer.Builder(context).build()
            try {
                player.setMediaItems(items)
                player.seekToDefaultPosition(3)

                // Off -> no upcoming items at end of queue
                player.repeatMode = Player.REPEAT_MODE_OFF
                assertEquals(emptyList<String>(), getCacheWindow(player).map(MediaItem::mediaId))

                // Toggle to Repeat All -> wraps around to first two items
                player.repeatMode = Player.REPEAT_MODE_ALL
                assertEquals(listOf("A", "B"), getCacheWindow(player).map(MediaItem::mediaId))

                // Toggle to Repeat One -> uses next-item navigation semantics (off)
                player.repeatMode = Player.REPEAT_MODE_ONE
                assertEquals(emptyList<String>(), getCacheWindow(player).map(MediaItem::mediaId))
            } finally {
                player.release()
            }
        }
    }

    @Test
    fun survivesShuffleToggle() {
        val items = listOf(item("A"), item("B"), item("C"), item("D"))
        val shuffleOrder = ShuffleOrder.DefaultShuffleOrder(intArrayOf(0, 2, 1, 3), 0L)
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = ExoPlayer.Builder(context).build()
            try {
                player.setMediaItems(items)
                player.setShuffleOrder(shuffleOrder)

                // Shuffle off -> linear order
                player.shuffleModeEnabled = false
                assertEquals(listOf("B", "C"), getCacheWindow(player).map(MediaItem::mediaId))

                // Shuffle on -> custom order A -> C -> B -> D
                player.shuffleModeEnabled = true
                assertEquals(listOf("C", "B"), getCacheWindow(player).map(MediaItem::mediaId))

                // Shuffle back off -> linear order
                player.shuffleModeEnabled = false
                assertEquals(listOf("B", "C"), getCacheWindow(player).map(MediaItem::mediaId))
            } finally {
                player.release()
            }
        }
    }

    @Test
    fun repeatOneUsesNextItemNavigationSemantics() {
        assertWindow(
            listOf(item("A"), item("B"), item("C"), item("D")),
            0,
            listOf("B", "C"),
            repeatMode = Player.REPEAT_MODE_ONE
        )
    }

    @Test
    fun repeatAllWindowDeduplicatesUrisWithinPositionBudget() {
        assertWindow(
            listOf(item("B"), item("A")),
            1,
            listOf("B"),
            repeatMode = Player.REPEAT_MODE_ALL
        )
    }

    @Test
    fun updatesWindowWhenReplacingQueueWithAnotherAlbumOrPlaylist() {
        val album1 = listOf(item("Album1_Track1"), item("Album1_Track2"), item("Album1_Track3"))
        val album2 = listOf(
            item("Album2_Track1"),
            item("Album2_Track2"),
            item("Album2_Track3"),
            item("Album2_Track4")
        )
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = ExoPlayer.Builder(context).build()
            try {
                player.setMediaItems(album1)
                assertEquals(
                    listOf("Album1_Track2", "Album1_Track3"),
                    getCacheWindow(player).map(MediaItem::mediaId)
                )

                player.setMediaItems(album2)
                assertEquals(
                    listOf("Album2_Track2", "Album2_Track3"),
                    getCacheWindow(player).map(MediaItem::mediaId)
                )
            } finally {
                player.release()
            }
        }
    }

    @Test
    fun updatesWindowWhenReplacingQueueMidPlayback() {
        val album1 = listOf(item("Album1_Track1"), item("Album1_Track2"), item("Album1_Track3"))
        val playlist = listOf(
            item("Playlist_Track1"),
            item("Playlist_Track2"),
            item("Playlist_Track3"),
            item("Playlist_Track4")
        )
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = ExoPlayer.Builder(context).build()
            try {
                player.setMediaItems(album1)
                player.seekToDefaultPosition(1)
                assertEquals(
                    listOf("Album1_Track3"),
                    getCacheWindow(player).map(MediaItem::mediaId)
                )

                player.setMediaItems(playlist, 1, 0L)
                assertEquals(
                    listOf("Playlist_Track3", "Playlist_Track4"),
                    getCacheWindow(player).map(MediaItem::mediaId)
                )
            } finally {
                player.release()
            }
        }
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
