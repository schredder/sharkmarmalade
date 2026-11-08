package be.bendardenne.jellyfin.aaos

import android.accounts.AccountManager
import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.android.androidDevice
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo

@Module
@InstallIn(SingletonComponent::class)
class JellyfinHiltModule {

    @Provides
    @Singleton
    fun provideMediaCache(@ApplicationContext appContext: Context): SimpleCache {
        return SimpleCache(
            File(appContext.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(1024L * 1024 * 1024),
            StandaloneDatabaseProvider(appContext)
        )
    }

    @Provides
    fun provideJellyfin(@ApplicationContext appContext: Context): Jellyfin {
        val version =
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName

        return createJellyfin {
            clientInfo = ClientInfo(appContext.getString(R.string.app_name), version ?: "unknown")
            deviceInfo = androidDevice(appContext)
            context = appContext
        }
    }

    @Provides
    fun provideAccountManager(@ApplicationContext appContext: Context): JellyfinAccountManager {
        return JellyfinAccountManager(AccountManager.get(appContext))
    }
}

