package app.recordshelf

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
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
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
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
        const val EXTRA_OPEN_NOW = "openNow"
    }

    private lateinit var web: WebView
    private var pageReady = false
    private var insetsJson = "{\"top\":0,\"bottom\":0,\"ime\":0}"
    private var openSpinWhenReady = false
    private var openNowWhenReady = false

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val saveBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) Core.backupTo(uri) }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) Core.restoreFrom(uri) }

    override fun pickBackup(save: Boolean, name: String) = runOnUiThread {
        try {
            if (save) saveBackup.launch(name) else openBackup.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        } catch (_: Exception) {
            Core.toast("No app on this phone can pick files")
        }
    }

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
            settings.allowContentAccess = false
            // Covers and photos never change at their address: a copy kept from before is used even
            // when its cache time is up, so pages don't wait on (or blink for) pictures already seen.
            settings.cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
                }

                // The app's page never goes anywhere else; a web link opens in the browser.
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString().orEmpty()
                    if (url.startsWith("file:///android_asset/ui/")) return false
                    openUrl(url)
                    return true
                }

                // The page's process went (killed for memory, or crashed): start this screen again, not the whole app.
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    (view?.parent as? android.view.ViewGroup)?.removeView(view)
                    view?.destroy()
                    recreate()
                    return true
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

        // Before Android 13 the app takes every back press; from 13 on only while the page has
        // somewhere to go back to (see setCanGoBack), so Android can show its own way out.
        if (Build.VERSION.SDK_INT < 33) {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = goBack()
            })
        }
        // Brought back from Recents (or after Android ended the app), the intent is the old one: a TIDAL login link or a notification tap already handled.
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        // Back from TIDAL's login page (recordshelf://tidal?code=…).
        val data = i?.data
        if (data != null && data.scheme == "recordshelf" && data.host == "tidal") {
            i.data = null
            Core.handle(JSONObject().put("t", "tidalRedirect").put("uri", data.toString()))
        }
        if (i?.getBooleanExtra(EXTRA_OPEN_SPIN, false) == true) {
            i.removeExtra(EXTRA_OPEN_SPIN)
            if (pageReady) push("onOpenSpin", "true") else openSpinWhenReady = true
        }
        // From the widget while the phone plays something.
        if (i?.getBooleanExtra(EXTRA_OPEN_NOW, false) == true) {
            i.removeExtra(EXTRA_OPEN_NOW)
            if (pageReady) push("onOpenNow", "true") else openNowWhenReady = true
        }
    }

    override fun onStart() {
        super.onStart()
        web.onResume()
        web.resumeTimers()
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
        // Hidden, the page rests: its once-a-second clock and animations stop until the app is back.
        web.onPause()
        web.pauseTimers()
        super.onStop()
        updateTiltSensor()
    }

    /**
     * The music app [pkg], on the screen its player notification opens (its now-playing screen in
     * most apps), else on its start screen.
     */
    private fun openPhoneApp(pkg: String) {
        val pi = PhoneListener.instance?.sessionActivity(pkg)
        if (pi != null) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    // Since Android 14 an app sending another app's intent says it may start an activity.
                    val opts = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    pi.send(this, 0, null, null, null, null, opts.toBundle())
                } else {
                    pi.send()
                }
                return
            } catch (_: Exception) {
            }
        }
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            try {
                startActivity(it)
            } catch (_: Exception) {
            }
        }
    }

    // ---- back ----

    private var backCallback: Any? = null
    private var backTaken = false

    /**
     * The page says whether back stays in the app (a page, sheet, story or search to close, a tab
     * to leave). Only then is back taken, so otherwise Android's back swipe previews leaving the
     * app, as in any modern app.
     */
    private fun setCanGoBack(on: Boolean) {
        if (Build.VERSION.SDK_INT < 33 || on == backTaken) return
        backTaken = on
        val cb = (backCallback ?: makeBackCallback().also { backCallback = it }) as OnBackInvokedCallback
        if (on) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
    }

    /** From Android 14 the swipe's progress goes to the page, which lets the layer it closes follow the finger. */
    @RequiresApi(33)
    private fun makeBackCallback(): OnBackInvokedCallback =
        if (Build.VERSION.SDK_INT >= 34) {
            object : OnBackAnimationCallback {
                override fun onBackStarted(e: BackEvent) = js("window.onBackStart&&onBackStart(${e.swipeEdge})")
                override fun onBackProgressed(e: BackEvent) =
                    js("window.onBackProgress&&onBackProgress(%.3f,%d)".format(java.util.Locale.ROOT, e.progress, e.swipeEdge))
                override fun onBackCancelled() = js("window.onBackCancel&&onBackCancel()")
                override fun onBackInvoked() = goBack()
            }
        } else {
            OnBackInvokedCallback { goBack() }
        }

    private fun js(code: String) {
        if (!isDestroyed) web.evaluateJavascript(code, null)
    }

    private fun goBack() {
        web.evaluateJavascript("window.onBack ? window.onBack() : false") { handled ->
            if (handled != "true") moveTaskToBack(true)
        }
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
                if (openNowWhenReady) {
                    openNowWhenReady = false
                    push("onOpenNow", "true")
                }
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
            "restartApp" -> {
                // After a restore: start fresh, so only the restored data is in memory.
                packageManager.getLaunchIntentForPackage(packageName)?.let {
                    startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
                finishAffinity()
                Runtime.getRuntime().exit(0)
            }
            "openApp" -> {
                // A streaming app's own link (spotify:search:…), else the web page.
                val uri = msg.optString("uri")
                val ok = uri.startsWith("spotify:") && try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (_: Exception) {
                    false
                }
                if (!ok) openUrl(msg.optString("url"))
            }
            "copyText" -> {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText(msg.optString("label", "Heavy Rotation"), msg.optString("text")))
                // Android 13+ shows its own "copied" note.
                if (Build.VERSION.SDK_INT < 33) Core.toast("Copied")
            }
            "shareText" -> try {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, msg.optString("text")).putExtra(Intent.EXTRA_SUBJECT, msg.optString("subject"))
                startActivity(Intent.createChooser(send, msg.optString("subject")))
            } catch (_: Exception) {
            }
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
            "canBack" -> setCanGoBack(msg.optBoolean("v"))
            "openPhoneApp" -> openPhoneApp(msg.optString("pkg"))
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
