package com.shilapi.xcertplay

import android.content.res.Configuration
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowToast
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29])
@LooperMode(LooperMode.Mode.PAUSED)
class DiPlayLauncherTransportTest {
    @After fun reset() {
        ReflectionHelpers.setStaticField(DiPlayLauncherIntegration::class.java, "embedded", false)
        DiPlayLauncherIntegration.onManualDisconnect = null
        CarPlayBackgroundSession.clear()
    }

    @Test fun usbAndWifiRequestsSelectTheActualReceiverTransportWithoutChangingLanMode() {
        val app = RuntimeEnvironment.getApplication()
        DiPlayLauncherIntegration.initialize(app)
        ReflectionHelpers.setStaticField(DiPlayBootstrap::class.java, "ready", false)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.EXISTING_WIFI)
        for (transport in CarPlayTransport.entries) {
            val before = ShadowToast.shownToastCount()
            val activity = Robolectric.buildActivity(DiPlayActivity::class.java,
                DiPlayLauncherIntegration.connectionIntent(app, transport)).setup()
            try {
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(transport == CarPlayTransport.WIRELESS, AirPlayPersistence.loadWirelessEnabled(app))
                assertEquals(WirelessHotspotMode.EXISTING_WIFI, AirPlayPersistence.loadWirelessHotspotMode(app))
                // Missing source-build authentication must not be interpreted as a connection.
                assertFalse(CarPlayBackgroundSession.active)
                assertEquals(before + 1, ShadowToast.shownToastCount())
                activity.recreate()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(before + 1, ShadowToast.shownToastCount())
            } finally { activity.pause().stop().destroy() }
        }
    }

    @Test fun systemThemeIgnoresTheLocalizedActivityOverrideAndRetainsTheLastKnownValue() {
        val app = RuntimeEnvironment.getApplication()
        fun systemMode(mode: Int) {
            val config = Configuration(app.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
            }
            app.resources.updateConfiguration(config, app.resources.displayMetrics)
        }
        systemMode(Configuration.UI_MODE_NIGHT_YES)
        assertTrue(DiPlayLauncherIntegration.systemNight(app))
        systemMode(Configuration.UI_MODE_NIGHT_UNDEFINED)
        assertTrue(DiPlayLauncherIntegration.systemNight(app))
        systemMode(Configuration.UI_MODE_NIGHT_NO)
        val localized = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        })
        assertFalse(DiPlayLauncherIntegration.systemNight(localized))
    }
}
