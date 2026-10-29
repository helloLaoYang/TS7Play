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
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.android.controller.ServiceController
import java.io.File
import com.shilapi.xcertplay.media.CarPlayTouchMapper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 36], qualifiers = "w1024dp-h600dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class LauncherTest {
    private lateinit var serviceController: ServiceController<Ts7SessionService>

    @Before fun bindRealIdleService() {
        val app = RuntimeEnvironment.getApplication()
        serviceController = Robolectric.buildService(Ts7SessionService::class.java).create()
        shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(app, Ts7SessionService::class.java), serviceController.get().onBind(Intent()))
        shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(false)
    }

    @After fun destroyService() { serviceController.destroy() }

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
            assertNotNull(text(activity.window.decorView, activity.getString(R.string.original_home)))
            assertNotNull(text(activity.window.decorView, activity.getString(R.string.system_settings)))
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

    @Test fun canDisableAutomaticConnectionWithoutValidNetworkConfiguration() {
        val controller = Robolectric.buildActivity(LauncherActivity::class.java).setup()
        try {
            val activity = controller.get()
            text(activity.window.decorView, activity.getString(R.string.settings)).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertFalse(LauncherStore(activity).autoConnect)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun exportUsesDocumentPickerWithResultAndNoBroadStoragePermission() {
        val controller = Robolectric.buildActivity(LauncherActivity::class.java).setup()
        try {
            val activity = controller.get()
            text(activity.window.decorView, activity.getString(R.string.settings)).performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            text(dialog.window!!.decorView, activity.getString(R.string.export_report)).performClick()
            val request = shadowOf(activity).nextStartedActivityForResult
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
            assertEquals("text/plain", request.intent.type)
            assertEquals(11, request.requestCode)
        } finally { controller.pause().stop().destroy() }
    }

    @Test @Config(sdk = [35]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun renderCompactAndFullWithLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        for ((name, width, height) in listOf(Triple("compact", 800, 480), Triple("full", 1280, 720), Triple("rtl", 1280, 720))) {
            val locale = if (name == "rtl") "ar-rSA-ldrtl-" else ""
            RuntimeEnvironment.setQualifiers("${locale}w${width}dp-h${height}dp-land-mdpi")
            val controller = Robolectric.buildActivity(LauncherActivity::class.java).setup().visible()
            try {
                val activity = controller.get()
                val direction = if (name == "rtl") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                activity.window.decorView.layoutDirection = direction
                capture(activity.window.decorView, width, height, "$name-home")
                text(activity.window.decorView, activity.getString(R.string.settings)).performClick()
                val dialogView = ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
                dialogView.layoutDirection = direction
                capture(dialogView, width, height, "$name-settings")
            } finally { controller.pause().stop().destroy() }
        }
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
    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }
}
