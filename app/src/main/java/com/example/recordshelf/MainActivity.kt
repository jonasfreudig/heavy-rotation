package com.example.recordshelf

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.util.TypedValue
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

/**
 * Hosts the UI (a local web page in assets/ui) and forwards its messages to [Core]. Only
 * things that need an Activity (permission dialogs, opening the browser, the theme) are
 * handled here. Also hands the Material You palette of the current wallpaper to the page.
 */
class MainActivity : AppCompatActivity(), Core.Ui {

    companion object {
        const val EXTRA_OPEN_SPIN = "openSpin"
    }

    private lateinit var web: WebView
    private var pageReady = false
    private var insetsJson = "{\"top\":0,\"bottom\":0,\"ime\":0}"
    private var openSpinWhenReady = false

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Core.ensure(this)
        applyNightMode(Core.prefs.theme)
        super.onCreate(savedInstanceState)
        val palette = palette()
        val surface = android.graphics.Color.parseColor(palette.getJSONObject("c").optString("surface", "#141218"))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !palette.getBoolean("dark")
            isAppearanceLightNavigationBars = !palette.getBoolean("dark")
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        web = WebView(this).apply {
            setBackgroundColor(surface)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(Bridge(), "ShelfNative")
            loadUrl("file:///android_asset/ui/index.html")
        }
        setContentView(web)
        ViewCompat.setOnApplyWindowInsetsListener(web) { _, insets ->
            val d = resources.displayMetrics.density
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            insetsJson = JSONObject().put("top", bars.top / d).put("bottom", bars.bottom / d).put("ime", ime.bottom / d).toString()
            if (pageReady) push("onInsets", insetsJson)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.onBack ? window.onBack() : false") { handled ->
                    if (handled != "true") moveTaskToBack(true)
                }
            }
        })
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_OPEN_SPIN, false) == true) {
            i.removeExtra(EXTRA_OPEN_SPIN)
            if (pageReady) push("onOpenSpin", "true") else openSpinWhenReady = true
        }
    }

    override fun onStart() {
        super.onStart()
        Core.attachUi(this)
        if (pageReady) {
            pushTheme()
            Core.pushAll()
        }
    }

    override fun onResume() {
        super.onResume()
        Core.onResume()
        updateTiltSensor()
    }

    override fun onStop() {
        Core.detachUi(this)
        super.onStop()
        updateTiltSensor()
    }

    override fun push(fn: String, json: String) {
        runOnUiThread {
            if (!isDestroyed) web.evaluateJavascript("window.$fn&&window.$fn($json)", null)
        }
    }

    /** Opens web pages (Last.fm approval, Discogs, sign-up pages) in the browser. */
    override fun openUrl(url: String) {
        if (!url.startsWith("https://")) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
        }
    }

    // ---- tilt: the phone's motion for the moving covers ----

    /** The page wants motion updates (a big cover is showing). */
    private var tiltWanted = false
    private var tiltListening = false
    private val rot = FloatArray(9)
    private val orient = FloatArray(3)
    private var basePitch = Float.NaN
    private var baseRoll = 0f
    private var lastTilt = 0L

    private val tiltListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val pitch: Float
            val roll: Float
            if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                pitch = (e.values[1] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
                roll = (-e.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
            } else {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, orient)
                pitch = orient[1]
                roll = orient[2]
            }
            if (basePitch.isNaN()) {
                basePitch = pitch
                baseRoll = roll
            }
            // The rest position follows slowly, so any way of holding the phone becomes "level".
            basePitch += (pitch - basePitch) * 0.015f
            baseRoll += (roll - baseRoll) * 0.015f
            val now = SystemClock.uptimeMillis()
            if (now - lastTilt < 30) return
            lastTilt = now
            val x = ((roll - baseRoll) / 0.35f).coerceIn(-1f, 1f)
            val y = ((pitch - basePitch) / 0.35f).coerceIn(-1f, 1f)
            if (pageReady) web.evaluateJavascript("window.onTilt&&window.onTilt(%.3f,%.3f)".format(java.util.Locale.ROOT, x, y), null)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun updateTiltSensor() {
        val listen = tiltWanted && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        if (listen == tiltListening) return
        val sm = getSystemService(SensorManager::class.java) ?: return
        if (listen) {
            val s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            basePitch = Float.NaN
            tiltListening = sm.registerListener(tiltListener, s, SensorManager.SENSOR_DELAY_GAME)
        } else {
            sm.unregisterListener(tiltListener)
            tiltListening = false
        }
    }

    private fun openSettings(i: Intent): Boolean = try {
        startActivity(i)
        true
    } catch (_: Exception) {
        false
    }

    private fun pushTheme() = push("onTheme", palette().toString())

    private fun applyNightMode(mode: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }

    /**
     * Material 3 color roles of the current theme. On Android 12+ the theme follows the
     * wallpaper (dynamic color); older versions get the Material baseline.
     */
    private fun palette(): JSONObject {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val roles = mapOf(
            "primary" to "colorPrimary", "on-primary" to "colorOnPrimary",
            "primary-container" to "colorPrimaryContainer", "on-primary-container" to "colorOnPrimaryContainer",
            "secondary" to "colorSecondary", "on-secondary" to "colorOnSecondary",
            "secondary-container" to "colorSecondaryContainer", "on-secondary-container" to "colorOnSecondaryContainer",
            "tertiary" to "colorTertiary", "on-tertiary" to "colorOnTertiary",
            "tertiary-container" to "colorTertiaryContainer", "on-tertiary-container" to "colorOnTertiaryContainer",
            "error" to "colorError", "on-error" to "colorOnError",
            "error-container" to "colorErrorContainer", "on-error-container" to "colorOnErrorContainer",
            "surface" to "colorSurface", "on-surface" to "colorOnSurface", "on-surface-variant" to "colorOnSurfaceVariant",
            "surface-container-lowest" to "colorSurfaceContainerLowest", "surface-container-low" to "colorSurfaceContainerLow",
            "surface-container" to "colorSurfaceContainer", "surface-container-high" to "colorSurfaceContainerHigh",
            "surface-container-highest" to "colorSurfaceContainerHighest",
            "outline" to "colorOutline", "outline-variant" to "colorOutlineVariant",
            "inverse-surface" to "colorSurfaceInverse", "inverse-on-surface" to "colorOnSurfaceInverse",
        )
        val c = JSONObject()
        val tv = TypedValue()
        for ((role, attr) in roles) {
            val id = resources.getIdentifier(attr, "attr", packageName)
            if (id != 0 && theme.resolveAttribute(id, tv, true)) {
                val color = if (tv.resourceId != 0) getColor(tv.resourceId) else tv.data
                c.put(role, String.format("#%06x", color and 0xffffff))
            }
        }
        return JSONObject().put("dark", dark).put("c", c)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun send(json: String) {
            val msg = try {
                JSONObject(json)
            } catch (_: Exception) {
                return
            }
            runOnUiThread { onMessage(msg) }
        }
    }

    private fun onMessage(msg: JSONObject) {
        when (msg.optString("t")) {
            "hello" -> {
                pushTheme()
                push("onInsets", insetsJson)
                Core.handle(msg)
                if (openSpinWhenReady) {
                    openSpinWhenReady = false
                    push("onOpenSpin", "true")
                }
            }
            "setTheme" -> {
                val mode = msg.optString("v", "system")
                Core.prefs.theme = mode
                Core.pushState()
                // Recreates the activity with the matching Material You palette.
                applyNightMode(mode)
            }
            "openUrl" -> openUrl(msg.optString("url"))
            "openNotifAccess" -> {
                // Straight to this app's switch where Android supports it, else the list of apps.
                val direct = if (Build.VERSION.SDK_INT >= 30) {
                    Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(AndroidSettings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, PhoneListener.component(this).flattenToString())
                } else null
                if (direct == null || !openSettings(direct)) openSettings(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            "openAppInfo" -> openSettings(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", msg.optString("pkg").ifEmpty { packageName }, null)),
            )
            "openBattery" -> openSettings(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            "tilt" -> {
                tiltWanted = msg.optBoolean("on")
                updateTiltSensor()
            }
            "startSpin" -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                Core.handle(msg)
            }
            else -> Core.handle(msg)
        }
    }
}
