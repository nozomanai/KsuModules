package io.github.cobylinweiqi.fakeloc.probe

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads the platform location and prints whatever this process actually received.
 * With the package on the FakeLoc allow-list, that value is the one the module returned.
 */
class ProbeActivity : Activity() {

    private lateinit var output: TextView
    private var watching = false
    private var afterGrant: (() -> Unit)? = null
    private val lines = ArrayDeque<String>()
    private val listener = LocationListener { location -> append(location) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_probe)
        applySystemBars(findViewById(R.id.root))
        output = findViewById(R.id.output)
        findViewById<TextView>(R.id.hint).text =
            "包名 $packageName\n加入 FakeLoc 名单后，强制停止本应用再打开。"
        findViewById<Button>(R.id.read_once).setOnClickListener {
            withLocationPermission { readOnce() }
        }
        findViewById<Button>(R.id.watch).setOnClickListener {
            withLocationPermission { watch() }
        }
        output.text = "还没有读过定位。"
    }

    override fun onDestroy() {
        if (watching) {
            locationManager().removeUpdates(listener)
        }
        super.onDestroy()
    }

    private fun withLocationPermission(block: () -> Unit) {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED) {
            block()
            return
        }
        afterGrant = block
        requestPermissions(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            REQUEST_LOCATION,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCATION) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        val block = afterGrant
        afterGrant = null
        if (granted && block != null) {
            block()
        } else {
            output.text = "没有定位权限。"
        }
    }

    private fun readOnce() {
        val manager = locationManager()
        val found = ArrayList<String>()
        for (provider in providers()) {
            val location = try {
                manager.getLastKnownLocation(provider)
            } catch (error: SecurityException) {
                output.text = error.message ?: "读定位被拒绝"
                return
            }
            if (location != null) found.add(formatFix(location))
        }
        output.text = if (found.isEmpty()) {
            "这些提供方都没有缓存：${providers().joinToString()}。\n点「持续监听」等下一次回调。"
        } else {
            found.joinToString("\n\n")
        }
    }

    private fun watch() {
        val manager = locationManager()
        if (watching) {
            manager.removeUpdates(listener)
        }
        var armed = 0
        for (provider in providers()) {
            val ok = runCatching {
                manager.requestLocationUpdates(provider, 1_000L, 0f, listener, mainLooper)
            }.isSuccess
            if (ok) armed++
        }
        watching = armed > 0
        if (armed == 0) {
            output.text = "没有可用的定位提供方。"
            return
        }
        lines.clear()
        output.text = "正在监听，约每秒一次。"
    }

    private fun append(location: Location) {
        lines.addFirst(formatFix(location))
        while (lines.size > 12) lines.removeLast()
        output.text = lines.joinToString("\n\n")
    }

    private fun formatFix(location: Location): String {
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(location.time))
        return buildString {
            append(location.provider ?: "unknown")
            append("  ")
            append(clock)
            append('\n')
            append(String.format(Locale.US, "%.6f, %.6f", location.latitude, location.longitude))
            if (location.hasAccuracy()) {
                append('\n')
                append(String.format(Locale.US, "accuracy %.1f m", location.accuracy))
            }
        }
    }

    private fun providers(): List<String> {
        val names = ArrayList<String>(3)
        names.add(LocationManager.GPS_PROVIDER)
        names.add(LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            names.add(LocationManager.FUSED_PROVIDER)
        }
        return names
    }

    private fun applySystemBars(root: View) {
        val extra = (16 * resources.displayMetrics.density).toInt()
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
            } else {
                null
            }
            if (bars != null) {
                view.setPadding(
                    bars.left + extra,
                    bars.top + extra,
                    bars.right + extra,
                    bars.bottom + extra,
                )
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft + extra,
                    insets.systemWindowInsetTop + extra,
                    insets.systemWindowInsetRight + extra,
                    insets.systemWindowInsetBottom + extra,
                )
            }
            insets
        }
        root.requestApplyInsets()
    }

    private fun locationManager(): LocationManager {
        return getSystemService(LocationManager::class.java)
    }

    private companion object {
        const val REQUEST_LOCATION = 1
    }
}
