package com.shilapi.xcertplay

import org.junit.After
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25])
class DiPlayLauncherIntegrationTest {
    private val owner = Any()
    @After fun clear() {
        DiPlayLauncherIntegration.releaseDesktop(owner)
        CarPlayBackgroundSession.clear()
        org.robolectric.util.ReflectionHelpers.setStaticField(DiPlayLauncherIntegration::class.java, "embedded", false)
        DiPlayLauncherIntegration.onManualDisconnect = null
    }

    @Test fun desktopReservationCannotBeStolenOrReleasedByAnotherOwner() {
        assertTrue(DiPlayLauncherIntegration.reserveDesktop(owner))
        assertFalse(DiPlayLauncherIntegration.reserveDesktop(Any()))
        DiPlayLauncherIntegration.releaseDesktop(Any())
        assertTrue(DiPlayLauncherIntegration.desktopReceiverBusy)
        DiPlayLauncherIntegration.releaseDesktop(owner)
        assertFalse(DiPlayLauncherIntegration.desktopReceiverBusy)
    }

    @Test fun anExistingDiPlaySessionBlocksAnotherDesktopReceiver() {
        storeSession()
        assertFalse(DiPlayLauncherIntegration.reserveDesktop(owner))
        val projection = requireNotNull(DiPlayLauncherIntegration.projection())
        assertEquals(960, projection.width)
        assertEquals(540, projection.height)
        assertTrue(projection.embeddable)
        CarPlayBackgroundSession.active = true
        assertTrue(projection.active)
        CarPlayBackgroundSession.clear()
        assertFalse(projection.active)
    }

    @Test fun rotatedOutputRemainsInTheOriginalProjectionPage() {
        storeSession(rotation = 90)
        assertFalse(requireNotNull(DiPlayLauncherIntegration.projection()).embeddable)
    }

    @Test fun setupIntentTargetsTheSameApkAndDoesNotRequestAutoConnect() {
        val context = RuntimeEnvironment.getApplication()
        val intent = DiPlayLauncherIntegration.pageIntent(context, "settings")
        assertEquals(context.packageName, intent.component!!.packageName)
        assertEquals(DiPlayActivity::class.java.name, intent.component!!.className)
        assertEquals("settings", intent.getStringExtra("page"))
        assertNull(intent.action)
    }

    @Test fun homeEntryPreservesTheOriginalOptInAutoConnectBehavior() {
        val intent = DiPlayLauncherIntegration.pageIntent(RuntimeEnvironment.getApplication())
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertNull(intent.getStringExtra("page"))
    }

    @Test fun importingLegacySettingsOncePreservesLaterDiPlayEdits() {
        val context = RuntimeEnvironment.getApplication()
        DiPlayLauncherIntegration.importDesktopConnection(context, "First LAN", "test-only-password", null, true)
        assertEquals("First LAN", AirPlayPersistence.loadExistingWifiSsid(context))
        AirPlayPersistence.saveExistingWifiCredentials(context, "Edited LAN", "another-test-password")
        DiPlayLauncherIntegration.importDesktopConnection(context, "First LAN", "test-only-password", null, false)
        assertEquals("Edited LAN", AirPlayPersistence.loadExistingWifiSsid(context))
    }

    @Test fun explicitTransportRequestsAreConsumedOnceAndUseTheSameHost() {
        val context = RuntimeEnvironment.getApplication()
        DiPlayLauncherIntegration.initialize(context)
        for (transport in CarPlayTransport.entries) {
            val intent = DiPlayLauncherIntegration.connectionIntent(context, transport)
            assertEquals(DiPlayActivity::class.java.name, intent.component!!.className)
            assertEquals(transport, DiPlayLauncherIntegration.consumeConnectionRequest(intent))
            assertNull(DiPlayLauncherIntegration.consumeConnectionRequest(intent))
        }
    }

    @Test fun migrationPreservesAnExistingCarHotspotChoice() {
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        DiPlayLauncherIntegration.importDesktopConnection(context, "Old LAN", "test-only-password", null, true)
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertEquals("", AirPlayPersistence.loadExistingWifiSsid(context))
    }

    private fun storeSession(rotation: Int = 0) {
        CarPlayBackgroundSession.store(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java),
            960, 540, owner, CarPlaySessionDisplay(960, 540, rotation, false, false, 960, 540)) { done ->
            CarPlayBackgroundSession.clear()
            done()
        }
    }
}
