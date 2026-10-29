package com.diplay.ts7

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.util.ArrayDeque

/** Only typed events enter the report; protocol logs, addresses and credentials never do. */
internal class SessionDiagnostics(context: Context) {
    enum class Event {
        SERVICE_CREATED, ATTEMPT_STARTED, WAITING_NETWORK, NETWORK_CHANGED, WAITING_PHONE,
        SESSION_ACTIVE, FIRST_FRAME, SESSION_ENDED, CONFIGURATION_ERROR, TRANSPORT_ERROR,
        ATTEMPT_TIMEOUT, USER_DISCONNECTED, SETTINGS_OPENED, SCREEN_SLEEP, SCREEN_WAKE,
        SURFACE_ATTACHED, SURFACE_DETACHED, SURFACE_TIMEOUT, CLEANUP_TIMEOUT, START_REJECTED,
        AUTHENTICATION_UNAVAILABLE, PERMISSION_MISSING, RETRIES_EXHAUSTED,
    }
    private val app = context.applicationContext
    private val file = File(app.filesDir, "ts7-events.txt")
    private val events = ArrayDeque<String>()

    init {
        if (file.isFile) file.readLines().takeLast(100).filter { line ->
            line.matches(Regex("[0-9]+ [0-9]+ [A-Z_]+")) &&
                Event.entries.any { it.name == line.substringAfterLast(' ') }
        }.forEach(events::addLast)
    }

    @Synchronized fun record(event: Event) {
        events.addLast("${System.currentTimeMillis()} ${SystemClock.elapsedRealtime()} ${event.name}")
        while (events.size > 100) events.removeFirst()
        // This small bounded file preserves the previous attempt across process restarts.
        runCatching { file.writeText(events.joinToString("\n")) }
    }

    @Synchronized fun report(state: SessionState): String {
        val memory = ActivityManager.MemoryInfo()
        app.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val display = app.resources.displayMetrics
        return buildString {
            appendLine("TS7 CarPlay diagnostic report v1")
            appendLine("version=${BuildConfig.VERSION_NAME}")
            appendLine("upstream=615b32ce0b40cd0b99ce94ba23a512001553684f")
            appendLine("api=${Build.VERSION.SDK_INT}")
            appendLine("android=${Build.VERSION.RELEASE}")
            appendLine("manufacturer=${Build.MANUFACTURER}")
            appendLine("model=${Build.MODEL}")
            appendLine("firmware=${Build.DISPLAY}")
            appendLine("mcu=not exposed by a generic Android API")
            appendLine("abis=${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("screen=${display.widthPixels}x${display.heightPixels}")
            appendLine("memory.total=${memory.totalMem}")
            appendLine("memory.available=${memory.availMem}")
            appendLine("heap.used=${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()}")
            appendLine("video.offer=960x540 H.264 30fps main stream only")
            appendLine("state=${state.phase}; reason=${state.message}; attempts=${state.attempts}")
            appendLine("Frame rate, dropped frames and temperature require a device measurement.")
            appendLine("No SSID, password, IP/MAC, phone identifier, pairing material or raw protocol logs included.")
            appendLine("Events: wall clock milliseconds, elapsed milliseconds, event")
            events.forEach(::appendLine)
        }
    }
}
