package com.diplay.ts7

import android.Manifest
import android.content.Intent
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.android.controller.ServiceController
import java.io.File
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.DiPlayLauncherIntegration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 36], qualifiers = "zh-rCN-w1024dp-h600dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class LauncherTest {
    private lateinit var serviceController: ServiceController<Ts7SessionService>

    @Before fun bindRealIdleService() {
        val app = RuntimeEnvironment.getApplication()
        LauncherStore(app).guideSeen = true
        serviceController = Robolectric.buildService(Ts7SessionService::class.java).create()
        shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(app, Ts7SessionService::class.java), serviceController.get().onBind(Intent()))
        shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(false)
    }

    @After fun destroyService() { serviceController.destroy() }

    @Test fun bothTransportButtonsTargetTheBundledDiPlayActivity() {
        val controller = Robolectric.buildActivity(LauncherActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertEquals("应用", activity.getString(R.string.ts7_apps))
            assertEquals("连接", activity.getString(R.string.ts7_connect))
            text(activity.window.decorView, activity.getString(R.string.ts7_usb_connection)).performClick()
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(ComponentName(activity, DiPlayActivity::class.java), intent.component)
            assertEquals("WIRED", intent.getStringExtra("com.hilllaoya.ts7play.CONNECTION_TRANSPORT"))
            text(activity.window.decorView, activity.getString(R.string.ts7_wifi_connection)).performClick()
            assertEquals("WIRELESS", shadowOf(activity).nextStartedActivity.getStringExtra("com.hilllaoya.ts7play.CONNECTION_TRANSPORT"))
            assertTrue(LauncherStore(activity).diPlayMode)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun originalDiPlayConnectionSettingsRenderInsideThisApk() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            DiPlayLauncherIntegration.pageIntent(context, "settings")).setup()
        try {
            val content = descendants(controller.get().window.decorView).filterIsInstance<TextView>()
                .joinToString("\n") { it.text.toString() }
            assertTrue(content.contains("连接"))
            assertTrue(content.contains("音频"))
            assertTrue(content.contains("显示"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun homeAndBackLeaveTheLauncherAliveAndDoNotStartASession() {
        val controller = Robolectric.buildActivity(LauncherActivity::class.java).setup()
        try {
            val activity = controller.get()
            // Robolectric records the initial bind in the service-intent queue.
            assertEquals(ComponentName(activity, Ts7SessionService::class.java),
                shadowOf(activity).nextStartedService.component)
            controller.newIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            activity.onBackPressed()
            assertFalse(activity.isFinishing)
            assertNull(shadowOf(activity).nextStartedService)
            val ball = activity.window.decorView.findViewWithTag<View>(FloatingLauncher.TAG_ENTRY)
            assertNotNull(ball)
            ball.performClick()
            val menu = ShadowDialog.getLatestDialog()
            assertNotNull(text(menu.window!!.decorView, activity.getString(R.string.ts7_original_home)))
            assertNotNull(text(menu.window!!.decorView, activity.getString(R.string.ts7_system_settings)))
            menu.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun surfaceAspectMatchesOfferedMainStream() {
        assertEquals(800 to 450, CarPlayPanel.fit(800, 600))
        assertEquals(640 to 360, CarPlayPanel.fit(900, 360))
        val panel = CarPlayPanel(RuntimeEnvironment.getApplication())
        panel.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        panel.layout(0, 0, 800, 600)
        assertEquals(800, panel.video.width)
        assertEquals(450, panel.video.height)
        assertEquals(75, panel.video.top)
    }

    @Test fun touchEdgesAndCancellationUseTheActualSurfaceRectangle() {
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 800f, 450f, 0)
        try {
            val down = CarPlayTouchMapper.contacts(event, 800, 450).single()
            assertEquals(1.0, down.x, 0.0)
            assertEquals(1.0, down.y, 0.0)
            assertTrue(down.down)
            event.action = MotionEvent.ACTION_CANCEL
            assertFalse(CarPlayTouchMapper.contacts(event, 800, 450).single().down)
        } finally { event.recycle() }
    }

    @Test fun manualDisconnectSurvivesStoreRecreationAndHomeButNotNewBoot() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 12)
        LauncherStore(app).apply { observeBoot(); suppressed = true; autoConsumed = true }
        LauncherStore(app).apply { observeBoot(); assertTrue(suppressed); assertTrue(autoConsumed) }
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 13)
        LauncherStore(app).apply { observeBoot(); assertFalse(suppressed); assertFalse(autoConsumed) }
    }

    @Test fun permissionRequirementsAreApiGated() {
        val permissions = LauncherStore.requiredPermissions(true)
        assertTrue(permissions.contains(Manifest.permission.RECORD_AUDIO))
        assertEquals(Build.VERSION.SDK_INT >= 31, permissions.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertFalse(LauncherStore.requiredPermissions(false).contains(Manifest.permission.RECORD_AUDIO))
    }

    @Test fun reportOmitsStoredNetworkPhoneAndAuthenticationMaterial() {
        val app = RuntimeEnvironment.getApplication()
        LauncherStore(app).apply { ssid = "PRIVATE_NETWORK"; password = "PRIVATE_PASSWORD"; phone = "PRIVATE_PHONE" }
        val diagnostics = SessionDiagnostics(app)
        diagnostics.record(SessionDiagnostics.Event.NETWORK_CHANGED)
        val report = diagnostics.report(SessionState(SessionPhase.FAILED))
        listOf("PRIVATE_NETWORK", "PRIVATE_PASSWORD", "PRIVATE_PHONE").forEach { assertFalse(report.contains(it)) }
        assertTrue(report.contains("NETWORK_CHANGED"))
        assertTrue(report.contains("api=${Build.VERSION.SDK_INT}"))
    }

    @Test fun canSkipEveryGuideStepWithoutChangingSystemOrStartupSettings() {
        val controller = Robolectric.buildActivity(LauncherActivity::class.java).setup()
        try {
            val activity = controller.get()
            val wasDefaultHome = DeviceProfile.isDefaultHome(activity)
            activity.showPage(LauncherNavigation.GUIDE)
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog()
            repeat(5) { text(dialog.window!!.decorView, activity.getString(R.string.ts7_skip_step)).performClick() }
            val alreadyComplete = if (wasDefaultHome) 1 else 0
            assertTrue(text(dialog.window!!.decorView, activity.getString(R.string.ts7_guide_summary, alreadyComplete, 5 - alreadyComplete)).isShown)
            assertEquals(wasDefaultHome, DeviceProfile.isDefaultHome(activity))
            text(dialog.window!!.decorView, activity.getString(R.string.ts7_enter_home)).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertFalse(LauncherStore(activity).autoConnect)
            assertFalse(DiPlayLauncherIntegration.autoConnect(activity))
            assertFalse(LauncherStore(activity).resumeConnect)
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun exportUsesDocumentPickerWithResultAndNoBroadStoragePermission() {
        val controller = Robolectric.buildActivity(LauncherActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.showPage(LauncherNavigation.SETTINGS)
            clickText(activity.window.decorView, activity.getString(R.string.ts7_export_report))
            val request = shadowOf(activity).nextStartedActivityForResult
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
            assertEquals("text/plain", request.intent.type)
            assertEquals(11, request.requestCode)
        } finally { controller.pause().stop().destroy() }
    }

    @Test @Config(sdk = [35]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun renderSystemThemesLaunchpadAndSkippableGuide() {
        for ((name, width, height) in listOf(Triple("compact-day", 800, 480), Triple("full-dark", 1280, 720),
            Triple("compact-large", 800, 480), Triple("rtl", 1280, 720))) {
            RuntimeEnvironment.setFontScale(if (name == "compact-large") 1.3f else 1f)
            val locale = if (name == "rtl") "ar-rSA-ldrtl-" else "zh-rCN-"
            val night = if (name == "full-dark") "night" else "notnight"
            RuntimeEnvironment.setQualifiers("${locale}w${width}dp-h${height}dp-land-$night-mdpi")
            val controller = Robolectric.buildActivity(LauncherActivity::class.java).setup().visible()
            try {
                val activity = controller.get()
                val direction = if (name == "rtl") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                activity.window.decorView.layoutDirection = direction
                capture(activity.window.decorView, width, height, "$name-home")
                activity.window.decorView.findViewWithTag<View>(FloatingLauncher.TAG_ENTRY).performClick()
                val menu = ShadowDialog.getLatestDialog()
                val dialogView = menu.window!!.decorView
                dialogView.layoutDirection = direction
                capture(dialogView, minOf(width - 48, 800), minOf(height - 48, 340), "$name-launchpad")
                menu.dismiss()
                activity.showPage(LauncherNavigation.SETTINGS)
                capture(activity.window.decorView, width, height, "$name-settings")
                if (name == "compact-day") {
                    activity.showPage(LauncherNavigation.GUIDE)
                    val guide = ShadowDialog.getLatestDialog()
                    repeat(6) { step ->
                        capture(guide.window!!.decorView, width - 48, height - 48, "guide-$step")
                        if (step < 5) text(guide.window!!.decorView, activity.getString(R.string.ts7_skip_step)).performClick()
                    }
                    guide.dismiss()
                }
            } finally { controller.pause().stop().destroy() }
        }
        RuntimeEnvironment.setQualifiers("zh-rCN-w1280dp-h720dp-land-mdpi")
        RuntimeEnvironment.setFontScale(1f)
        val context = RuntimeEnvironment.getApplication()
        DiPlayLauncherIntegration.importDesktopConnection(context, "", "", null, true)
        val diPlay = Robolectric.buildActivity(DiPlayActivity::class.java,
            DiPlayLauncherIntegration.pageIntent(context)).setup().visible()
        try {
            capture(diPlay.get().window.decorView, 1280, 720, "diplay-home")
            diPlay.newIntent(DiPlayLauncherIntegration.pageIntent(context, "settings"))
            capture(diPlay.get().window.decorView, 1280, 720, "diplay-settings")
        } finally { diPlay.pause().stop().destroy() }
    }

    private fun capture(view: View, width: Int, height: Int, name: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/reports/ts7-ui/$name.png")
        requireNotNull(file.parentFile).mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun text(root: View, value: String): TextView =
        descendants(root).filterIsInstance<TextView>().first { it.text.toString() == value }
    private fun clickText(root: View, value: String) {
        var target: View? = text(root, value)
        while (target != null && !target.isClickable) target = target.parent as? View
        assertTrue(requireNotNull(target).performClick())
    }
    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }
}
