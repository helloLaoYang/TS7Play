package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.update.UpdateAvailability
import java.io.File

/** Same-APK launcher integration. The DiPlay receiver remains owned by its existing host. */
object DiPlayLauncherIntegration {
    const val EDGE_GUTTER_DP = 64
    private const val CONNECTION_REQUEST = "com.hilllaoya.ts7play.CONNECTION_TRANSPORT"
    var embedded: Boolean = false
        private set
    var onManualDisconnect: (() -> Unit)? = null
    private var desktopOwner: Any? = null
    val desktopReceiverBusy: Boolean @Synchronized get() = desktopOwner != null

    fun initialize(context: Context) {
        embedded = true
        // Upstream releases have a different application ID and signing identity.
        UpdateAvailability.saveBackgroundChecksEnabled(context, false)
        UpdateAvailability.clear(context)
    }

    @Synchronized fun reserveDesktop(owner: Any): Boolean {
        if (desktopOwner != null || hasSession() || CarPlayScreen.current() != null) return false
        desktopOwner = owner
        return true
    }
    @Synchronized fun releaseDesktop(owner: Any) {
        if (desktopOwner === owner) desktopOwner = null
    }

    fun hasSession(): Boolean = CarPlayBackgroundSession.hasSession()
    fun projectionOpen(): Boolean = CarPlayScreen.current() != null
    fun releaseTouches() { CarPlayBackgroundSession.snapshot()?.controller?.sendTouch(emptyList()) }
    fun projectionIntent(context: Context): Intent = CarPlayScreen.intent(context)
    fun connectionIntent(context: Context, transport: CarPlayTransport): Intent =
        pageIntent(context, "connection").putExtra(CONNECTION_REQUEST, transport.name)

    internal fun consumeConnectionRequest(intent: Intent): CarPlayTransport? {
        if (!embedded) return null
        val value = intent.getStringExtra(CONNECTION_REQUEST)
        intent.removeExtra(CONNECTION_REQUEST)
        return CarPlayTransport.entries.firstOrNull { it.name == value }
    }

    fun autoConnect(context: Context): Boolean = DiPlayPreferences.autoConnect(context)
    fun saveAutoConnect(context: Context, enabled: Boolean) = DiPlayPreferences.saveAutoConnect(context, enabled)
    fun defaultTransport(context: Context): CarPlayTransport =
        if (DiPlayPreferences.autoConnectWireless(context)) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED
    fun phoneSelected(context: Context): Boolean = DiPlayPreferences.phoneAddress(context) != null

    enum class Authentication { MISSING, CONFIGURED, EXTERNAL }
    fun authentication(context: Context): Authentication {
        if (AirPlayPersistence.loadMfiTarget(context) != MfiTarget.LOCAL) return Authentication.EXTERNAL
        val directory = File(context.noBackupFilesDir, "offline-mfi")
        val names = listOf("identity.pk8", "certificate.p7b")
        val installed = names.all { File(directory, it).let { file -> file.isFile && file.length() > 0 } }
        val bundled = runCatching { context.assets.list("offline-mfi").orEmpty().toSet().containsAll(names) }.getOrDefault(false)
        return if (installed || bundled) Authentication.CONFIGURED else Authentication.MISSING
    }

    /** Appearance follows the current system flag; an unknown flag retains the last valid value. */
    fun systemNight(context: Context): Boolean {
        val prefs = context.getSharedPreferences("ts7_system_ui", Context.MODE_PRIVATE)
        return when (context.applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true.also { prefs.edit().putBoolean("last_night", it).apply() }
            Configuration.UI_MODE_NIGHT_NO -> false.also { prefs.edit().putBoolean("last_night", it).apply() }
            else -> prefs.getBoolean("last_night", false)
        }
    }

    fun requestHotspotAccess(context: Context): Boolean =
        CarHotspotSetup.grant(context, listOf(CarHotspotSetup.Permission.HOTSPOT)) ==
            com.shilapi.xcertplay.adb.LocalAdb.Access.READY && CarHotspotSetup.Permission.HOTSPOT.granted(context)

    fun permissionsReady(context: Context, transport: CarPlayTransport): Boolean {
        val permissions = listOf(android.Manifest.permission.RECORD_AUDIO) +
            if (transport == CarPlayTransport.WIRELESS)
                WirelessPermissions.required(AirPlayPersistence.loadWirelessHotspotMode(context), android.os.Build.VERSION.SDK_INT)
            else emptyList()
        return permissions.all { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    }
    fun stop(completion: () -> Unit) {
        val host = CarPlayScreen.current()
        CarPlayBackgroundSession.stop {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (host != null && !host.isDestroyed) host.finish()
                completion()
            }
        }
    }
    fun pageIntent(context: Context, page: String = "home"): Intent {
        require(page in setOf("home", "settings", "connection"))
        return Intent(context, DiPlayActivity::class.java).apply {
            if (page == "home") {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            } else putExtra("page", page)
        }
    }

    fun importDesktopConnection(context: Context, ssid: String, password: String, phone: String?, audioFocus: Boolean) {
        val prefs = context.getSharedPreferences("ts7_diplay_integration", Context.MODE_PRIVATE)
        if (prefs.getBoolean("connection_imported", false)) return
        if (AirPlayPersistence.loadExistingWifiSsid(context).isEmpty() &&
            !AirPlayPersistence.hasSavedWirelessMode(context) && DiPlayPreferences.phoneAddress(context) == null) {
            AirPlayPersistence.saveExistingWifiCredentials(context, ssid, password)
            AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
            AirPlayPersistence.saveWirelessEnabled(context, true)
            AirPlayPersistence.saveAudioFocusEnabled(context, audioFocus)
            if (phone != null && DiPlayPreferences.phoneAddress(context) == null)
                DiPlayPreferences.savePhone(context, phone, "iPhone")
        }
        prefs.edit().putBoolean("connection_imported", true).apply()
    }

    /** Only the plain main display is borrowed; advanced view-area layouts stay in DiPlay. */
    class Projection internal constructor(
        private val controller: CarPlayController,
        private val sink: AndroidMediaSink,
        val width: Int,
        val height: Int,
        val embeddable: Boolean,
    ) {
        fun sameReceiver(other: Projection?): Boolean = other != null && controller === other.controller &&
            width == other.width && height == other.height && embeddable == other.embeddable
        val active: Boolean get() = isCurrent() && CarPlayBackgroundSession.active
        private fun isCurrent() = CarPlayBackgroundSession.snapshot()?.controller === controller && !controller.isClosed()
        fun attach(surface: Surface) {
            if (embeddable && isCurrent()) {
                sink.setSurface(110, surface)
                sink.refreshPicture(110)
            }
        }
        fun detach(surface: Surface): Boolean {
            controller.sendTouch(emptyList())
            return sink.beginSurfaceDetach(surface, parkMain = true).await()
        }
        fun touch(event: MotionEvent, width: Int, height: Int): Boolean =
            embeddable && isCurrent() && controller.sendTouch(CarPlayTouchMapper.contacts(event, width, height))
        fun releaseTouches() { controller.sendTouch(emptyList()) }
    }

    fun projection(): Projection? = CarPlayBackgroundSession.snapshot()?.let { snapshot ->
        if (snapshot.controller.isClosed()) null else Projection(snapshot.controller, snapshot.sink,
            snapshot.display.width, snapshot.display.height,
            snapshot.display.rotation == 0 && snapshot.display.viewAreas == null)
    }
}
