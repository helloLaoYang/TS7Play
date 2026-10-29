package com.diplay.ts7

import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 36])
@LooperMode(LooperMode.Mode.PAUSED)
class SessionServiceTest {
    @Test fun firstPictureDeadlineIsSuspendedWhileTheSurfaceIsDetached() {
        val controller = Robolectric.buildService(Ts7SessionService::class.java).create()
        val service = controller.get()
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        try {
            // Represent a peer that has accepted the session but has not rendered its first frame.
            ReflectionHelpers.setField(service, "state", SessionState(SessionPhase.CONNECTED))
            service.attachSurface(surface)
            service.detachSurface(surface)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
            var observed = SessionState()
            service.observe { observed = it }
            assertEquals(SessionPhase.CONNECTED, observed.phase)
            service.attachSurface(surface)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
            assertEquals(SessionPhase.FAILED, observed.phase)
            assertEquals(R.string.failure_picture, observed.message)
        } finally {
            controller.destroy()
            surface.release()
            texture.release()
        }
    }

    @Test fun processRecreationDoesNotRearmAnExhaustedAutomaticEpisode() {
        val context = RuntimeEnvironment.getApplication()
        LauncherStore(context).apply {
            observeBoot()
            autoConnect = true
            autoConsumed = true
            connectionPending = true
            saveBudget(4, 0)
        }
        val controller = Robolectric.buildService(Ts7SessionService::class.java).create()
        try {
            val service = controller.get()
            service.setVisible(true)
            var observed = SessionState()
            service.observe { observed = it }
            assertEquals(SessionPhase.FAILED, observed.phase)
            assertEquals(4, observed.attempts)
            assertFalse(LauncherStore(context).connectionPending)
            assertFalse(service.report().contains("ATTEMPT_STARTED"))
        } finally { controller.destroy() }
    }

    @Test fun rebindingAfterManualDisconnectDoesNotStartAnotherSession() {
        val context = RuntimeEnvironment.getApplication()
        LauncherStore(context).autoConnect = true
        val controller = Robolectric.buildService(Ts7SessionService::class.java).create()
        val service = controller.get()
        try {
            service.disconnect()
            val binder = service.onBind(Intent()) as Ts7SessionService.LocalBinder
            assertSame(service, binder.service)
            service.setVisible(true)
            var observed = SessionState()
            service.observe { observed = it }
            assertEquals(SessionPhase.DISCONNECTED, observed.phase)
            assertEquals(0, observed.attempts)
            assertTrue(LauncherStore(context).suppressed)
        } finally { controller.destroy() }
    }

    @Test fun idleServiceAndSettingsDoNotNeedAuthenticationOrStartTheReceiver() {
        val controller = Robolectric.buildService(Ts7SessionService::class.java).create()
        val service = controller.get()
        try {
            service.setVisible(true)
            service.beginEditing()
            service.endEditing()
            var observed = SessionState()
            service.observe { observed = it }
            assertEquals(SessionPhase.IDLE, observed.phase)
            assertEquals(0, observed.attempts)
            val report = service.report()
            assertFalse(report.contains("ATTEMPT_STARTED"))
            assertFalse(report.contains("AUTHENTICATION_UNAVAILABLE"))
        } finally { controller.destroy() }
    }
}
