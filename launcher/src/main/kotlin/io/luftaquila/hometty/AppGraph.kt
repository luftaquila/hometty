package io.luftaquila.hometty

import android.app.Application
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.luftaquila.hometty.core.EntryStore
import io.luftaquila.hometty.core.SettingsStore
import io.luftaquila.hometty.core.StateStore
import io.luftaquila.hometty.data.Secrets
import io.luftaquila.hometty.data.Storage
import io.luftaquila.hometty.platform.*
import io.luftaquila.hometty.shell.TerminalNotices
import javax.inject.Inject
import javax.inject.Singleton

@HiltAndroidApp
class HomettyApplication : Application(), PlatformDependencies {
    @Inject override lateinit var storage: Storage
    @Inject override lateinit var notifications: NotificationHub
    @Inject override lateinit var network: NetworkData
    @Inject lateinit var notices: TerminalNotices

    override fun onCreate() {
        super.onCreate()
        // Notifications and feed items print whether or not a screen ever opens in this process.
        notices.start()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppGraph {
    @Provides @Singleton fun storage(@ApplicationContext context: Context) = Storage(context)

    @Provides fun settingsStore(storage: Storage): SettingsStore = storage

    @Provides fun entryStore(storage: Storage): EntryStore = storage

    @Provides fun stateStore(storage: Storage): StateStore = storage

    @Provides @Singleton fun secrets(@ApplicationContext context: Context) = Secrets(context)

    @Provides @Singleton fun device(@ApplicationContext context: Context) = DeviceAccess(context)

    @Provides @Singleton fun notifications() = NotificationHub()

    @Provides @Singleton fun purchases(@ApplicationContext context: Context) = Purchases(context)

    @Provides
    @Singleton
    fun network(settings: SettingsStore, state: StateStore, secrets: Secrets) =
        NetworkData(settings, state, secrets)

    @Provides
    @Singleton
    fun music(@ApplicationContext context: Context) = MusicAccess(context)
}
