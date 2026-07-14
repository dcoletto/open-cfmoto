// OpenCfMoto glue (uses AGPLv3 code ported from headunit-revived). Orchestrates the loopback
// "self-mode" Android Auto Projection receiver:
//   1. Listen on TCP 127.0.0.1:5288 (+ NSD _aawireless._tcp).
//   2. Launch Google Android Auto's WirelessStartupActivity pointed at 127.0.0.1:5288 (no VPN).
//   3. Accept the inbound socket, run the AAP version+SSL handshake, point the H.264 decoder at
//      the supplied video sink Surface (SurfaceCropper input, or the raw encoder surface as
//      fallback), and start the message loop → AA video flows toward the encoder.
package dev.coletz.opencfmoto.aa

import android.content.Context
import dev.coletz.opencfmoto.AaVideoBridge
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.view.Surface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class AaReceiver(
    private val context: Context,
    /** Sink for decoded AA video: the SurfaceCropper input (or the raw encoder surface as fallback). */
    private val videoSurface: Surface,
    private val log: (String) -> Unit,
) {
    companion object {
        const val PORT = 5288
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    @Volatile private var transport: AapTransport? = null
    @Volatile private var connection: SocketAccessoryConnection? = null
    @Volatile private var steadyVideoFired = false
    private val videoDecoder = VideoDecoder().apply {
        fallbackWidth = ServiceDiscoveryResponse.AA_WIDTH
        fallbackHeight = ServiceDiscoveryResponse.AA_HEIGHT
        onFpsChanged = { fps ->
            log("[AA] decode fps=$fps")
            if (!steadyVideoFired && fps >= 25) {
                steadyVideoFired = true
                log("[AA] steady video reached (fps=$fps) — signalling ready for bike hand-off")
                try { AaVideoBridge.onSteadyVideo?.invoke() } catch (_: Exception) {}
            }
        }
    }

    /** Ensure Conscrypt/AAP logging are wired before anything touches SSL. */
    fun start() {
        if (running) { log("[AA] already running"); return }
        running = true
        AaLog.sink = log
        ConscryptInitializer.initialize()

        try {
            serverSocket = ServerSocket(PORT).apply { reuseAddress = true }
            log("[AA] WirelessServer listening on :$PORT")
        } catch (e: Exception) {
            log("[AA] failed to bind :$PORT — ${e.message}")
            running = false
            return
        }

        registerNsd()

        acceptThread = thread(name = "aa-accept", isDaemon = true) { acceptLoop() }
        // Self-mode (launching Google Android Auto) is triggered by MainActivity from the
        // foreground, via AaSelfMode.trigger(), to satisfy background-activity-launch rules.
    }

    fun stop() {
        running = false
        try { transport?.quit() } catch (_: Exception) {}
        transport = null
        try { connection?.disconnect() } catch (_: Exception) {}
        connection = null
        try { videoDecoder.stop("AaReceiver.stop") } catch (_: Exception) {}
        unregisterNsd()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread?.interrupt(); acceptThread = null
        AaLog.sink = null
        log("[AA] receiver stopped")
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running) {
            val client = try {
                ss.accept()
            } catch (e: Exception) {
                if (running) log("[AA] accept ended: ${e.message}")
                break
            }
            log("[AA] <<< Android Auto connected from ${client.inetAddress?.hostAddress}")
            if (transport != null) {
                log("[AA] already have a session — dropping extra connection")
                try { client.close() } catch (_: Exception) {}
                continue
            }
            thread(name = "aa-session", isDaemon = true) { handleConnection(client) }
        }
    }

    private fun handleConnection(client: Socket) {
        val conn = SocketAccessoryConnection(client)
        connection = conn
        val t = AapTransport(videoDecoder, context)
        t.onQuit = { clean ->
            log("[AA] transport quit (clean=$clean, userExit=${t.wasUserExit})")
            transport = null
            try { conn.disconnect() } catch (_: Exception) {}
            connection = null
            // Server keeps listening — AA (or the user) can reconnect.
        }
        transport = t

        log("[AA] starting AAP handshake (version + SSL)…")
        if (!t.startHandshake(conn)) {
            log("[AA] handshake FAILED")
            transport = null
            try { conn.disconnect() } catch (_: Exception) {}
            connection = null
            return
        }
        log("[AA] handshake OK — pointing decoder at video sink surface and starting read loop")
        videoDecoder.setSurface(videoSurface)
        t.startReading()
        log("[AA] read loop started — expecting ServiceDiscovery then video")
    }

    private fun registerNsd() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) { log("[AA] NSD unavailable"); return }
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "AAWireless"
                serviceType = "_aawireless._tcp"
                port = PORT
            }
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = log("[AA] NSD registered: ${info.serviceName}")
                override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) = log("[AA] NSD reg fail: $err")
                override fun onServiceUnregistered(info: NsdServiceInfo) = log("[AA] NSD unregistered")
                override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) = log("[AA] NSD unreg fail: $err")
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            log("[AA] NSD register error: ${e.message}")
        }
    }

    private fun unregisterNsd() {
        try { registrationListener?.let { nsdManager?.unregisterService(it) } } catch (_: Exception) {}
        registrationListener = null
    }
}
