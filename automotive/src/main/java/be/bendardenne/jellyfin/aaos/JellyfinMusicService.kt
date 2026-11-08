package be.bendardenne.jellyfin.aaos

import android.accounts.AccountManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.preference.PreferenceManager
import be.bendardenne.jellyfin.aaos.JellyfinMediaLibrarySessionCallback.Companion.PLAYLIST_INDEX_PREF
import be.bendardenne.jellyfin.aaos.JellyfinMediaLibrarySessionCallback.Companion.PLAYLIST_TRACK_POSITON_MS_PREF
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.ROOT_ID
import be.bendardenne.jellyfin.aaos.SharkMarmaladeConstants.LOG_MARKER
import dagger.hilt.android.AndroidEntryPoint
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.serializer.toUUID
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.inject.Inject

// Cache the current song plus up to two upcoming songs.
private const val CACHE_QUEUE_WINDOW_SIZE = 3

@AndroidEntryPoint
@OptIn(UnstableApi::class)
class JellyfinMusicService : MediaLibraryService() {

    @Inject
    lateinit var jellyfin: Jellyfin

    @Inject
    lateinit var cache: SimpleCache

    private lateinit var accountManager: JellyfinAccountManager
    private lateinit var jellyfinApi: ApiClient
    private lateinit var cacheDataSourceFactory: CacheDataSource.Factory
    private lateinit var mediaSourceFactory: DefaultMediaSourceFactory
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private lateinit var callback: JellyfinMediaLibrarySessionCallback

    private val handler: Handler = Handler(Looper.getMainLooper())
    private val cacheExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var activeCacheWriter: CacheWriter? = null
    private var cacheWindowFuture: Future<*>? = null
    private var currentPlaybackTime: Long = 0;
    private var currentTrack: MediaItem? = null;

