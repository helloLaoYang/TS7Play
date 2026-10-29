package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.view.MotionEvent
import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.update.UpdateAvailability

/** Same-APK launcher integration. The DiPlay receiver remains owned by its existing host. */
object DiPlayLauncherIntegration {
    var embedded: Boolean = false
        private set
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
    fun stop(completion: () -> Unit) = CarPlayBackgroundSession.stop(completion)
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
        if (AirPlayPersistence.loadExistingWifiSsid(context).isEmpty()) {
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
