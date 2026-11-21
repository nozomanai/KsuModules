package io.github.cobylinweiqi.fakeloc.xposed

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import io.github.cobylinweiqi.fakeloc.core.ConfigCodec
import io.github.cobylinweiqi.fakeloc.core.Coords
import io.github.cobylinweiqi.fakeloc.core.Datum
import io.github.cobylinweiqi.fakeloc.core.Geo
import io.github.cobylinweiqi.fakeloc.core.SpoofConfig
import java.io.File
import java.util.Locale

/**
 * Config visible to hooks inside the target process.
 *
 * [preload] runs before specialization, while the module directory is still
 * readable, and that snapshot is what this process uses. The manager writes
 * the same file; a new value is picked up the next time the process starts.
 * `Settings.Global` is not used, because every app can read it.
 */
internal object HookState {

    private const val TAG = "State"
    private const val STATE_CONFIG = "/data/adb/fakeloc/config.json"

    @Volatile
    private var current: SpoofConfig = SpoofConfig()

    @Volatile
    private var processPackage: String? = null

    private val driftEngine = Geo.DriftEngine()

    fun preload(moduleDir: String?) {
        // The module directory is replaced on every install, so the copy there
        // disappears after a reboot. The manager's own file survives that.
        val files = ArrayList<File>(2)
        if (!moduleDir.isNullOrEmpty()) files.add(File(moduleDir, "config.json"))
        files.add(File(STATE_CONFIG))
        for (file in files) {
            val text = runCatching { if (file.isFile) file.readText() else null }.getOrNull()
            if (text.isNullOrEmpty()) continue
            val decoded = runCatching { ConfigCodec.decode(text) }.getOrElse {
                HookKit.error(TAG, "snapshot unreadable at ${file.path}", it)
                null
            } ?: continue
            current = decoded
            HookKit.info(TAG, "snapshot loaded from ${file.path}")
            return
        }
        HookKit.info(TAG, "no snapshot")
    }

    @Synchronized
    fun init(packageName: String?) {
        processPackage = packageName
        HookKit.info(TAG, "state ready for $packageName")
        HookKit.info(TAG, selfCheck())
        if (!current.spoofs()) {
            HookKit.warn(TAG, "spoofing is off — this process will report its real position until it is turned on")
        }
    }

    fun selfCheck(): String = buildString {
        val cfg = current
        append("playing=").append(cfg.playing)
        append(" mapSdkCompat=").append(cfg.mapSdkCompat)
        append(" strictSources=").append(cfg.strictSources)
        append(" antiMock=").append(cfg.antiMock)
        append(" syncAddress=").append(cfg.syncAddress)
        append(" anchor=")
        append(String.format(Locale.US, "%.6f,%.6f", cfg.latitude, cfg.longitude))
        if (cfg.addrCity.isNotBlank()) append(" fakeCity=").append(cfg.addrCity)
    }

    fun config(): SpoofConfig = current

    fun active(): Boolean = config().spoofs()

    fun antiMockEngaged(): Boolean = config().let { it.antiMock && it.playing }

    fun sourceLockEngaged(): Boolean {
        val cfg = config()
        // Cutting Wi-Fi, cell and raw GNSS before any fake fix has been handed
        // to the app makes the SDK give up. Feishu then shows 定位失败 instead
        // of a position. Hold the lock until a synthetic callback has landed.
        return cfg.strictSources && cfg.spoofs() && SdkDispatch.hasDelivered()
    }

    fun mapSdkEngaged(): Boolean {
        val cfg = config()
        return cfg.mapSdkCompat && cfg.spoofs()
    }

    fun packageName(): String? = processPackage

    /**
     * One switch for every log line, in the hooked app and in `system_server`.
     * [SpoofConfig.log] defaults to off, so a target cannot read these lines
     * back out of its own logcat.
     */
    fun mayLog(): Boolean = config().log

