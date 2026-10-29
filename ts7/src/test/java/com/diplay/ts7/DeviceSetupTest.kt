package com.diplay.ts7

import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.DiPlayLauncherIntegration
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 36], qualifiers = "zh-rCN-w1024dp-h600dp-land-notnight-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DeviceSetupTest {
    private lateinit var service: ServiceController<Ts7SessionService>
    @Before fun prepare() {
        val app = RuntimeEnvironment.getApplication()
        LauncherStore(app).guideSeen = true
        service = Robolectric.buildService(Ts7SessionService::class.java).create()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, Ts7SessionService::class.java), service.get().onBind(Intent()))
        shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(false)
    }
    @After fun finish() { service.destroy() }

    @Test fun skippingStartupDiscardsUnappliedToggleChanges() {
        val app = RuntimeEnvironment.getApplication()
        LauncherStore(app).guideStep = 3
        val controller = Robolectric.buildActivity(LauncherActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.showPage(LauncherNavigation.GUIDE)
            val dialog = ShadowDialog.getLatestDialog()
            val toggle = descendants(dialog.window!!.decorView).filterIsInstance<Switch>()
                .first { it.text.toString() == activity.getString(R.string.ts7_auto_connect) }
            toggle.performClick()
            assertTrue(toggle.isChecked)
            assertFalse(DiPlayLauncherIntegration.autoConnect(activity))
            text(dialog.window!!.decorView, activity.getString(R.string.ts7_skip_step)).performClick()
            assertFalse(DiPlayLauncherIntegration.autoConnect(activity))
            assertEquals("skipped", LauncherStore(activity).stepResult(3))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun deviceDetectionAndSameLanHotspotControlsDoNotChangeNetworking() {
        val controller = Robolectric.buildActivity(LauncherActivity::class.java).setup()
        var controls: HotspotControls? = null
        try {
            val activity = controller.get()
            AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.EXISTING_WIFI)
            val home = DeviceProfile.defaultHome(activity)
            val facts = DeviceProfile.collect(activity)
            assertTrue(facts.any { it.label == R.string.ts7_usb_connection })
            assertTrue(facts.any { it.label == R.string.ts7_hotspot })
            assertEquals(home, DeviceProfile.defaultHome(activity))
            assertFalse(CarHotspotSettings.enabled(activity))
            controls = HotspotControls(activity) { true }.also { it.show() }
            val button = text(ShadowDialog.getLatestDialog().window!!.decorView,
                activity.getString(R.string.ts7_hotspot_enable))
            assertFalse(button.isEnabled)
            button.performClick()
            assertFalse(CarHotspotSettings.enabled(activity))
            assertEquals(WirelessHotspotMode.EXISTING_WIFI, AirPlayPersistence.loadWirelessHotspotMode(activity))
        } finally { controls?.close(); controller.pause().stop().destroy() }
    }

    @Test fun systemThemeRefreshKeepsTheSameVideoSurfaceAndDoesNotConnect() {
        val controller = Robolectric.buildActivity(LauncherActivity::class.java).setup()
        try {
            val activity = controller.get()
            val video = descendants(activity.window.decorView).filterIsInstance<SurfaceView>().single()
            RuntimeEnvironment.setQualifiers("+night")
            activity.onConfigurationChanged(Configuration(activity.applicationContext.resources.configuration))
            assertTrue(LauncherUi(activity).night)
            assertSame(video, descendants(activity.window.decorView).filterIsInstance<SurfaceView>().single())
            assertNull(shadowOf(activity).nextStartedActivity)
            assertFalse(service.get().report().contains("ATTEMPT_STARTED"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun floatingEntryAndMenuDisappearWhenTheActivityLeaves() {
        val controller = Robolectric.buildActivity(LauncherActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val entry = activity.window.decorView.findViewWithTag<View>(FloatingLauncher.TAG_ENTRY)
            assertNotNull(entry)
            entry.performClick()
            val menu = ShadowDialog.getLatestDialog()
            assertTrue(menu.isShowing)
            controller.pause()
            assertFalse(menu.isShowing)
            assertNull(activity.window.decorView.findViewWithTag<View>(FloatingLauncher.TAG_ENTRY))
            controller.resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, descendants(activity.window.decorView).count { it.tag == FloatingLauncher.TAG_ENTRY })
        } finally { controller.pause().stop().destroy() }
    }

    private fun text(root: View, value: String): Button = descendants(root).filterIsInstance<Button>().first { it.text.toString() == value }
    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }
}
