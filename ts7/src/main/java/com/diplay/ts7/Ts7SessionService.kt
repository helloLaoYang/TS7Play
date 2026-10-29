package com.diplay.ts7

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import com.diplay.ts7.SessionDiagnostics.Event
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.AppLocale
import com.shilapi.xcertplay.DiPlayLauncherIntegration
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.network.WirelessStartupFailure
import java.util.concurrent.Executors

internal enum class SessionPhase { IDLE, WAITING_NETWORK, WAITING_PHONE, CONNECTING, CONNECTED, FAILED, DISCONNECTED }
internal data class SessionState(val phase: SessionPhase = SessionPhase.IDLE,
    val message: Int = 0, val attempts: Int = 0, val retrying: Boolean = false,
    val pictureAvailable: Boolean = true, val videoWidth: Int = ReceiverEngine.WIDTH,
    val videoHeight: Int = ReceiverEngine.HEIGHT)

/** Owns connection, media and callbacks independently of any Activity or Surface. */
class Ts7SessionService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@Ts7SessionService }
    private val main = Handler(Looper.getMainLooper())
    private val lifecycleWorker = Executors.newSingleThreadExecutor()
    private val policy = ConnectionPolicy()
    private lateinit var store: LauncherStore
    private lateinit var diagnostics: SessionDiagnostics
    private lateinit var connectivity: ConnectivityManager
    private val observers = mutableSetOf<(SessionState) -> Unit>()
    private var engine: ReceiverEngine? = null
    private var borrowedProjection: DiPlayLauncherIntegration.Projection? = null
    private var handoff: ((Boolean) -> Unit)? = null
    private var surface: Surface? = null
    private var generation = 0
    private var busy = false
    private var cleanupBlocked = false
    private var foreground = false
    private var visible = false
    private var destroyed = false
    private var screenWasOff = false
    private var networkSignature = ""
    private var firstFrame = false
    private var state = SessionState()
    private var registeredNetwork = false
    private var registeredScreen = false
    private val retry = Runnable { pump() }
    private val timeout = Runnable { fail(R.string.ts7_failure_timeout, true, Event.ATTEMPT_TIMEOUT) }
    private val pictureTimeout = Runnable { fail(R.string.ts7_failure_picture, true, Event.ATTEMPT_TIMEOUT) }
    private val stable = Runnable {
        policy.stable()
        store.saveBudget(policy.attempts, 0)
    }
    private val networkCheck = Runnable { checkNetwork() }
    private val diPlayPoll = object : Runnable {
        override fun run() {
            if (!visible || destroyed) return
            refreshDiPlay()
            main.postDelayed(this, 500)
        }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = changed()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed()
        private fun changed() { main.post(networkCheck) }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenWasOff = true
                    diagnostics.record(Event.SCREEN_SLEEP)
                }
                Intent.ACTION_SCREEN_ON -> if (screenWasOff) {
                    screenWasOff = false
                    diagnostics.record(Event.SCREEN_WAKE)
                    if (store.resumeConnect && (store.autoConnect || DiPlayLauncherIntegration.autoConnect(this@Ts7SessionService)) && !policy.editing && !policy.active) {
                        store.newStartup()
                        automaticStart()
                    }
                    checkNetwork()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = LauncherStore(this)
        store.observeBoot()
        policy.autoConnect = store.autoConnect
        diagnostics = SessionDiagnostics(this)
        diagnostics.record(Event.SERVICE_CREATED)
        connectivity = getSystemService(ConnectivityManager::class.java)
        connectivity.registerNetworkCallback(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), networkCallback)
        registeredNetwork = true
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, filter)
        registeredScreen = true
        networkSignature = currentNetworkSignature()
        if (store.suppressed) state = SessionState(SessionPhase.DISCONNECTED)
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) disconnect()
        return START_NOT_STICKY
    }

    internal fun observe(observer: (SessionState) -> Unit) {
        observers.add(observer)
        observer(state)
    }
    internal fun removeObserver(observer: (SessionState) -> Unit) { observers.remove(observer) }
    internal fun setVisible(value: Boolean) {
        visible = value
        main.removeCallbacks(diPlayPoll)
        if (value) {
            refreshDiPlay()
            main.post(diPlayPoll)
            automaticStart()
            pump()
        } else releaseTouches()
    }

    internal fun prepareDiPlay(completion: (Boolean) -> Unit) {
        handoff = completion
        store.diPlayMode = true
        store.autoConnect = false
        store.connectionPending = false
        store.autoConsumed = true
        policy.disconnect()
        stopEngine()
        finishHandoff()
    }

    internal fun cancelDiPlayHandoff() { handoff = null }

    private fun finishHandoff() {
        if (busy) return
        val callback = handoff ?: return
        handoff = null
        if (!cleanupBlocked) store.suppressed = false
        callback(!cleanupBlocked)
    }

    private fun refreshDiPlay() {
        if (!store.diPlayMode && !DiPlayLauncherIntegration.hasSession()) return
        if (engine != null || busy) return
        store.diPlayMode = true
        val next = DiPlayLauncherIntegration.projection()
        if (borrowedProjection?.sameReceiver(next) != true) {
            surface?.let { borrowedProjection?.detach(it) }
            borrowedProjection = next
            surface?.takeIf { it.isValid }?.let { next?.attach(it) }
        }
        val active = next?.active == true
        if (active) store.suppressed = false
        val phase = when {
            active -> SessionPhase.CONNECTED
            DiPlayLauncherIntegration.hasSession() -> SessionPhase.CONNECTING
            store.suppressed -> SessionPhase.DISCONNECTED
            else -> SessionPhase.IDLE
        }
        val snapshot = SessionState(phase,
            message = if (active && next?.embeddable != true) R.string.ts7_diplay_open_projection else 0,
            pictureAvailable = next?.embeddable == true,
            videoWidth = next?.width ?: ReceiverEngine.WIDTH,
            videoHeight = next?.height ?: ReceiverEngine.HEIGHT)
        if (state != snapshot) {
            state = snapshot
            observers.toList().forEach { it(state) }
        }
    }
    internal fun beginEditing() {
        policy.editing = true
        main.removeCallbacks(retry)
        diagnostics.record(Event.SETTINGS_OPENED)
        // An established session continues. An unfinished attempt must not reconnect over an editor.
        if (state.phase != SessionPhase.CONNECTED) {
            policy.disconnect()
            store.connectionPending = false
            stopEngine()
            publish(SessionPhase.IDLE)
        }
    }
    internal fun endEditing() {
        policy.editing = false
        main.post(retry)
    }

    internal fun settingsSaved(autoWasEnabled: Boolean) {
        policy.autoConnect = store.autoConnect
        if (!store.autoConnect && state.phase != SessionPhase.CONNECTED) {
            policy.disconnect()
            store.connectionPending = false
            stopEngine()
            publish(SessionPhase.IDLE)
        }
        if (!autoWasEnabled && store.autoConnect) {
            store.newStartup()
            // Wait for the editor to close before considering an automatic attempt.
        }
    }

    internal fun connect() {
        if (store.diPlayMode || DiPlayLauncherIntegration.hasSession()) return
        if (policy.active) return
        store.suppressed = false
        store.autoConsumed = true
        store.connectionPending = true
        policy.autoConnect = store.autoConnect
        policy.request()
        store.saveBudget(0, 0)
        pump()
    }

    internal fun reconnect() {
        policy.disconnect()
        stopEngine()
        connect()
    }

    internal fun disconnect() {
        store.suppressed = true
        store.connectionPending = false
        store.autoConsumed = true
        policy.disconnect()
        diagnostics.record(Event.USER_DISCONNECTED)
        if (store.diPlayMode) {
            surface?.let { borrowedProjection?.detach(it) }
            borrowedProjection = null
            DiPlayLauncherIntegration.stop { main.post { if (!destroyed) refreshDiPlay() } }
        }
        stopEngine()
        publish(SessionPhase.DISCONNECTED)
        finishIdleService()
    }

    private fun automaticStart() {
        if (store.diPlayMode) return
        if (!visible && !foreground) return
        if (store.autoConnect && !store.suppressed && !policy.editing &&
            (!store.autoConsumed || store.connectionPending) && !policy.desired && !policy.active) {
            if (store.connectionPending) policy.restorePending(store.attempts, store.retryAt)
            else policy.request()
            store.autoConsumed = true
            store.connectionPending = policy.desired
            if (!policy.desired) publish(SessionPhase.FAILED, R.string.ts7_retry_exhausted)
        }
    }

    private fun pump() {
        main.removeCallbacks(retry)
        if (store.diPlayMode) return
        if (destroyed || cleanupBlocked || busy || policy.editing || !policy.desired || policy.active) return
        if (!foreground && !visible) return
        if (networkSignature.isEmpty()) {
            publish(SessionPhase.WAITING_NETWORK)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now < policy.retryAt) {
            main.postDelayed(retry, policy.retryAt - now)
            return
        }
        if (!policy.canStart(true, now)) return
        val problem = when {
            ManualHotspotValidation.error(store.ssid, store.password) != null -> R.string.ts7_invalid_network
            store.phone.isNullOrEmpty() -> R.string.ts7_choose_phone_first
            LauncherStore.missingPermissions(this, store.microphone).isNotEmpty() -> R.string.ts7_permission_missing
            getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true -> R.string.ts7_bluetooth_off
            else -> 0
        }
        if (problem != 0) {
            fail(problem, false, if (problem == R.string.ts7_permission_missing) Event.PERMISSION_MISSING else Event.CONFIGURATION_ERROR)
            return
        }
        if (!DiPlayLauncherIntegration.reserveDesktop(this)) {
            fail(R.string.ts7_receiver_busy, false, Event.START_REJECTED)
            return
        }
        try {
            startService(Intent(this, Ts7SessionService::class.java))
            showForeground()
        } catch (_: RuntimeException) {
            fail(R.string.ts7_failure_start, false, Event.START_REJECTED)
            return
        }
        policy.started()
        store.saveBudget(policy.attempts, 0)
        busy = true
        firstFrame = false
        val token = ++generation
        diagnostics.record(Event.ATTEMPT_STARTED)
        publish(SessionPhase.CONNECTING)
        main.postDelayed(timeout, 60_000)
        lifecycleWorker.execute {
            val result = runCatching { ReceiverEngine.create(applicationContext, store, listener(token)) { status ->
                main.post { if (token == generation && !destroyed) onStatus(status) }
            } }
            main.post {
                val next = result.getOrNull()
                if (token != generation || destroyed) {
                    lifecycleWorker.execute {
                        val stopped = runCatching { next?.close() ?: true }.getOrDefault(false)
                        main.post { cleanupFinished(stopped) }
                    }
                } else {
                    busy = false
                    if (next == null) {
                        if (result.exceptionOrNull() is AuthenticationUnavailableException)
                            fail(R.string.ts7_auth_missing, false, Event.AUTHENTICATION_UNAVAILABLE)
                        else fail(R.string.ts7_failure_start, false, Event.START_REJECTED)
                    } else {
                        engine = next
                        surface?.takeIf { it.isValid }?.let(next::attach)
                        try { next.controller.start() }
                        catch (_: RuntimeException) { fail(R.string.ts7_failure_start, false, Event.START_REJECTED) }
                    }
                }
            }
        }
    }

    private fun listener(token: Int) = object : AirPlaySessionListener {
        override val wantsTrace = false
        private fun current(action: () -> Unit) {
            main.post { if (token == generation && !destroyed) action() }
        }
        override fun onSessionActive(session: AirPlaySession) = current {
            main.removeCallbacks(timeout)
            if (!firstFrame && surface?.isValid == true) main.postDelayed(pictureTimeout, 15_000)
            diagnostics.record(Event.SESSION_ACTIVE)
            publish(SessionPhase.CONNECTED)
        }
        override fun onVideoFrameRendered(session: AirPlaySession) = current {
            if (!firstFrame) {
                firstFrame = true
                main.removeCallbacks(pictureTimeout)
                main.postDelayed(stable, 60_000)
                diagnostics.record(Event.FIRST_FRAME)
            }
        }
        override fun onSessionEnded(session: AirPlaySession) = current {
            fail(R.string.ts7_failure_transport, true, Event.SESSION_ENDED)
        }
        override fun onTransportError(message: String) = current {
            // Upstream free-text messages can contain network or phone identifiers.
            fail(R.string.ts7_failure_transport, true, Event.TRANSPORT_ERROR)
        }
    }

    private fun onStatus(status: CarPlayStatus) {
        when (status) {
            is CarPlayStatus.Failed -> {
                val pairingError = status.knownCause in setOf(ConnectionFailureCause.CHOSEN_IPHONE_NOT_PAIRED,
                    ConnectionFailureCause.NO_PAIRED_IPHONE, ConnectionFailureCause.IPHONE_NOT_CHOSEN)
                val message = when {
                    pairingError -> R.string.ts7_choose_phone_first
                    status.wifiResetRequired -> R.string.ts7_failure_wifi_reset
                    status.startupFailure == WirelessStartupFailure.BLUETOOTH_UNUSABLE -> R.string.ts7_failure_bluetooth
                    status.startupFailure == WirelessStartupFailure.FIRST_TCP_TIMEOUT -> R.string.ts7_failure_timeout
                    status.startupFailure?.retryable == false -> R.string.ts7_failure_configuration
                    else -> R.string.ts7_failure_transport
                }
                fail(message, !pairingError && status.startupFailure?.retryable != false && !status.wifiResetRequired,
                    if (pairingError) Event.CONFIGURATION_ERROR else Event.TRANSPORT_ERROR)
            }
            CarPlayStatus.WaitingForPairedIphone, CarPlayStatus.RunningWireless -> {
                if (state.phase != SessionPhase.CONNECTED) {
                    diagnostics.record(Event.WAITING_PHONE)
                    publish(SessionPhase.WAITING_PHONE)
                }
            }
            CarPlayStatus.ControlEnded -> fail(R.string.ts7_failure_transport, true, Event.SESSION_ENDED)
            CarPlayStatus.WaitingForMfi -> fail(R.string.ts7_auth_missing, false, Event.AUTHENTICATION_UNAVAILABLE)
            else -> if (state.phase != SessionPhase.CONNECTED) publish(SessionPhase.CONNECTING)
        }
    }

    private fun fail(message: Int, retryable: Boolean, event: Event) {
        diagnostics.record(event)
        policy.failed(retryable, SystemClock.elapsedRealtime())
        store.saveBudget(policy.attempts, policy.retryAt)
        store.connectionPending = policy.desired
        stopEngine()
        publish(SessionPhase.FAILED, message)
        if (policy.desired) main.postDelayed(retry, (policy.retryAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        else {
            if (retryable && policy.attempts >= ConnectionPolicy.MAX_ATTEMPTS) diagnostics.record(Event.RETRIES_EXHAUSTED)
            finishIdleService()
        }
    }

    private fun stopEngine() {
        ++generation
        main.removeCallbacks(timeout)
        main.removeCallbacks(pictureTimeout)
        main.removeCallbacks(stable)
        main.removeCallbacks(retry)
        val previous = engine
        engine = null
        if (previous != null) {
            surface?.let { previous.detach(it) }
            busy = true
            lifecycleWorker.execute {
                val stopped = runCatching { previous.close() }.getOrDefault(false)
                main.post { cleanupFinished(stopped) }
            }
        } else if (!busy && !cleanupBlocked) {
            DiPlayLauncherIntegration.releaseDesktop(this)
        }
    }

    private fun cleanupFinished(stopped: Boolean) {
        busy = false
        if (stopped) DiPlayLauncherIntegration.releaseDesktop(this)
        if (!stopped) {
            cleanupBlocked = true
            policy.failed(false, SystemClock.elapsedRealtime())
            store.connectionPending = false
            diagnostics.record(Event.CLEANUP_TIMEOUT)
            publish(SessionPhase.FAILED, R.string.ts7_failure_cleanup)
        }
        if (destroyed) lifecycleWorker.shutdown()
        else { finishHandoff(); pump(); finishIdleService() }
    }

    internal fun attachSurface(next: Surface) {
        surface = next
        if (next.isValid) {
            if (store.diPlayMode) {
                refreshDiPlay()
                borrowedProjection?.attach(next)
            }
            engine?.attach(next)
            if (!store.diPlayMode && state.phase == SessionPhase.CONNECTED && !firstFrame) {
                main.removeCallbacks(pictureTimeout)
                main.postDelayed(pictureTimeout, 15_000)
            }
        }
        diagnostics.record(Event.SURFACE_ATTACHED)
    }
    internal fun detachSurface(previous: Surface) {
        if (surface !== previous) return
        releaseTouches()
        surface = null
        // A hidden launcher cannot produce its first visible frame; resume this check on return.
        main.removeCallbacks(pictureTimeout)
        val borrowedDetached = borrowedProjection?.detach(previous) != false
        borrowedProjection = null
        if (!borrowedDetached) {
            diagnostics.record(Event.SURFACE_TIMEOUT)
            store.suppressed = true
            DiPlayLauncherIntegration.stop {}
            publish(SessionPhase.FAILED, R.string.ts7_failure_cleanup)
        }
        if (engine?.detach(previous) == false) {
            diagnostics.record(Event.SURFACE_TIMEOUT)
            fail(R.string.ts7_failure_cleanup, false, Event.SURFACE_TIMEOUT)
        }
        diagnostics.record(Event.SURFACE_DETACHED)
    }
    internal fun touch(event: MotionEvent, width: Int, height: Int): Boolean =
        if (store.diPlayMode) borrowedProjection?.touch(event, width, height) == true
        else engine?.controller?.sendTouch(CarPlayTouchMapper.contacts(event, width, height)) == true
    internal fun releaseTouches() {
        engine?.controller?.sendTouch(emptyList())
        borrowedProjection?.releaseTouches()
    }
    internal fun report() = diagnostics.report(state)
    internal fun localNetworkReady() = networkSignature.isNotEmpty()

    private fun currentNetworkSignature(): String = connectivity.allNetworks.mapNotNull { network ->
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
        val properties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
        val addresses = properties.linkAddresses.map { it.address }.filter { !it.isLoopbackAddress && !it.isAnyLocalAddress }
        if (addresses.isEmpty()) null else "$network:${properties.interfaceName}:${addresses.map { it.hostAddress }.sortedBy { it }.joinToString()}"
    }.sorted().joinToString("|")

    private fun checkNetwork() {
        if (destroyed) return
        val next = currentNetworkSignature()
        if (next == networkSignature) return
        networkSignature = next
        diagnostics.record(Event.NETWORK_CHANGED)
        if (store.diPlayMode) {
            observers.toList().forEach { it(state) }
            return
        }
        if (policy.active) fail(R.string.ts7_failure_network, true, Event.NETWORK_CHANGED)
        if (next.isEmpty() && policy.desired) {
            diagnostics.record(Event.WAITING_NETWORK)
            publish(SessionPhase.WAITING_NETWORK)
        }
        pump()
    }

    private fun publish(phase: SessionPhase, message: Int = 0) {
        state = SessionState(phase, message, policy.attempts, phase == SessionPhase.FAILED && policy.desired)
        observers.toList().forEach { it(state) }
        if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
            else Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, LauncherActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, Ts7SessionService::class.java).setAction(ACTION_DISCONNECT), flags)
        return builder.setSmallIcon(R.drawable.ic_ts7).setContentTitle(getString(R.string.ts7_app_name))
            .setContentText(getString(phaseLabel(state.phase))).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.ts7_disconnect), stop).build()).build()
    }

    private fun showForeground() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.ts7_notification_channel), NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Build.VERSION.SDK_INT >= 30 && store.microphone) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION, notification(), types)
        } else startForeground(NOTIFICATION, notification())
        foreground = true
    }

    private fun finishIdleService() {
        if (busy || policy.desired || policy.active || cleanupBlocked) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        handoff = null
        surface?.let { borrowedProjection?.detach(it) }
        borrowedProjection = null
        observers.clear()
        policy.disconnect()
        if (registeredNetwork) connectivity.unregisterNetworkCallback(networkCallback)
        if (registeredScreen) unregisterReceiver(screenReceiver)
        main.removeCallbacksAndMessages(null)
        stopEngine()
        if (!busy) lifecycleWorker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "ts7_carplay"
        private const val NOTIFICATION = 701
        private const val ACTION_DISCONNECT = "com.diplay.ts7.DISCONNECT"
        internal fun phaseLabel(phase: SessionPhase): Int = when (phase) {
            SessionPhase.IDLE -> R.string.ts7_state_idle
            SessionPhase.WAITING_NETWORK -> R.string.ts7_state_network
            SessionPhase.WAITING_PHONE -> R.string.ts7_state_phone
            SessionPhase.CONNECTING -> R.string.ts7_state_connecting
            SessionPhase.CONNECTED -> R.string.ts7_state_connected
            SessionPhase.FAILED -> R.string.ts7_state_failed
            SessionPhase.DISCONNECTED -> R.string.ts7_state_disconnected
        }
    }
}