    fun position(): Pair<Double, Double> {
        val cfg = config()
        if (!cfg.driftEnabled || cfg.driftRadiusMeters <= 0.0) {
            driftEngine.reset()
            return cfg.latitude to cfg.longitude
        }
        return driftEngine.position(cfg, SystemClock.elapsedRealtime())
    }

    fun latitude(): Double = position().first

    fun longitude(): Double = position().second

    fun positionIn(datum: Datum): Pair<Double, Double> {
        if (datum == Datum.WGS84) return position()
        val (lat, lng) = position()
        return Coords.fromWgs84(lat, lng, datum)
    }

    private val KNOWN_PROVIDERS = setOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
        LocationManager.FUSED_PROVIDER,
    )

    fun buildLocation(original: Location?, provider: String? = null): Location {
        val resolvedProvider = when {
            provider != null && provider in KNOWN_PROVIDERS -> provider
            original != null && original.provider in KNOWN_PROVIDERS -> original.provider
            else -> LocationManager.GPS_PROVIDER
        }
        val fake = Location(resolvedProvider)
        val originalTime = original?.time ?: 0L
        val originalIsFresh = originalTime > 0L &&
            System.currentTimeMillis() - originalTime <= FIX_FRESH_WINDOW_MS
        if (original != null) {
            fake.bearing = original.bearing
            fake.bearingAccuracyDegrees = original.bearingAccuracyDegrees
        }
        if (originalIsFresh) {
            fake.time = originalTime
            val originalElapsed = original?.elapsedRealtimeNanos ?: 0L
            fake.elapsedRealtimeNanos = if (originalElapsed > 0L) {
                originalElapsed
            } else {
                SystemClock.elapsedRealtimeNanos() - FIX_LAG_NANOS
            }
        } else {
            stampTiming(fake)
        }
        applyTo(fake)
        // AMap treats a GPS fix as mocked when extras have no satellite count,
        // or when altitude, bearing and speed are all zero while it has not
        // seen a satellite. Feishu then drops the callback and shows 定位失败.
        // Probe only prints the coordinate, so it never hits this check.
        if (fake.extras?.getInt("satellites") ?: 0 <= 0) {
            val extras = fake.extras ?: android.os.Bundle()
            extras.putInt("satellites", 12)
            fake.extras = extras
        }
        if (fake.bearing == 0f) fake.bearing = 1f
        return fake
    }

    fun stampTiming(location: Location) {
        location.time = System.currentTimeMillis() - FIX_LAG_MS
        location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - FIX_LAG_NANOS
    }

    fun applyTo(location: Location, datum: Datum = Datum.WGS84) {
        val cfg = config()
        val (lat, lng) = positionIn(datum)
        location.latitude = lat
        location.longitude = lng
        if (cfg.useAccuracy) {
            location.accuracy = cfg.accuracy
            location.verticalAccuracyMeters = cfg.accuracy * VERTICAL_ACCURACY_RATIO
        }
        if (cfg.useAltitude) {
            location.altitude = cfg.altitude
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                location.mslAltitudeMeters = cfg.altitude
                location.mslAltitudeAccuracyMeters = MSL_ALTITUDE_ACCURACY
            }
        }
        if (cfg.useSpeed) {
            location.speed = cfg.speed
            location.speedAccuracyMetersPerSecond = SPEED_ACCURACY
        }
        if (cfg.antiMock) {
            MockTrace.scrub(location)
        }
    }

    fun normaliseProvider(candidate: String?): String =
        when (candidate) {
            null -> LocationManager.GPS_PROVIDER
            in KNOWN_PROVIDERS -> candidate
            else -> LocationManager.GPS_PROVIDER
        }

    private const val SPEED_ACCURACY = 0.6f
    private const val VERTICAL_ACCURACY_RATIO = 1.6f
    private const val MSL_ALTITUDE_ACCURACY = 3.0f
    private const val FIX_LAG_MS = 250L
    private const val FIX_LAG_NANOS = 250_000_000L
    private const val FIX_FRESH_WINDOW_MS = 5_000L
}
