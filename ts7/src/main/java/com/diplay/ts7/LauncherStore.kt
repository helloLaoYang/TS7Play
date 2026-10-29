package com.diplay.ts7

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import java.security.MessageDigest

internal class LauncherStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("ts7_launcher_v1", Context.MODE_PRIVATE)
    var ssid: String
        get() = prefs.getString("ssid", "").orEmpty()
        set(value) { prefs.edit().putString("ssid", value).apply() }
    var password: String
        get() = prefs.getString("password", "").orEmpty()
        set(value) { prefs.edit().putString("password", value).apply() }
    var phone: String?
        get() = prefs.getString("phone", null)
        set(value) { prefs.edit().putString("phone", value).apply() }
    var originalHome: String?
        get() = prefs.getString("original_home", null)
        set(value) { prefs.edit().putString("original_home", value).apply() }
    var autoConnect: Boolean
        get() = prefs.getBoolean("auto_connect", false)
        set(value) { prefs.edit().putBoolean("auto_connect", value).apply() }
    var resumeConnect: Boolean
        get() = prefs.getBoolean("resume_connect", false)
        set(value) { prefs.edit().putBoolean("resume_connect", value).apply() }
    var microphone: Boolean
        get() = prefs.getBoolean("microphone", true)
        set(value) { prefs.edit().putBoolean("microphone", value).apply() }
    var audioFocus: Boolean
        get() = prefs.getBoolean("audio_focus", true)
        set(value) { prefs.edit().putBoolean("audio_focus", value).apply() }
    var suppressed: Boolean
        get() = prefs.getBoolean("suppressed", false)
        set(value) { prefs.edit().putBoolean("suppressed", value).apply() }
    var autoConsumed: Boolean
        get() = prefs.getBoolean("auto_consumed", false)
        set(value) { prefs.edit().putBoolean("auto_consumed", value).apply() }
    var connectionPending: Boolean
        get() = prefs.getBoolean("connection_pending", false)
        set(value) { prefs.edit().putBoolean("connection_pending", value).apply() }
    val attempts: Int get() = prefs.getInt("attempts", 0)
    val retryAt: Long get() = prefs.getLong("retry_at", 0)

    fun saveBudget(attempts: Int, retryAt: Long) {
        // Commit before starting a receiver so a process crash cannot replenish the retry budget.
        check(prefs.edit().putInt("attempts", attempts).putLong("retry_at", retryAt).commit()) {
            "Could not persist the connection budget"
        }
    }

    fun shortcut(slot: Int) = prefs.getString("shortcut_$slot", null)
    fun saveShortcut(slot: Int, component: String) {
        prefs.edit().putString("shortcut_$slot", component).apply()
    }

    fun observeBoot() {
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (boot >= 0 && prefs.getInt("boot", -2) != boot) {
            newStartup()
            prefs.edit().putInt("boot", boot).apply()
        }
    }

    fun newStartup() {
        prefs.edit().putBoolean("suppressed", false).putBoolean("auto_consumed", false)
            .putBoolean("connection_pending", false).putInt("attempts", 0).putLong("retry_at", 0).apply()
    }

    fun identity(): AirPlayIdentity {
        val privateKey = prefs.getString("identity_private", null)
        val publicKey = prefs.getString("identity_public", null)
        val pairingId = prefs.getString("identity_id", null)
        if (privateKey != null && publicKey != null && pairingId != null) {
            return AirPlayIdentity(decode(privateKey), decode(publicKey), pairingId)
        }
        return AirPlayIdentity.generate().also {
            check(prefs.edit().putString("identity_private", encode(it.privateKey))
                .putString("identity_public", encode(it.publicKey)).putString("identity_id", it.pairingId)
                .commit()) { "Could not persist accessory identity" }
        }
    }

    fun pairings(): PairingStore {
        val storage = context.getSharedPreferences("ts7_pairings_v1", Context.MODE_PRIVATE)
        val result = PairingStore { identifier, key ->
            storage.edit().putString(identifier, encode(key)).apply()
        }
        storage.all.forEach { (identifier, key) ->
            if (key is String) result.save(identifier, decode(key))
        }
        return result
    }

    companion object {
        fun deviceId(identity: AirPlayIdentity): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).copyOf(6)
            bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
            return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
        }
        private fun encode(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
        private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)

        fun requiredPermissions(microphone: Boolean): List<String> = buildList {
            if (microphone) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        fun missingPermissions(context: Context, microphone: Boolean) =
            requiredPermissions(microphone).filter {
                context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
    }
}
