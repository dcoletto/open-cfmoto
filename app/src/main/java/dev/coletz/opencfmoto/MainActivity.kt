package dev.coletz.opencfmoto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var prober: EasyConnProber
    private var bleWakeUp: BleWakeUp? = null
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val raw = result.data?.getStringExtra(QrScanActivity.RESULT_QR)
        if (result.resultCode != RESULT_OK || raw == null) {
            log("QR scan cancelled")
            return@registerForActivityResult
        }
        log("QR raw: $raw")
        val qr = QrData.parse(raw)
        if (qr == null) {
            log("QR parse FAILED — missing ssid/pwd?")
            Toast.makeText(this, "Invalid QR", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        log(
            "QR parsed: ssid=${qr.ssid} mac=${qr.mac} action=${qr.action} " +
                "(ap=${qr.supportsAp}, p2p=${qr.supportsP2p}) modelId=${qr.modelId} sn=${qr.sn}"
        )
        joinAndStart(qr)
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            log("screen-capture consent declined")
            return@registerForActivityResult
        }
        // FGS of type mediaProjection must be RUNNING before getMediaProjection() on API 34+.
        // startForegroundService is async, so poll the service's foreground flag (~every 100ms)
        // instead of guessing a fixed delay.
        ProjectionService.start(this)
        val code = result.resultCode
        val data = result.data!!
        val maxTries = 50  // 50 * 100ms = 5s ceiling
        val poll = object : Runnable {
            var tries = 0
            override fun run() {
                if (ProjectionService.isForeground) {
                    try {
                        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        ProjectionHolder.projection = mpm.getMediaProjection(code, data)
                        log("screen-capture armed (FGS up after ${tries * 100}ms) — now scan the QR")
                        scanLauncher.launch(Intent(this@MainActivity, QrScanActivity::class.java))
                    } catch (e: Exception) {
                        log("getMediaProjection failed: $e")
                        ProjectionService.stop(this@MainActivity)
                    }
                } else if (tries++ < maxTries) {
                    logView.postDelayed(this, 100)
                } else {
                    log("foreground service did not start within 5s — aborting mirror")
                    ProjectionService.stop(this@MainActivity)
                }
            }
        }
        logView.post(poll)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        logView = findViewById(R.id.log_view)
        logScroll = findViewById(R.id.log_scroll)
        logView.movementMethod = ScrollingMovementMethod()

        // All components (bike PXC, Android Auto receiver, video pipeline — including those
        // running in the foreground service) log through LogBus; mirror it into the view.
        LogBus.listener = { line ->
            runOnUiThread {
                logView.append("$line\n")
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }

        BikeConfig.load(applicationContext)
        prober = EasyConnProber(applicationContext, ::log)

        // Android 13+: request notification permission up front so the mediaProjection
        // foreground-service notification can be posted (some setups gate the FGS on it).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3,
            )
        }

        // Android Auto receiver runs in its own foreground service so it survives lock/background.
        findViewById<Button>(R.id.btn_aa_start).setOnClickListener {
            log("→ starting Android Auto receiver (loopback self-mode). Ensure Android Auto is installed & set up.")
            // Once AA video is flowing steadily, auto-open the bike QR scanner so the hand-off
            // doesn't depend on scanning in the right order. One-shot; runs on the UI thread.
            AaVideoBridge.onSteadyVideo = {
                runOnUiThread {
                    AaVideoBridge.onSteadyVideo = null
                    log("→ Android Auto video is live — opening bike QR scanner")
                    ProjectionHolder.projection = null   // bike uses the AA pipeline, not mirror
                    ensureLocationPermission()
                    try {
                        scanLauncher.launch(Intent(this, QrScanActivity::class.java))
                    } catch (e: Exception) {
                        log("auto-scan launch failed ($e) — tap Scan manually")
                    }
                }
            }
            AndroidAutoService.start(this)
            // Trigger Google AA to project from the FOREGROUND activity (background-activity-launch
            // safe on Android 12+/15), after giving the service's :5288 server time to bind.
            logView.postDelayed({
                dev.coletz.opencfmoto.aa.AaSelfMode.trigger(this, log = ::log)
            }, 900)
        }
        // Stop everything: Android Auto receiver, bike PXC, projection, and leave the bike Wi-Fi.
        findViewById<Button>(R.id.btn_aa_stop).setOnClickListener {
            log("→ stopping everything (Android Auto + bike)")
            AaVideoBridge.onSteadyVideo = null
            AndroidAutoService.stop(this)
            prober.stop()
            bleWakeUp?.stop()
            bleWakeUp = null
            ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
            ProjectionHolder.projection = null
            ProjectionService.stop(this)
            BikeWifi.leave(this, ::log)
        }

        findViewById<Button>(R.id.btn_settings).setOnClickListener { showSettingsDialog() }

        findViewById<Button>(R.id.btn_share_log).setOnClickListener { shareLog() }

        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            LogBus.clear()
            logView.text = ""
        }

        log("Ready. tap Start and wait for the QR code scanner to show up.")
    }

    override fun onDestroy() {
        LogBus.listener = null
        AaVideoBridge.onSteadyVideo = null
        prober.stop()
        bleWakeUp?.stop()
        bleWakeUp = null
        ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
        ProjectionHolder.projection = null
        ProjectionService.stop(this)
        // NOTE: AndroidAutoService is intentionally NOT stopped here — it is a foreground service
        // meant to keep running when the phone is backgrounded/locked. Use "Stop Android Auto".
        BikeWifi.leave(this, ::log)
        super.onDestroy()
    }

    private fun joinAndStart(qr: QrData) {
        BikeWifi.join(
            context = this,
            ssid = qr.ssid,
            psk = qr.pwd,
            onAvailable = {
                // BLE wake-up is NOT required for projection (confirmed via TCP capture) — go
                // straight to the PXC flow. runBleWakeUpThenProber() remains available if needed.
                log("→ Wi-Fi bound; starting EasyConn PXC flow …")
                try {
                    prober.start(BikeWifi.currentNetwork)
                } catch (e: Exception) {
                    log("prober start failed: $e")
                }
            },
            onLost = { log("bike network lost") },
            log = ::log,
        )
    }

    private fun runBleWakeUpThenProber() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                ), 2,
            )
            // The user will need to tap Scan again after granting; keeping it simple for PoC.
            return
        }
        bleWakeUp?.stop()
        bleWakeUp = BleWakeUp(
            context = this,
            log = ::log,
            onUnlocked = {
                log("→ BLE wake-up OK; starting EasyConn prober …")
                try {
                    prober.start(BikeWifi.currentNetwork)
                } catch (e: Exception) {
                    log("prober start failed: $e")
                }
            },
            onFailed = { reason ->
                log("BLE wake-up failed: $reason — TCP probe likely useless, starting anyway")
                try {
                    prober.start(BikeWifi.currentNetwork)
                } catch (e: Exception) {
                    log("prober start failed: $e")
                }
            },
        ).also { it.start() }
    }

    private fun showSettingsDialog() {
        val dpiLabel = BikeConfig.dpiOverride?.let { "$it (custom)" }
            ?: "${BikeConfig.model.densityDpi} (bike default)"
        val items = arrayOf(
            "Bike model: ${BikeConfig.model.displayName}",
            "Android Auto DPI: $dpiLabel",
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showBikeModelDialog()
                    1 -> showDpiDialog()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showBikeModelDialog() {
        val models = BikeModel.entries
        val labels = models.map { "${it.displayName}  (${it.bikeWidth}x${it.bikeHeight})" }.toTypedArray()
        val current = models.indexOf(BikeConfig.model)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Bike model")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val model = models[which]
                BikeConfig.save(applicationContext, model)
                log("→ bike model set: $model (effective dpi=${BikeConfig.effectiveDpi})")
                warnIfAaRunning()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDpiDialog() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Bike default: ${BikeConfig.model.densityDpi}"
            BikeConfig.dpiOverride?.let { setText(it.toString()) }
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Android Auto DPI")
            .setMessage(
                "Higher = bigger UI elements, lower = more content on screen. " +
                    "Allowed: ${BikeConfig.DPI_MIN}–${BikeConfig.DPI_MAX}."
            )
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val dpi = input.text.toString().toIntOrNull()
                if (dpi == null || dpi !in BikeConfig.DPI_MIN..BikeConfig.DPI_MAX) {
                    Toast.makeText(
                        this,
                        "DPI must be ${BikeConfig.DPI_MIN}–${BikeConfig.DPI_MAX}",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setPositiveButton
                }
                BikeConfig.saveDpiOverride(applicationContext, dpi)
                log("→ DPI override set: $dpi")
                warnIfAaRunning()
            }
            .setNeutralButton("Use bike default") { _, _ ->
                BikeConfig.saveDpiOverride(applicationContext, null)
                log("→ DPI override cleared — using bike default (${BikeConfig.model.densityDpi})")
                warnIfAaRunning()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun warnIfAaRunning() {
        if (AndroidAutoService.isRunning) {
            log("!! Android Auto is running — the new setting applies on the next Start")
            Toast.makeText(this, "Applies on next Start", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureLocationPermission() {
        // Some OEMs require fine location to associate via WifiNetworkSpecifier.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1,
            )
        }
    }

    private fun shareLog() {
        try {
            val dir = File(cacheDir, "logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "opencfmoto-$stamp.log")
            file.writeText(LogBus.snapshot())
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "opencfmoto log $stamp")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share log"))
            log("log saved: ${file.absolutePath} (${file.length()} bytes)")
        } catch (e: Exception) {
            log("share failed: $e")
        }
    }

    private fun log(msg: String) = LogBus.log(msg)
}
