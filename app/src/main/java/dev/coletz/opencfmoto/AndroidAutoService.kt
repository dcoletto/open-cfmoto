package dev.coletz.opencfmoto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dev.coletz.opencfmoto.aa.AaReceiver
import dev.coletz.opencfmoto.aa.ServiceDiscoveryResponse

/**
 * Foreground service that hosts the Android Auto receiver end-to-end (M4):
 *   VideoPipeline (H.264 encoder, external-source mode) + AaReceiver (loopback AAP receiver).
 *
 * Running as a foreground service with a partial wake lock keeps the whole decode→encode→PXC
 * chain alive while the phone is backgrounded or the screen is locked. The encoder's input
 * Surface is published to [AaVideoBridge] so [EasyConnProber] streams the re-encoded Android
 * Auto video to the bike dash.
 */
class AndroidAutoService : Service() {

    private var pipeline: VideoPipeline? = null
    private var cropper: SurfaceCropper? = null
    private var receiver: AaReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        startAsForeground()
        startReceiver()
        isRunning = true
        return START_STICKY
    }

    private fun startAsForeground() {
        val channelId = "opencfmoto_androidauto"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Android Auto receiver", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("OpenCfMoto — Android Auto")
            .setContentText("Receiving Android Auto for the bike dash — tap to open")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openApp)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenCfMoto:AndroidAuto").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L /* 4h safety cap */)
        }
        LogBus.log("[AA] foreground service up (wake lock held)")
    }

    private fun startReceiver() {
        if (receiver != null) { LogBus.log("[AA] receiver already started"); return }
        try {
            // Encoder at the bike's native 800x384. AA streams 800x480 with the UI in a centered
            // 800x384 viewport (margins); the decoder crops the bars onto this surface 1:1.
            val vp = VideoPipeline(
                applicationContext,
                ServiceDiscoveryResponse.BIKE_WIDTH,
                ServiceDiscoveryResponse.BIKE_HEIGHT,
                LogBus::log,
                externalSource = true,
            )
            vp.start()
            val surface = vp.encoderInputSurface()
            if (surface == null) {
                LogBus.log("[AA] encoder input surface null — cannot start receiver")
                vp.stop()
                stopSelf()
                return
            }
            pipeline = vp
            AaVideoBridge.pipeline = vp

            // GL crop stage: the AA decoder renders the full 800x480 stream (UI centered in an
            // 800x384 viewport, black margin bars top/bottom) into the cropper, which draws only
            // the viewport onto the encoder surface. Rendering the decoder straight into the
            // encoder surface stretches (scaling modes are ignored there), so if GL init fails
            // we fall back to that as a degraded mode.
            val marginX = (ServiceDiscoveryResponse.AA_WIDTH - ServiceDiscoveryResponse.BIKE_WIDTH) / 2
            val marginY = (ServiceDiscoveryResponse.AA_HEIGHT - ServiceDiscoveryResponse.BIKE_HEIGHT) / 2
            val cr = SurfaceCropper(
                surface,
                ServiceDiscoveryResponse.AA_WIDTH, ServiceDiscoveryResponse.AA_HEIGHT,
                marginX, marginY,
                ServiceDiscoveryResponse.BIKE_WIDTH, ServiceDiscoveryResponse.BIKE_HEIGHT,
                LogBus::log,
            )
            val decoderSurface = cr.start()
            if (decoderSurface != null) {
                cropper = cr
            } else {
                LogBus.log("[AA] GL crop unavailable — decoder will render straight to encoder (stretched)")
                cr.release()
            }

            receiver = AaReceiver(applicationContext, decoderSurface ?: surface, LogBus::log).also { it.start() }
        } catch (e: Exception) {
            LogBus.log("[AA] receiver start failed: $e")
            stopSelf()
        }
    }

    override fun onDestroy() {
        isRunning = false
        try { receiver?.stop() } catch (_: Exception) {}
        receiver = null
        try { cropper?.release() } catch (_: Exception) {}
        cropper = null
        AaVideoBridge.pipeline = null
        try { pipeline?.stop() } catch (_: Exception) {}
        pipeline = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        LogBus.log("[AA] foreground service stopped")
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 2

        @Volatile var isRunning = false
            private set

        const val ACTION_STOP = "dev.coletz.opencfmoto.ACTION_STOP_AA"

        fun start(ctx: Context) {
            val i = Intent(ctx, AndroidAutoService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AndroidAutoService::class.java))
        }
    }
}