    private lateinit var playbackPoll: Runnable;


    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
                events.contains(Player.EVENT_REPEAT_MODE_CHANGED)
            ) {
                cacheQueueWindow(player)
            }

            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                // Persist the current index of the queue in the preferences.
                // This is restored in onPlaybackResumption
                PreferenceManager.getDefaultSharedPreferences(this@JellyfinMusicService).edit {
                    putInt(PLAYLIST_INDEX_PREF, player.currentMediaItemIndex)
                }

                SuspendToFutureAdapter.launchFuture { reportPlayback(player) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        Log.i(LOG_MARKER, "onCreate")

        accountManager = JellyfinAccountManager(AccountManager.get(applicationContext))
        jellyfinApi = jellyfin.createApi()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setCacheKeyFactory { dataSpec -> dataSpec.uri.toString() }

        mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(cacheDataSourceFactory)

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        player.addListener(playerListener)

        // Start in no repeat & no shuffle by default
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = false
        // TODO  double check if we can't get events for this
        // https://proandroiddev.com/mastering-playback-state-with-exo-player-977016aa5003
        pollForPlaybackStatus(player)

        callback = JellyfinMediaLibrarySessionCallback(this, accountManager, jellyfinApi)

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, callback)
            .setMediaButtonPreferences(CommandButtons.createButtons(player))
            .build()

        if (accountManager.isAuthenticated) {
            onLogin()
        }
    }

    private fun pollForPlaybackStatus(player: ExoPlayer) {
        // Repeatedly poll the player for current elapsed playback time
        // We need this to report elapsed time on playback stop, which is needed for scrobbling.
        playbackPoll = Runnable {
            if (player.isPlaying) {
                currentPlaybackTime = player.currentPosition
                currentTrack = player.currentMediaItem

                PreferenceManager.getDefaultSharedPreferences(this@JellyfinMusicService).edit {
                    putLong(PLAYLIST_TRACK_POSITON_MS_PREF, currentPlaybackTime)
                }
            }

            handler.postDelayed(playbackPoll, 1000)
        }
        handler.postDelayed(playbackPoll, 1000)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        Log.i(LOG_MARKER, "onDestroy")

        val player = mediaLibrarySession.player
        player.removeListener(playerListener)
        cancelQueueCaching()
        cacheExecutor.shutdownNow()
        mediaLibrarySession.release()
        player.release()
        handler.removeCallbacks(playbackPoll)
        super.onDestroy()
    }

    fun onLogout() {
        // Remove the account first so browsers refetching afterwards see the signed-out state.
        accountManager.deleteAccount()

        val player = mediaLibrarySession.player
        player.stop()
        player.clearMediaItems()
        currentTrack = null
        currentPlaybackTime = 0

        // Drop the persisted queue so it is not restored for the next user.
        PreferenceManager.getDefaultSharedPreferences(this).edit {
            remove(JellyfinMediaLibrarySessionCallback.PLAYLIST_IDS_PREF)
            remove(PLAYLIST_INDEX_PREF)
            remove(PLAYLIST_TRACK_POSITON_MS_PREF)
        }
    }

    fun onLogin() {
        val headers = jellyfinApi.auth(accountManager)

        val authedHttpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
        cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(authedHttpFactory)
            .setCacheKeyFactory { dataSpec -> dataSpec.uri.toString() }

        mediaSourceFactory.setDataSourceFactory(cacheDataSourceFactory)

        // Trigger a refresh upon login.
        mediaLibrarySession.notifyChildrenChanged(ROOT_ID, 4, null)
    }

    private fun cacheQueueWindow(player: Player) {
        cancelQueueCaching()

        val mediaItems = getCacheWindow(player)
        if (mediaItems.isEmpty()) return
        val dataSourceFactory = cacheDataSourceFactory

        cacheWindowFuture = cacheExecutor.submit {
            for (mediaItem in mediaItems) {
                if (Thread.currentThread().isInterrupted) break

                val uri = mediaItem.localConfiguration?.uri ?: continue
                val writer = CacheWriter(
                    dataSourceFactory.createDataSourceForDownloading(),
                    DataSpec.Builder().setUri(uri).build(),
                    null,
                    null
                )
                activeCacheWriter = writer

                try {
                    if (Thread.currentThread().isInterrupted) {
                        writer.cancel()
                        break
                    }
                    writer.cache()
                    Log.i(LOG_MARKER, "Finished caching track ${mediaItem.mediaId}")
                } catch (e: IOException) {
                    Log.w(LOG_MARKER, "Unable to cache track ${mediaItem.mediaId}", e)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.d(LOG_MARKER, "Caching track ${mediaItem.mediaId} was cancelled")
                    break
                } finally {
                    if (activeCacheWriter === writer) {
                        activeCacheWriter = null
                    }
                }
            }
        }
    }

    private fun cancelQueueCaching() {
        activeCacheWriter?.cancel()
        activeCacheWriter = null
        cacheWindowFuture?.cancel(true)
        cacheWindowFuture = null
    }

    private suspend fun reportPlayback(player: Player) {
        val exoPlayer = player as ExoPlayer
        // MediaItem has changed; if there was a previous item playing, mark it stopped.
        if (currentTrack != null) {
            Log.i(LOG_MARKER, "Reporting playback stopped: ${currentPlaybackTime}")
            jellyfinApi.playStateApi.onPlaybackStopped(
                currentTrack!!.mediaId.toUUID(),
                positionTicks = 10000 * currentPlaybackTime
            )
        }

        if (player.currentMediaItem != null) {
            val format = exoPlayer.audioFormat
            val formatString = "${format?.containerMimeType} at ${format?.averageBitrate} bps"

            Log.i(
                LOG_MARKER,
                "Playing $formatString: ${exoPlayer.currentMediaItem?.localConfiguration?.uri}"
            )
            jellyfinApi.playStateApi.onPlaybackStart(
                player.currentMediaItem!!.mediaId.toUUID(),
                canSeek = true
            )
        }
    }
}

@OptIn(UnstableApi::class)
internal fun getCacheWindow(player: Player): List<MediaItem> {
    val timeline = player.currentTimeline
    val currentIndex = player.currentMediaItemIndex
    if (timeline.isEmpty || currentIndex !in 0 until timeline.windowCount) return emptyList()

    val repeatMode = if (player.repeatMode == Player.REPEAT_MODE_ONE) {
        Player.REPEAT_MODE_OFF
    } else {
        player.repeatMode
    }
    val shuffleModeEnabled = player.shuffleModeEnabled
    val window = Timeline.Window()
    val mediaItems = mutableListOf<MediaItem>()
    val seenUris = mutableSetOf<String>()
    var itemIndex = currentIndex

    repeat(CACHE_QUEUE_WINDOW_SIZE) {
        if (itemIndex !in 0 until timeline.windowCount) return mediaItems

        val mediaItem = timeline.getWindow(itemIndex, window).mediaItem
        val uri = mediaItem.localConfiguration?.uri
        if (uri != null && seenUris.add(uri.toString())) {
            mediaItems += mediaItem
        }

        itemIndex = timeline.getNextWindowIndex(itemIndex, repeatMode, shuffleModeEnabled)
    }

    return mediaItems
}
