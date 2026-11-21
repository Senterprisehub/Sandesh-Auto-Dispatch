package com.example.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.LockCredentialType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Arrays

private val Context.vaultDataStore: DataStore<Preferences> by preferencesDataStore(name = "sandesh_vault")

data class VaultUiSummary(
    val credentialType: LockCredentialType = LockCredentialType.NONE,
    val hasDeviceCredentialSaved: Boolean = false,
    val hasWhatsAppLockPinSaved: Boolean = false,
    val autoRelockAfterSend: Boolean = true,
    val oemShieldAcknowledged: Boolean = false,
    val quickTileAdded: Boolean = false
)

class DecryptedDispatchCredentials(
    val lockType: LockCredentialType,
    val deviceCredential: CharArray?,
    val whatsAppLockPin: CharArray?,
    val autoRelockAfterSend: Boolean
) {
    fun clear() {
        deviceCredential?.let { Arrays.fill(it, '\u0000') }
        whatsAppLockPin?.let { Arrays.fill(it, '\u0000') }
    }
}

class VaultPreferences(
    private val context: Context,
    private val crypto: KeystoreCryptoManager
) {
    private object Keys {
        val LOCK_TYPE = stringPreferencesKey("lock_type")
        val ENCRYPTED_DEVICE_CREDENTIAL = stringPreferencesKey("enc_device_cred")
        val ENCRYPTED_WA_PIN = stringPreferencesKey("enc_wa_pin")
        val AUTO_RELOCK = booleanPreferencesKey("auto_relock")
        val OEM_SHIELD_ACK = booleanPreferencesKey("oem_shield_ack")
        val QUICK_TILE_ADDED = booleanPreferencesKey("quick_tile_added")
    }

    val vaultSummary: Flow<VaultUiSummary> = context.vaultDataStore.data.map { prefs ->
        val type = runCatching {
            LockCredentialType.valueOf(prefs[Keys.LOCK_TYPE] ?: LockCredentialType.NONE.name)
        }.getOrDefault(LockCredentialType.NONE)
        VaultUiSummary(
            credentialType = type,
            hasDeviceCredentialSaved = !prefs[Keys.ENCRYPTED_DEVICE_CREDENTIAL].isNullOrBlank(),
            hasWhatsAppLockPinSaved = !prefs[Keys.ENCRYPTED_WA_PIN].isNullOrBlank(),
            autoRelockAfterSend = prefs[Keys.AUTO_RELOCK] ?: true,
            oemShieldAcknowledged = prefs[Keys.OEM_SHIELD_ACK] ?: false,
            quickTileAdded = prefs[Keys.QUICK_TILE_ADDED] ?: false
        )
    }

    suspend fun saveDeviceCredential(type: LockCredentialType, rawSecret: String) {
        val encrypted = if (type == LockCredentialType.NONE || rawSecret.isBlank()) {
            ""
        } else {
            crypto.encrypt(rawSecret)
        }
        context.vaultDataStore.edit { prefs ->
            prefs[Keys.LOCK_TYPE] = type.name
            prefs[Keys.ENCRYPTED_DEVICE_CREDENTIAL] = encrypted
        }
    }

    suspend fun saveWhatsAppLockPin(rawPin: String) {
        val encrypted = if (rawPin.isBlank()) "" else crypto.encrypt(rawPin)
        context.vaultDataStore.edit { prefs ->
            prefs[Keys.ENCRYPTED_WA_PIN] = encrypted
        }
    }

    suspend fun setAutoRelock(enabled: Boolean) {
        context.vaultDataStore.edit { prefs ->
            prefs[Keys.AUTO_RELOCK] = enabled
        }
    }

    suspend fun setOemShieldAcknowledged(acknowledged: Boolean) {
        context.vaultDataStore.edit { prefs ->
            prefs[Keys.OEM_SHIELD_ACK] = acknowledged
        }
    }

    suspend fun setQuickTileAdded(added: Boolean) {
        context.vaultDataStore.edit { prefs ->
            prefs[Keys.QUICK_TILE_ADDED] = added
        }
    }

    suspend fun clearAllCredentials() {
        context.vaultDataStore.edit { prefs ->
            prefs[Keys.LOCK_TYPE] = LockCredentialType.NONE.name
            prefs[Keys.ENCRYPTED_DEVICE_CREDENTIAL] = ""
            prefs[Keys.ENCRYPTED_WA_PIN] = ""
        }
    }

    suspend fun decryptForDispatch(): DecryptedDispatchCredentials {
        val prefs = context.vaultDataStore.data.first()
        val type = runCatching {
            LockCredentialType.valueOf(prefs[Keys.LOCK_TYPE] ?: LockCredentialType.NONE.name)
        }.getOrDefault(LockCredentialType.NONE)

        val deviceCred = prefs[Keys.ENCRYPTED_DEVICE_CREDENTIAL]
            ?.takeIf { it.isNotBlank() }
            ?.let { crypto.decrypt(it) }

        val waPin = prefs[Keys.ENCRYPTED_WA_PIN]
            ?.takeIf { it.isNotBlank() }
            ?.let { crypto.decrypt(it) }

        return DecryptedDispatchCredentials(
            lockType = type,
            deviceCredential = deviceCred,
            whatsAppLockPin = waPin,
            autoRelockAfterSend = prefs[Keys.AUTO_RELOCK] ?: true
        )
    }
}
