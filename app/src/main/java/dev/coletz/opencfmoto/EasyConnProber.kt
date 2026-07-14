package dev.coletz.opencfmoto

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.wifi.WifiManager
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * EasyConn / Carbit PXC client for CFMoto MotoPlay.
 *
 * Topology (verified in cfmoto-tcp-v5.log): the PHONE is the SERVER.
 *  1. Discover the bike (gateway 192.168.0.1, EasyConn mDNS advertises :10930).
 *  2. Open TCP servers on 10920, 10921, 10922 bound to our bike-network IP.
 *  3. Connect once to bike:10930 and send ECP_PXC_MDNS_RESPOND (cmd 0x70000010, JSON);
 *     bike replies {"status":true} and we close that socket.
 *  4. The bike then connects BACK to our listening ports and drives the PXC handshake
 *     (channel selects, CLIENT_INFO, SN check, heartbeats) — handled by [PxcHandshake].
 */
class EasyConnProber(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    companion object {
        const val PORT_MEDIA_DATA = 10920   // MediaProjectService data
        const val PORT_MEDIA_CTRL = 10921   // MediaProjectService ctrl
        const val PORT_PXC_CTRL   = 10922   // PXCService ctrl (channel selects + CLIENT_INFO)
        const val BIKE_PROBE_PORT = 10930   // bike's EasyConn mDNS/probe endpoint
        const val SPOOFED_PACKAGE = "com.cfmoto.cfmotointernational"
        private val LISTEN_PORTS = intArrayOf(PORT_PXC_CTRL, PORT_MEDIA_CTRL, PORT_MEDIA_DATA)
    }

    private val handshake = PxcHandshake(log)
    private val servers = ArrayList<ServerSocket>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var heartbeatThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var probed = false
    @Volatile private var video: VideoPipeline? = null
    @Volatile private var ownsVideo = false
    @Volatile private var negW = 800
    @Volatile private var negH = 384
    @Volatile private var framesSent = 0
    // Diagnostic: a portrait bike can't accept the landscape AA encoder. When it requests portrait,
    // don't share AA — run a dedicated own-content pipeline at the bike's requested size instead.
    @Volatile private var forceOwnContent = false

    fun start(network: Network?) {
        if (running) { log("already running"); return }
        dumpEnvironment(network)

        val myIp = pickBikeInterfaceIp(network)
        if (myIp == null) { log("could not resolve our IPv4 on the bike network; aborting"); return }
        val bikeIp = resolveGateway(network)
        if (bikeIp == null) { log("could not resolve bike gateway IP; aborting"); return }
        log("our IP=${myIp.hostAddress}  bike IP=${bikeIp.hostAddress}")

        running = true
        acquireMulticastLock()

        // 1. Listen on all three ports BEFORE probing, so we're ready for the bike's call-back.
        for (port in LISTEN_PORTS) {
            try {
                val ss = ServerSocket(port, 50, myIp)
                servers.add(ss)
                spawnAccept(port, ss)
            } catch (e: Exception) {
                log("bind :$port failed: ${e.message}")
            }
        }
        log("listening on ${myIp.hostAddress} ports ${LISTEN_PORTS.toList()}")

        // 2. Send the probe (gives the bike our IP → it connects back).
        thread(name = "ec-probe", isDaemon = true) {
            sendMdnsRespond(bikeIp, myIp, network)
        }
        startHeartbeatLog()
    }

    fun stop() {
        running = false
        // Only stop the pipeline if we created it; the shared Android Auto pipeline is owned
        // by AndroidAutoService and must outlive a bike disconnect.
        if (ownsVideo) video?.stop()
        video = null; ownsVideo = false
        heartbeatThread?.interrupt(); heartbeatThread = null
        for (s in servers) try { s.close() } catch (_: IOException) {}
        servers.clear()
        multicastLock?.let { try { if (it.isHeld) it.release() } catch (_: Exception) {} }
        multicastLock = null
        log("stopped")
    }

    /** Step 3: phone→bike probe. cmd 0x70000010 + JSON; expect 0x70000011 {"status":true}. */
    private fun sendMdnsRespond(bikeIp: Inet4Address, myIp: Inet4Address, network: Network?) {
        var attempt = 0
        while (running && attempt < 5 && !probed) {
            attempt++
            try {
                log("[PROBE] connect #$attempt -> ${bikeIp.hostAddress}:$BIKE_PROBE_PORT")
                val sock = Socket()
                try { sock.bind(InetSocketAddress(myIp, 0)) } catch (_: Exception) {}
                network?.let { try { it.bindSocket(sock) } catch (_: Exception) {} }
                sock.connect(InetSocketAddress(bikeIp, BIKE_PROBE_PORT), 3000)
                sock.soTimeout = 5000

                val json = JSONProbe()
                log("[PROBE] -> MDNS_RESPOND (0x70000010) $json")
                PxcFrame(PxcFrame.CMD_MDNS_RESPOND, json.toByteArray(Charsets.UTF_8))
                    .write(sock.getOutputStream())

                val resp = PxcFrame.read(sock.getInputStream())
                if (resp == null) {
                    log("[PROBE] bike closed before responding")
                } else {
                    val body = String(resp.payload, Charsets.UTF_8)
                    log("[PROBE] <- cmd=0x${resp.cmd.toUInt().toString(16)} $body")
                    if (resp.cmd == PxcFrame.CMD_MDNS_RESPOND_ACK && body.contains("true")) {
                        log("[PROBE] *** accepted — bike should now connect back to our ports ***")
                        probed = true
                    } else {
                        log("[PROBE] !! not accepted: $body")
                    }
                }
                try { sock.close() } catch (_: IOException) {}
                if (probed) return
            } catch (e: Exception) {
                log("[PROBE] failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
        }
    }

    private fun JSONProbe(): String =
        "{\"phoneType\":\"Android\",\"packageName\":\"$SPOOFED_PACKAGE\"}"

    private fun spawnAccept(port: Int, server: ServerSocket) =
        thread(name = "ec-accept-$port", isDaemon = true) {
            while (running) {
                val client = try { server.accept() } catch (e: IOException) {
                    if (running) log("[:$port] accept ended: ${e.message}"); break
                }
                log("[:$port] <<< bike connected from ${client.remoteSocketAddress}")
                thread(name = "ec-conn-$port", isDaemon = true) { readLoop(port, client) }
            }
        }

    private fun readLoop(port: Int, socket: Socket) {
        val tag = ":$port"
        socket.soTimeout = 0
        socket.tcpNoDelay = true
        try {
            val input = BufferedInputStream(socket.getInputStream())
            // Framing is by port (consistent across every run):
            //   10922 = PXC control (16-byte CmdBaseHead); 10921/10920 = media (8-byte ReqBase).
            if (port == PORT_PXC_CTRL) {
                log("[$tag] framing=CmdBaseHead (PXC control)")
                while (running) {
                    val frame = try { PxcFrame.read(input) } catch (e: Exception) {
                        log("[$tag] frame error: ${e.message}"); return
                    } ?: run { log("[$tag] bike closed"); return }
                    try { handshake.handle(tag, frame, socket) }
                    catch (e: Exception) { log("[$tag] handler error: $e") }
                }
            } else {
                log("[$tag] framing=ReqBase (media plane)")
                mediaLoop(tag, input, socket.getOutputStream())
            }
        } catch (e: IOException) {
            log("[$tag] read error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: IOException) {}
        }
    }

    // ---- Media plane: Protocol.ReqBase framing (8-byte LE header + body) ----
    // header: cmdType(s16) | cmdLen(s16) | token(i32); reply uses the same header.
    private fun mediaLoop(tag: String, input: java.io.InputStream, out: OutputStream) {
        val header = ByteArray(8)
        while (running) {
            if (!PxcFrame.readFully(input, header, 8)) { log("[$tag] media closed"); return }
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val cmdType = b.getShort(0).toInt()
            val cmdLen = b.getShort(2).toInt() and 0xFFFF
            val token = b.getInt(4)
            val body = ByteArray(cmdLen)
            if (cmdLen > 0 && !PxcFrame.readFully(input, body, cmdLen)) { log("[$tag] media body short"); return }
            handleMediaReq(tag, cmdType, token, body, out)
        }
    }

    /** Frame reply on the data socket is written RAW (not ReqBase): [size i32 LE][access unit].
     *  Inferred from the partial-decompiled MediaProjectServerDataExecuteThread.reply*Data(). */
    private fun sendFrameRaw(out: OutputStream, frame: ByteArray) {
        val sz = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0, frame.size).array()
        synchronized(out) {
            out.write(sz)
            out.write(frame)
            out.flush()
        }
    }

    /** Summarize the Annex-B NAL unit types in an access unit, e.g. "SPS+PPS+IDR" or "P". */
    private fun nalSummary(frame: ByteArray): String {
        val types = ArrayList<String>()
        var i = 0
        while (i + 4 < frame.size) {
            // find start code 00 00 00 01 or 00 00 01
            val sc4 = frame[i].toInt() == 0 && frame[i+1].toInt() == 0 && frame[i+2].toInt() == 0 && frame[i+3].toInt() == 1
            val sc3 = frame[i].toInt() == 0 && frame[i+1].toInt() == 0 && frame[i+2].toInt() == 1
            if (sc4 || sc3) {
                val nalIdx = i + (if (sc4) 4 else 3)
                if (nalIdx < frame.size) {
                    when (frame[nalIdx].toInt() and 0x1F) {
                        7 -> types.add("SPS"); 8 -> types.add("PPS"); 5 -> types.add("IDR")
                        1 -> types.add("P"); 6 -> types.add("SEI"); else -> types.add("n${frame[nalIdx].toInt() and 0x1F}")
                    }
                }
                i = nalIdx + 1
            } else i++
            if (types.size >= 6) break
        }
        return if (types.isEmpty()) "?" else types.joinToString("+")
    }

    private fun sendReqBase(out: OutputStream, cmdType: Int, body: ByteArray?) {
        val len = body?.size ?: 0
        val h = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        h.putShort(0, cmdType.toShort())
        h.putShort(2, len.toShort())
        h.putInt(4, 0)
        synchronized(out) {
            out.write(h.array())
            if (body != null && body.isNotEmpty()) out.write(body)
            out.flush()
        }
    }

    private fun handleMediaReq(tag: String, cmdType: Int, token: Int, body: ByteArray, out: OutputStream) {
        when (cmdType) {
            16 -> { // REQ_RV_CONFIG_CAPTURE
                val cfg = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                val w = if (body.size >= 2) cfg.getShort(0).toInt() and 0xFFFF else 0
                val h = if (body.size >= 4) cfg.getShort(2).toInt() and 0xFFFF else 0
                val fps = if (body.size >= 8) cfg.getInt(4) else 0
                val wantEncoder = if (body.size >= 12) cfg.getInt(8) else 2
                val supportExtend = if (body.size >= 30) body[29] else 0
                log("[$tag] REQ_CONFIG_CAPTURE w=$w h=$h fps=$fps wantEncoder=$wantEncoder ext=$supportExtend len=${body.size}")
                val reqW = w and 0xFFF0
                val reqH = h and 0xFFF0
                // A shared external encoder (Android Auto) already runs at a FIXED resolution and
                // cannot be resized mid-stream. If we echo the bike's requested size but stream a
                // different one, the bike sets its decoder to the wrong dimensions and RESETs after
                // the first frames (seen on the 460x750 portrait bike). So report the encoder's
                // ACTUAL output resolution; the bike then scales it onto its own panel.
                val shared = AaVideoBridge.pipeline
                // Portrait bike (taller than wide) can't accept the landscape AA encoder. When AA is
                // sharing landscape but the bike wants portrait, DON'T share — run a dedicated
                // own-content pipeline at the bike's own size so orientation matches. (Diagnostic.)
                val bikePortrait = reqH > reqW && reqW > 0
                val sharedLandscape = shared != null && shared.width >= shared.height
                forceOwnContent = bikePortrait && sharedLandscape
                if (shared != null && !forceOwnContent) {
                    negW = shared.width
                    negH = shared.height
                    log("[$tag] shared AA encoder is ${negW}x${negH}; reporting that (bike requested ${reqW}x${reqH})")
                } else {
                    negW = reqW
                    negH = reqH
                    if (forceOwnContent)
                        log("[$tag] bike is PORTRAIT (${reqW}x${reqH}) but AA is landscape (${shared?.width}x${shared?.height}); using dedicated portrait own-content pipeline (diagnostic)")
                }
                // RLY_RV_CONFIG_CAPTURE (17): encoder(i32) | width&~15(s16) | height&~15(s16) | ext(byte)
                val rly = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                rly.putInt(0, if (wantEncoder == 0) 2 else wantEncoder)
                rly.putShort(4, negW.toShort())
                rly.putShort(6, negH.toShort())
                rly.put(8, supportExtend)
                log("[$tag] → RLY_CONFIG_CAPTURE(17) encoder=${if (wantEncoder==0) 2 else wantEncoder} w=$negW h=$negH")
                sendReqBase(out, 17, rly.array())
            }
            48 -> { // REQ_GET_VERSION → 49 (two LE ints: version, subVersion=1) per ctrl thread
                log("[$tag] REQ_GET_VERSION → RLY 49")
                val v = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                v.putInt(0, 3); v.putInt(4, 1)
                sendReqBase(out, 49, v.array())
            }
            64 -> { // REQ_HEARTBEAT → 65
                log("[$tag] REQ_HEARTBEAT(64) → RLY 65")
                sendReqBase(out, 65, null)
            }
            96 -> { // REQ_CONFIGCAPTUREREXTEND → 97 (JSON). Send state OK.
                log("[$tag] REQ_CONFIGCAPTUREREXTEND len=${body.size} ${String(body, Charsets.UTF_8).take(120)} → RLY 97")
                sendReqBase(out, 97, "{\"state\":0}".toByteArray(Charsets.UTF_8))
            }
            128 -> { // REQ_START_H264 → 129 (then bike expects frames on data socket)
                log("[$tag] *** REQ_START_H264 *** len=${body.size} → RLY 129 (no encoder yet — video stage TODO)")
                sendReqBase(out, 129, null)
            }
            112 -> { // REQ_RV_DATA_START → start encoder, then RLY_RV_DATA_START(113)
                if (video == null) {
                    val shared = AaVideoBridge.pipeline
                    if (shared != null && !forceOwnContent) {
                        // Android Auto is running: pull encoded frames from its (already started)
                        // pipeline instead of creating our own Presentation/mirror source.
                        video = shared
                        ownsVideo = false
                        log("[$tag] REQ_RV_DATA_START(112): using shared Android Auto video pipeline")
                    } else {
                        log("[$tag] REQ_RV_DATA_START(112): starting dedicated own-content video ${negW}x${negH}${if (forceOwnContent) " (portrait diagnostic)" else ""}")
                        video = VideoPipeline(context, negW, negH, log).also { it.start() }
                        ownsVideo = true
                    }
                }
                // A newly-started data stream must begin on a keyframe: force the (possibly
                // long-running, AA-shared) encoder to emit a fresh SPS/PPS+IDR and drop stale
                // P-frames, or the bike decodes nothing and resets after a few frames.
                video?.requestKeyFrameAndFlush()
                log("[$tag] → RLY 113")
                sendReqBase(out, 113, null)
            }
            114 -> { // REQ_RV_DATA_NEXT — bike pulling a frame (data socket); send one access unit raw
                val frame = video?.pollFrame(1500)
                if (frame == null) {
                    log("[$tag] REQ_RV_DATA_NEXT(114): no frame ready (ext=${body.size}b)")
                } else {
                    sendFrameRaw(out, frame)
                    framesSent++
                    if (framesSent <= 8 || framesSent % 60 == 0)
                        log("[$tag] sent frame #$framesSent (${frame.size}b, ${nalSummary(frame)})")
                }
            }
            else -> {
                val preview = BleProtocol.bytesToHex(body.copyOf(minOf(32, body.size)))
                log("[$tag] media cmdType=$cmdType len=${body.size} $preview")
            }
        }
    }

    private fun resolveGateway(network: Network?): Inet4Address? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val target = network ?: cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(target) ?: return null
        // 1. AP-hotspot bikes (action=9): the gateway is the default-route next hop (e.g. 192.168.0.1).
        for (r in lp.routes) {
            if (r.isDefaultRoute) {
                val gw = r.gateway
                if (gw is Inet4Address && !gw.isAnyLocalAddress) {
                    log("gateway via default route: ${gw.hostAddress}")
                    return gw
                }
            }
        }
        lp.dnsServers.filterIsInstance<Inet4Address>().firstOrNull()?.let {
            log("gateway via DNS server: ${it.hostAddress}")
            return it
        }
        // 2. Wi-Fi Direct bikes (action=73, DIRECT-… SSID) provide NO default route and NO DNS —
        //    the bike is the P2P group owner, which Android fixes at <subnet>.1 (e.g. 192.168.49.1).
        //    This also matches AP mode (x.x.x.1), so it's a safe general fallback.
        val myV4 = lp.linkAddresses.map(LinkAddress::getAddress)
            .filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress }
        if (myV4 != null) {
            val octets = myV4.address       // fresh copy; safe to mutate
            octets[3] = 1
            val gw = java.net.InetAddress.getByAddress(octets) as Inet4Address
            log("gateway derived as subnet .1 (Wi-Fi Direct / no default route): ${gw.hostAddress}")
            return gw
        }
        return null
    }

    private fun pickBikeInterfaceIp(network: Network?): Inet4Address? {
        if (network != null) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getLinkProperties(network)?.linkAddresses
                ?.map(LinkAddress::getAddress)
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.let { return it }
        }
        for (nic in NetworkInterface.getNetworkInterfaces()) {
            if (!nic.isUp || nic.isLoopback) continue
            for (addr in nic.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) return addr
            }
        }
        return null
    }

    private fun acquireMulticastLock() {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock("opencfmoto").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun startHeartbeatLog() {
        heartbeatThread = thread(name = "ec-hb", isDaemon = true) {
            var i = 0
            while (running) {
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                i++
                log("hb#$i probed=$probed video=${video != null} framesSent=$framesSent openServers=${servers.count { !it.isClosed }}")
            }
        }
    }

    private fun dumpEnvironment(network: Network?) {
        log("---- environment ----")
        log("Build: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (network != null) {
            val lp = cm.getLinkProperties(network)
            log("linkProps iface=${lp?.interfaceName} addrs=${lp?.linkAddresses} routes=${lp?.routes}")
        }
        log("---------------------")
    }
}
