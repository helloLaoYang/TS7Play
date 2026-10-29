package com.diplay.ts7

import android.content.Context
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.io.File

internal class AuthenticationUnavailableException(cause: Exception) : Exception("Runtime authentication unavailable", cause)

internal class ReceiverEngine(
    val controller: CarPlayController,
    private val sink: AndroidMediaSink,
) {
    fun attach(surface: Surface) {
        sink.setSurface(MAIN_STREAM, surface)
        sink.refreshPicture(MAIN_STREAM)
    }
    fun detach(surface: Surface): Boolean = sink.beginSurfaceDetach(surface, parkMain = true).await()
    fun close(): Boolean {
        controller.sendTouch(emptyList())
        controller.attachUi(object : AirPlaySessionListener { override val wantsTrace = false }, {})
        controller.close()
        val stopped = controller.awaitClosed(10_000)
        sink.close()
        return stopped
    }

    companion object {
        const val WIDTH = 960
        const val HEIGHT = 540
        const val MAIN_STREAM = 110

        /** Called off the UI thread. The service is the only owner of this engine. */
        fun create(context: Context, store: LauncherStore, listener: AirPlaySessionListener,
            status: (CarPlayStatus) -> Unit): ReceiverEngine {
            try { installAuthentication(context) }
            catch (error: Exception) { throw AuthenticationUnavailableException(error) }
            val identity = store.identity()
            val deviceId = LauncherStore.deviceId(identity)
            val config = CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL,
                transport = CarPlayTransport.WIRELESS,
                wirelessHotspotMode = WirelessHotspotMode.EXISTING_WIFI,
                existingWifiSsid = store.ssid,
                existingWifiPassphrase = store.password,
                wirelessBluetoothDeviceAddress = store.phone,
                label = "TS7 CarPlay",
                hostName = "ts7-" + deviceId.replace(":", "").lowercase(),
                hostMac = deviceId.split(":").map { it.toInt(16).toByte() }.toByteArray(),
                identification = Iap2IdentificationConfig(
                    name = "TS7 CarPlay", modelIdentifier = "TS7", manufacturer = "DiPlay",
                    serialNumber = "TS7-" + deviceId.replace(":", ""), firmwareVersion = BuildConfig.VERSION_NAME,
                    hardwareVersion = "1.0", carPlayUsbInterfaceNumber = 3,
                ),
            )
            val sink = AndroidMediaSink(videoWidth = WIDTH, videoHeight = HEIGHT,
                audioFocusEnabled = store.audioFocus, audioFocusAutoYield = true,
                context = context.applicationContext, mainVideoFrameRate = 30)
            try {
                val controller = CarPlayController(context.applicationContext, config,
                    AirPlayConfig(deviceName = "TS7 CarPlay", deviceId = deviceId, btMac = deviceId,
                        sourceVersion = "950.7.1", main = AirPlayDisplayConfig(WIDTH, HEIGHT, fps = 30),
                        microphone = store.microphone, videoInCar = false, hevc = false),
                    identity, store.pairings(), listener,
                    CarPlayMediaEngine(sink, microphoneEnabled = store.microphone), status)
                return ReceiverEngine(controller, sink)
            } catch (error: Exception) {
                sink.close()
                throw error
            }
        }

        private fun installAuthentication(context: Context) {
            val directory = File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
            if (!directory.exists()) {
                val staging = File(context.noBackupFilesDir, "ts7-auth-staging")
                staging.deleteRecursively()
                check(staging.mkdirs())
                try {
                    listOf("identity.pk8", "certificate.p7b").forEach { name ->
                        val target = File(staging, name)
                        context.assets.open("offline-mfi/$name").use { input ->
                            target.outputStream().use { input.copyTo(it) }
                        }
                        target.setReadable(false, false)
                        target.setReadable(true, true)
                        target.setWritable(false, false)
                        target.setWritable(true, true)
                    }
                    LocalMfiAuthenticationClient.load(staging)
                    check(staging.renameTo(directory))
                } finally {
                    staging.deleteRecursively()
                }
            }
            LocalMfiAuthenticationClient.load(directory)
        }
    }
}
