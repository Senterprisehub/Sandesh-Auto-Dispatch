package com.example

import android.app.Application
import com.example.data.local.SandeshDatabase
import com.example.data.media.MediaStorageManager
import com.example.data.repository.SandeshRepository
import com.example.dispatch.NotificationHelper
import com.example.security.KeystoreCryptoManager
import com.example.security.VaultPreferences

class AppContainer(application: Application) {
    val database: SandeshDatabase by lazy { SandeshDatabase.getInstance(application) }
    val mediaStorage: MediaStorageManager by lazy { MediaStorageManager(application) }
    val repository: SandeshRepository by lazy {
        SandeshRepository(
            scheduleDao = database.scheduledMessageDao(),
            templateDao = database.messageTemplateDao(),
            logDao = database.dispatchLogDao(),
            mediaStorage = mediaStorage
        )
    }
    val cryptoManager: KeystoreCryptoManager by lazy { KeystoreCryptoManager() }
    val vaultPreferences: VaultPreferences by lazy { VaultPreferences(application, cryptoManager) }
}

class SandeshApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationHelper.ensureChannels(this)
    }
}
