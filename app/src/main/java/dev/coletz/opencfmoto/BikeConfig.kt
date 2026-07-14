package dev.coletz.opencfmoto

import android.content.Context

/**
 * Supported bike dashboards. Each model drives the whole video geometry:
 *
 *  - [bikeWidth]x[bikeHeight]: the panel's native resolution — what the bike-side encoder
 *    produces and what the dash decoder expects.
 *  - [aaWidth]x[aaHeight]: the Android Auto codec resolution we negotiate. AA only streams
 *    fixed sizes (see ServiceDiscoveryResponse.codecResolutionFor), so pick the smallest one
 *    that fully contains the panel; the leftover is declared as margins and the phone renders
 *    the UI into a centered bikeWidth x bikeHeight viewport that SurfaceCropper extracts 1:1.
 *  - [densityDpi]: default DPI reported to the phone — tunes how large the AA UI draws on this
 *    panel. Users can override it per-phone in Settings (see [BikeConfig.dpiOverride]).
 *
 * Portrait panels are supported the same way — use a portrait AA resolution (e.g. 720x1280).
 *
 * To add a model, ask the user for the [BIKE-REPORT] log line (EasyConnProber logs it when the
 * bike sends REQ_RV_CONFIG_CAPTURE): it contains the panel resolution and the head-unit name.
 */
enum class BikeModel(
    val displayName: String,
    val bikeWidth: Int,
    val bikeHeight: Int,
    val aaWidth: Int,
    val aaHeight: Int,
    val densityDpi: Int = 160,
) {
    SR_675("675 SR-R", 800, 384, 800, 480);

    init {
        require(bikeWidth <= aaWidth && bikeHeight <= aaHeight) {
            "$name: panel ${bikeWidth}x$bikeHeight must fit inside AA ${aaWidth}x$aaHeight"
        }
    }

    /** Total margins declared to the phone; it centers the UI viewport, splitting them evenly. */
    val marginWidth: Int get() = aaWidth - bikeWidth
    val marginHeight: Int get() = aaHeight - bikeHeight

    override fun toString() = "$displayName (panel ${bikeWidth}x$bikeHeight, AA ${aaWidth}x$aaHeight)"
}

/**
 * Process-wide selected bike model, persisted in SharedPreferences. [load] is called by both
 * MainActivity and AndroidAutoService so the selection survives process restarts regardless of
 * which entry point runs first. The AAP stack (no Context available) reads [model] directly.
 *
 * NOTE: the model is read when the Android Auto session starts (service discovery, encoder and
 * crop geometry) — changing it while a session is running takes effect on the next start.
 */
object BikeConfig {
    private const val PREFS = "opencfmoto_settings"
    private const val KEY_BIKE_MODEL = "bike_model"
    private const val KEY_DPI_OVERRIDE = "dpi_override"   // 0 / absent = use the model default

    /** Sane clamp for the DPI reported to the phone; outside this AA renders unusably. */
    const val DPI_MIN = 80
    const val DPI_MAX = 640

    @Volatile var model: BikeModel = BikeModel.SR_675
        private set

    /** User-chosen DPI, or null to use the selected model's [BikeModel.densityDpi] default. */
    @Volatile var dpiOverride: Int? = null
        private set

    /** DPI actually reported to the phone: the user override if set, else the model default. */
    val effectiveDpi: Int get() = dpiOverride ?: model.densityDpi

    fun load(context: Context): BikeModel {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_BIKE_MODEL, null)
        model = BikeModel.entries.firstOrNull { it.name == name } ?: BikeModel.SR_675
        dpiOverride = prefs.getInt(KEY_DPI_OVERRIDE, 0).takeIf { it in DPI_MIN..DPI_MAX }
        return model
    }

    fun save(context: Context, newModel: BikeModel) {
        model = newModel
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BIKE_MODEL, newModel.name).apply()
    }

    /** Pass null to clear the override and fall back to the model default. */
    fun saveDpiOverride(context: Context, dpi: Int?) {
        require(dpi == null || dpi in DPI_MIN..DPI_MAX) { "dpi $dpi outside $DPI_MIN..$DPI_MAX" }
        dpiOverride = dpi
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_DPI_OVERRIDE, dpi ?: 0).apply()
    }
}
