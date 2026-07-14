package dev.coletz.opencfmoto

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * H.264 video pipeline for MotoPlay mirroring.
 *
 *   VirtualDisplay  ──hosts──▶  Presentation ("Hello World", live clock)
 *        │ output Surface = encoder input
 *        ▼
 *   MediaCodec (video/avc, 800x384)  ──encoded access units──▶  frameQueue
 *
 * The data socket pulls one frame per REQ_RV_DATA_NEXT(114) via [pollFrame].
 * Frames are Annex-B; SPS/PPS (codec config) is prepended to the first keyframe so the
 * decoder on the bike can start.
 *
 * No MediaProjection needed: we render our OWN content onto a private VirtualDisplay
 * (the same approach as MotoPlay's SplitScreenPresentation).
 */
class VideoPipeline(
    private val context: Context,
    val width: Int,
    val height: Int,
    private val log: (String) -> Unit,
    /**
     * When true, the pipeline only runs the H.264 encoder and exposes [encoderInputSurface] for
     * an EXTERNAL producer (the Android Auto video decoder) to render into. No Presentation /
     * MediaProjection source is created. See [encoderInputSurface] and AaVideoBridge.
     */
    private val externalSource: Boolean = false,
) {
    private val main = Handler(Looper.getMainLooper())
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false

    private val frameQueue = LinkedBlockingDeque<ByteArray>(8)
    @Volatile private var codecConfig: ByteArray? = null   // SPS/PPS
    // When a new consumer joins mid-stream we drop encoded P-frames until the next keyframe, so the
    // consumer's decoder starts on a self-contained IDR instead of an un-decodable P-frame.
    @Volatile private var waitForKeyframe = false

    fun start() {
        if (running) return
        running = true
        try {
            fun baseFormat() = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)   // frequent keyframes for late joiners
                // Surface-input encoders only emit on new buffers; a STATIC screen (e.g. mirror of
                // an idle app) then produces zero frames and the bike times out. Repeat the last
                // frame if nothing new arrives so output is continuous even when the screen is still.
                // 33ms → ~30fps floor: the AA-shared path already runs 30fps and works; own-content
                // was only ~12fps (bursty, tied to the ticker) and the bike's data thread starved.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 33_000L) // 33ms → ~30fps floor
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            // Prefer Baseline (embedded HU decoders often require it); fall back if the encoder rejects it.
            try {
                val fmt = baseFormat().apply {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                }
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                log("[VIDEO] configured Baseline@3.1")
            } catch (e: Exception) {
                log("[VIDEO] baseline configure failed ($e) — retrying default profile")
                c.reset()
                c.configure(baseFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            inputSurface = c.createInputSurface()
            c.start()
            codec = c
            log("[VIDEO] encoder started ${width}x${height} h264 30fps")

            drainThread = thread(name = "video-drain", isDaemon = true) { drainLoop() }

            if (externalSource) {
                // Android Auto mode: the AA VideoDecoder renders into inputSurface (see
                // encoderInputSurface()). No Presentation/MediaProjection source here.
                log("[VIDEO] EXTERNAL source mode (Android Auto) — encoder input surface ready")
                return
            }

            val projection = ProjectionHolder.projection
            if (projection != null) {
                log("[VIDEO] FULL-SCREEN mirror mode (MediaProjection)")
                setupProjectionDisplay(projection)
            } else {
                log("[VIDEO] own-content mode (Presentation)")
                main.post { setupDisplayAndPresentation() }
            }
        } catch (e: Exception) {
            log("[VIDEO] start failed: $e")
            stop()
        }
    }

    /** Full-screen mirror: capture the real device display into the encoder surface. */
    private fun setupProjectionDisplay(projection: android.media.projection.MediaProjection) {
        try {
            // Required on API 34+: register a callback before creating the virtual display.
            projection.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() { log("[VIDEO] MediaProjection stopped") }
            }, main)
            val flags = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            virtualDisplay = projection.createVirtualDisplay(
                "OpenCfMotoMirror", width, height, 160, flags, inputSurface, null, main,
            )
            log("[VIDEO] mirroring device screen → ${width}x${height} (letterboxed to fit)")
        } catch (e: Exception) {
            log("[VIDEO] projection display failed: $e")
        }
    }

    private fun setupDisplayAndPresentation() {
        try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            val vd = dm.createVirtualDisplay("OpenCfMoto", width, height, 160, inputSurface, flags)
            virtualDisplay = vd
            val display = vd?.display ?: run { log("[VIDEO] virtualDisplay.display null"); return }

            val pres = Presentation(context, display)
            val root = LinearLayout(pres.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#0D47A1"))
            }
            val title = TextView(pres.context).apply {
                text = "Hacked by Coletz :P"
                setTextColor(Color.WHITE)
                textSize = 28f
                gravity = Gravity.CENTER
            }
            val clock = TextView(pres.context).apply {
                setTextColor(Color.parseColor("#80D8FF"))
                textSize = 20f
                gravity = Gravity.CENTER
            }
            root.addView(title)
            root.addView(clock)
            pres.setContentView(root)
            pres.show()
            presentation = pres
            log("[VIDEO] presentation shown on virtual display")

            // Animate so the stream is visibly live (forces continuous frames).
            val ticker = object : Runnable {
                var n = 0
                override fun run() {
                    if (!running) return
                    clock.text = "frame tick ${n++}"
                    main.postDelayed(this, 100)
                }
            }
            main.post(ticker)
        } catch (e: Exception) {
            log("[VIDEO] display/presentation failed: $e")
        }
    }

    private fun drainLoop() {
        val codec = this.codec ?: return
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = try { codec.dequeueOutputBuffer(info, 100_000) } catch (e: Exception) {
                log("[VIDEO] dequeue failed: $e"); break
            }
            if (idx < 0) continue
            val buf = try { codec.getOutputBuffer(idx) } catch (e: Exception) { null }
            if (buf != null && info.size > 0) {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val bytes = ByteArray(info.size)
                buf.get(bytes)

                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    codecConfig = bytes   // SPS/PPS — hold, prepend to next keyframe
                    log("[VIDEO] got codec config (SPS/PPS) ${bytes.size}b")
                } else {
                    val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (waitForKeyframe && !isKey) {
                        // A consumer just joined; skip P-frames until the first keyframe.
                    } else {
                        if (isKey) waitForKeyframe = false
                        val out = if (isKey && codecConfig != null) codecConfig!! + bytes else bytes
                        // Keep the queue fresh: if full, drop oldest so we never lag far behind.
                        if (!frameQueue.offerLast(out)) {
                            frameQueue.pollFirst()
                            frameQueue.offerLast(out)
                        }
                    }
                }
            }
            try { codec.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
        }
    }

    /**
     * The encoder's input Surface. In [externalSource] mode the Android Auto video decoder is
     * pointed at this surface (`VideoDecoder.setSurface`), so decoded AA frames are re-encoded to
     * the bike's 800x384 H.264 and pulled by the PXC data socket via [pollFrame]. Valid only
     * after [start].
     */
    fun encoderInputSurface(): android.view.Surface? = inputSurface

    /** Called by the data socket on each REQ_RV_DATA_NEXT(114). Returns one access unit. */
    fun pollFrame(timeoutMs: Long): ByteArray? =
        try { frameQueue.pollFirst(timeoutMs, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null }

    /**
     * Force the encoder to emit a fresh keyframe (SPS/PPS + IDR) and drop stale queued frames.
     *
     * The AA-fed encoder may have been running for many seconds before the bike connects, so the
     * frame queue holds mid-GOP P-frames the bike's decoder can't start on. A bike that only ever
     * sees P-frames (no IDR) times out and resets after a few frames. Calling this when the bike
     * begins pulling (REQ_RV_DATA_START) guarantees the first frame it receives is a self-contained
     * keyframe.
     */
    fun requestKeyFrameAndFlush() {
        frameQueue.clear()
        waitForKeyframe = true
        try {
            val p = android.os.Bundle()
            p.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec?.setParameters(p)
            log("[VIDEO] requested sync frame + flushed queue (client join)")
        } catch (e: Exception) {
            log("[VIDEO] requestKeyFrame failed: $e")
        }
    }

    fun stop() {
        running = false
        drainThread?.interrupt(); drainThread = null
        main.post {
            try { presentation?.dismiss() } catch (_: Exception) {}
            presentation = null
            try { virtualDisplay?.release() } catch (_: Exception) {}
            virtualDisplay = null
        }
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        frameQueue.clear()
        codecConfig = null
    }
}
