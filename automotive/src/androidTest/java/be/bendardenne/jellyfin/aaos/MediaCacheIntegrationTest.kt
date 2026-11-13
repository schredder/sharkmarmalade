package be.bendardenne.jellyfin.aaos

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class MediaCacheIntegrationTest {

    private lateinit var context: Context
    private lateinit var cacheDir: File
    private lateinit var cache: SimpleCache
    private lateinit var testBytes: ByteArray

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        cacheDir = File(context.cacheDir, "test_media_cache_${System.currentTimeMillis()}")
        val databaseProvider = StandaloneDatabaseProvider(context)
        cache = SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(10L * 1024 * 1024),
            databaseProvider
        )
        testBytes = ByteArray(8192) { index -> (index % 256).toByte() }
    }

    @After
    fun tearDown() {
        cache.release()
        cacheDir.deleteRecursively()
    }

    @Test
    fun precachesUpcomingTracksIntoSimpleCache() {
        val track2Uri = "https://example.test/track2.mp3"
        val track3Uri = "https://example.test/track3.mp3"

        val item2 = MediaItem.Builder().setMediaId("2").setUri(track2Uri).build()
        val item3 = MediaItem.Builder().setMediaId("3").setUri(track3Uri).build()

        val upstreamFactory = DataSource.Factory { ByteArrayDataSource(testBytes) }
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheKeyFactory { dataSpec -> dataSpec.uri.toString() }

        assertFalse(cache.isCached(track2Uri, 0, testBytes.size.toLong()))
        assertFalse(cache.isCached(track3Uri, 0, testBytes.size.toLong()))

        val upcomingItems = listOf(item2, item3)
        for (item in upcomingItems) {
            val uri = item.localConfiguration?.uri!!
            val writer = CacheWriter(
                cacheDataSourceFactory.createDataSourceForDownloading(),
                DataSpec.Builder().setUri(uri).build(),
                null,
                null
            )
            writer.cache()
        }

        assertTrue(cache.isCached(track2Uri, 0, testBytes.size.toLong()))
        assertTrue(cache.isCached(track3Uri, 0, testBytes.size.toLong()))
    }

    @Test
    fun skipsPreFetchingIfTrackIsAlreadyCached() {
        val trackUri = "https://example.test/already_cached.mp3"
        val item = MediaItem.Builder().setMediaId("cached_1").setUri(trackUri).build()

        val upstreamFactory = DataSource.Factory { ByteArrayDataSource(testBytes) }
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheKeyFactory { dataSpec -> dataSpec.uri.toString() }

        val writer = CacheWriter(
            cacheDataSourceFactory.createDataSourceForDownloading(),
            DataSpec.Builder().setUri(item.localConfiguration?.uri!!).build(),
            null,
            null
        )
        writer.cache()
        assertTrue(cache.isCached(trackUri, 0, testBytes.size.toLong()))

        val isAlreadyCached = cache.getCachedBytes(trackUri, 0, Long.MAX_VALUE) > 0
        assertTrue(isAlreadyCached)
    }

    @Test
    fun replacesPrecachedItemsWhenPlayQueueIsReplaced() {
        val album1TrackUri = "https://example.test/album1_track2.mp3"
        val album2TrackUri = "https://example.test/album2_track2.mp3"

        val album1Item2 = MediaItem.Builder().setMediaId("a1_2").setUri(album1TrackUri).build()
        val album2Item2 = MediaItem.Builder().setMediaId("a2_2").setUri(album2TrackUri).build()

        val upstreamFactory = DataSource.Factory { ByteArrayDataSource(testBytes) }
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheKeyFactory { dataSpec -> dataSpec.uri.toString() }

        CacheWriter(
            cacheDataSourceFactory.createDataSourceForDownloading(),
            DataSpec.Builder().setUri(album1Item2.localConfiguration?.uri!!).build(),
            null,
            null
        ).cache()
        assertTrue(cache.isCached(album1TrackUri, 0, testBytes.size.toLong()))
        assertFalse(cache.isCached(album2TrackUri, 0, testBytes.size.toLong()))

        CacheWriter(
            cacheDataSourceFactory.createDataSourceForDownloading(),
            DataSpec.Builder().setUri(album2Item2.localConfiguration?.uri!!).build(),
            null,
            null
        ).cache()
        assertTrue(cache.isCached(album2TrackUri, 0, testBytes.size.toLong()))
    }
}
