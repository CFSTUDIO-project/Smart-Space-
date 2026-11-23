package com.cfstudio.smartspace

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private enum class Page { AUTHORIZED, HOME, SETTINGS, ABOUT }
    private var page = Page.AUTHORIZED
    private lateinit var root: FrameLayout
    private var bootDialog: AlertDialog? = null
    private var bootRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("smart_space", Context.MODE_PRIVATE) }
    private val gold = Color.rgb(225, 181, 70)
    private val muted = Color.rgb(151, 156, 166)
    private val surface = Color.rgb(25, 28, 34)
    private val background = Color.rgb(10, 12, 15)
    private val repoUrl = "https://github.com/CFSTUDIO-project/Smart-Space-"
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            prefs.edit().putBoolean("shizuku_granted", granted).apply()
            toast(if (granted) "Shizuku access authorized." else "Shizuku access was not granted.")
            if (page == Page.AUTHORIZED) showAuthorized()
        }
    }

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 604
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility = 0
        root = FrameLayout(this)
        setContentView(root)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        showSplash()
    }

    private fun showSplash() {
        root.removeAllViews()
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }
        val mark = ImageView(this).apply {
            setImageResource(R.drawable.ic_space)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        wrap.addView(mark, LinearLayout.LayoutParams(dp(104), dp(104)))
        val title = text("SMART SPACE", 26f, Color.WHITE, true).apply {
            letterSpacing = .16f
            gravity = Gravity.CENTER
        }
        wrap.addView(title, marginParams(-1, -2, top = 24))
        wrap.addView(text("CONTROL THE EDGE.", 11f, gold, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .2f
        }, marginParams(-1, -2, top = 8))
        val rail = View(this).apply { setBackgroundColor(Color.rgb(46, 48, 54)) }
        val fill = View(this).apply { setBackgroundColor(gold) }
        val railFrame = FrameLayout(this).apply { addView(rail, FrameLayout.LayoutParams(-1, dp(3))) }
        railFrame.addView(fill, FrameLayout.LayoutParams(0, dp(3)))
        wrap.addView(railFrame, marginParams(dp(148), dp(3), top = 36))
        wrap.addView(text("INITIALIZING DEVICE INTERFACE", 9f, muted, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .12f
        }, marginParams(-1, -2, top = 14))
        root.addView(wrap, FrameLayout.LayoutParams(-1, -1))
        fill.layoutParams = FrameLayout.LayoutParams(dp(148), dp(3))
        fill.pivotX = 0f
        fill.scaleX = 0f
        fill.post {
            fill.animate().withLayer().scaleX(1f).setDuration(1050L)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
        handler.postDelayed({
            page = Page.AUTHORIZED
            showAuthorized()
        }, 1350L)
    }

    private fun showAuthorized() {
        page = Page.AUTHORIZED
        val body = makePage()
        addHeader(body, "AUTHORIZED", "FIRST-RUN CONFIGURATION")
        body.addView(text("Prepare your device", 25f, Color.WHITE, true), marginParams(-1, -2, top = 12))
        body.addView(text("Smart Space uses Android's real permission screens. Review each access below; optional tools can stay disabled.", 14f, muted), marginParams(-1, -2, top = 8, bottom = 10))
        val guide = ImageView(this).apply {
            setImageResource(R.drawable.auth_guide)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Illustration of overlay access leading to the Smart Space floating panel"
        }
        body.addView(guide, marginParams(-1, dp(142), bottom = 8))
        body.addView(text("ALLOW OVERLAY ACCESS  →  FLOATING PANEL", 9f, gold, true), marginParams(-1, -2, bottom = 15))
        addPermissionCard(body, "01", "Display over other apps", "Required. Lets the slim | trigger and panel appear above games and other apps.", overlayGranted(), "AUTHORIZE", {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else toast("Overlay access is already enabled.")
        })
        addPermissionCard(body, "02", "Notifications", "Recommended. Shows the active service notification. Android 13 and newer ask separately.", notificationGranted(), "ALLOW", {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 301)
            } else toast("Notification permission is already available.")
        })
        addPermissionCard(body, "03", "Camera / flashlight", "Optional. Camera permission is used only to control the physical flash as a torch.", cameraGranted(), "ALLOW", {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 302)
            } else toast("Camera permission is already available.")
        })
        addPermissionCard(body, "04", "Modify system settings", "Required only for the brightness slider. Android asks you to approve this special access on its own settings screen.", Settings.System.canWrite(this), "OPEN SETTINGS", {
            if (!Settings.System.canWrite(this)) {
                val accessIntent = Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName")
                )
                try {
                    startActivity(accessIntent)
                } catch (_: Exception) {
                    toast("Could not open the system-settings access page. Opening app details instead.")
                    try {
                        startActivity(Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        ))
                    } catch (_: Exception) {
                        toast("Open Smart Space app info from Android Settings.")
                    }
                }
            } else {
                toast("System settings access is already enabled.")
            }
        })
        addPermissionCard(body, "05", "Background activity", "Recommended. Review battery restrictions so Android is less likely to stop the active service.", isIgnoringBatteryOptimizations(), "REVIEW", {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })
        val shizukuInstalled = packageInstalled("moe.shizuku.privileged.api")
        val shizukuConnected = shizukuBinderReady()
        val shizukuAllowed = shizukuGranted()
        val shizukuDescription = when {
            shizukuAllowed -> "Shizuku has authorized Smart Space. This permission is optional; the current release uses it for authorization status only."
            !shizukuInstalled -> "Optional advanced access. Install Shizuku first. It is not root and is not required for the floating panel."
            !shizukuConnected -> "Shizuku is installed but its service is not running. Open Shizuku, start its service, then return here to authorize Smart Space."
            else -> "Shizuku service connected. Tap AUTHORIZE to open its real permission dialog for Smart Space."
        }
        val shizukuAction = when {
            shizukuAllowed -> "AUTHORIZED  ✓"
            !shizukuInstalled -> "INSTALL GUIDE"
            !shizukuConnected -> "OPEN SHIZUKU"
            else -> "AUTHORIZE"
        }
        addPermissionCard(body, "06", "Shizuku (optional)", shizukuDescription, shizukuAllowed, shizukuAction) {
            requestShizukuAccess()
        }
        body.addView(text("Tutorial", 17f, Color.WHITE, true), marginParams(-1, -2, top = 18))
        body.addView(text("1. Tap AUTHORIZE and enable the requested access in Android Settings.\n2. Return to Smart Space; the status refreshes automatically.\n3. When Display over other apps shows READY, tap NEXT.\n4. In the dashboard, activate Smart Space to place the | handle on your screen.", 13f, muted), marginParams(-1, -2, top = 8))
        val next = button("NEXT  →", true) {
            if (overlayGranted()) {
                showHome()
            } else {
                toast("Overlay access is required before continuing.")
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        body.addView(next, marginParams(-1, dp(54), top = 24, bottom = 10))
        body.addView(text("You stay in control. Permissions can be changed later in Android Settings.", 11f, muted), marginParams(-1, -2, bottom = 24))
        root.removeAllViews()
        root.addView(wrapScroll(body))
    }

    private fun showHome() {
        page = Page.HOME
        val body = makePage()
        addHeader(body, "SMART SPACE", "GAMING CONTROL LAYER")
        body.addView(text("Your device.\nYour control.", 30f, Color.WHITE, true), marginParams(-1, -2, top = 12))
        body.addView(text("A real floating control surface for the apps and games you use.", 14f, muted), marginParams(-1, -2, top = 8, bottom = 20))
        val active = prefs.getBoolean("overlay_running", false)
        val hero = card()
        hero.addView(text(if (active) "SPACE IS ACTIVE" else "SPACE IS STANDBY", 11f, gold, true))
        hero.addView(text(if (active) "The edge trigger is available above other apps." else "Authorize the overlay, then launch the edge trigger.", 14f, Color.WHITE), marginParams(-1, -2, top = 8))
        hero.addView(button(if (active) "DEACTIVATE SPACE" else "ACTIVATE SPACE", true) {
            if (prefs.getBoolean("overlay_running", false)) stopOverlay() else startOverlay()
            handler.postDelayed({ showHome() }, 250L)
        }, marginParams(-1, dp(50), top = 16))
        body.addView(hero, marginParams(-1, -2, bottom = 14))

        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        grid.addView(metricTile("CPU", "LIVE", "Kernel counters"), LinearLayout.LayoutParams(0, dp(94), 1f))
        grid.addView(Space(this), LinearLayout.LayoutParams(dp(10), 1))
        grid.addView(metricTile("GPU", "N/A", "When unavailable"), LinearLayout.LayoutParams(0, dp(94), 1f))
        body.addView(grid, marginParams(-1, -2, bottom = 10))
        val grid2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        grid2.addView(metricTile("RAM", "LIVE", "System memory"), LinearLayout.LayoutParams(0, dp(94), 1f))
        grid2.addView(Space(this), LinearLayout.LayoutParams(dp(10), 1))
        grid2.addView(metricTile("FPS", "N/A", "No fake readings"), LinearLayout.LayoutParams(0, dp(94), 1f))
        body.addView(grid2, marginParams(-1, -2, bottom = 16))

        val shortcuts = card()
        shortcuts.addView(text("QUICK ACCESS", 11f, gold, true))
        shortcuts.addView(text("Chrome · TikTok · WhatsApp · YouTube", 15f, Color.WHITE), marginParams(-1, -2, top = 8))
        shortcuts.addView(text("Only installed apps will appear inside the floating panel.", 12f, muted), marginParams(-1, -2, top = 5))
        body.addView(shortcuts, marginParams(-1, -2, bottom = 16))
        addNavigation(body)
        root.removeAllViews()
        root.addView(wrapScroll(body))
    }

    private fun showSettings() {
        page = Page.SETTINGS
        val body = makePage()
        addHeader(body, "SETTINGS", "PERSONALIZE YOUR SPACE")
        body.addView(text("Control the experience", 25f, Color.WHITE, true),
            marginParams(-1, -2, top = 12, bottom = 8))
        body.addView(text("Choose how the floating layer is arranged. Changes apply the next time you open the panel.",
            13f, muted), marginParams(-1, -2, bottom = 16))

        val serviceSwitch = Switch(this).apply {
            text = "Floating panel enabled"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("overlay_running", false)
            setOnCheckedChangeListener { _, checked ->
                if (checked) startOverlay() else stopOverlay()
            }
        }
        body.addView(card().apply { addView(serviceSwitch) },
            marginParams(-1, -2, bottom = 14))

        val layoutCard = card()
        layoutCard.addView(text("PANEL LAYOUT", 10f, gold, true))
        layoutCard.addView(text("Dual layout keeps the vertical app dock beside the dashboard. Full layout hides the dock and uses one wide panel.",
            12f, muted), marginParams(-1, -2, top = 7))
        val layoutRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val selectedLayout = prefs.getString("layout_mode", "dual") ?: "dual"
        layoutRow.addView(button(if (selectedLayout == "dual") "DUAL  ·  ACTIVE" else "DUAL PANEL", selectedLayout == "dual") {
            prefs.edit().putString("layout_mode", "dual").apply()
            showSettings()
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) })
        layoutRow.addView(button(if (selectedLayout == "full") "FULL  ·  ACTIVE" else "FULL PANEL", selectedLayout == "full") {
            prefs.edit().putString("layout_mode", "full").apply()
            showSettings()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        layoutCard.addView(layoutRow, marginParams(-1, -2, top = 12))
        body.addView(layoutCard, marginParams(-1, -2, bottom = 12))

        val dockCard = card()
        dockCard.addView(text("DOCK POSITION", 10f, gold, true))
        val sideRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val side = prefs.getString("dock_side", "left") ?: "left"
        sideRow.addView(button(if (side == "left") "LEFT  ·  ACTIVE" else "LEFT", side == "left") {
            prefs.edit().putString("dock_side", "left").apply()
            showSettings()
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        sideRow.addView(button(if (side == "right") "RIGHT  ·  ACTIVE" else "RIGHT", side == "right") {
            prefs.edit().putString("dock_side", "right").apply()
            showSettings()
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        dockCard.addView(sideRow, marginParams(-1, -2, top = 10))
        val shortcuts = Switch(this).apply {
            text = "Show installed app shortcuts"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("show_app_shortcuts", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("show_app_shortcuts", checked).apply()
            }
        }
        dockCard.addView(shortcuts, marginParams(-1, -2, top = 10))
        body.addView(dockCard, marginParams(-1, -2, bottom = 12))

        val appearance = card()
        appearance.addView(text("PANEL OPACITY", 10f, gold, true))
        appearance.addView(text("Adjust the panel surface transparency. Text and controls remain fully opaque.",
            12f, muted), marginParams(-1, -2, top = 6))
        val opacity = SeekBar(this).apply {
            max = 28
            progress = (prefs.getInt("opacity", 96).coerceIn(72, 100) - 72)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) prefs.edit().putInt("opacity", value + 72).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        appearance.addView(opacity, marginParams(-1, -2, top = 5))
        body.addView(appearance, marginParams(-1, -2, bottom = 12))

        body.addView(actionCard("Reset floating positions",
            "Return the edge handle and panel to their default screen positions.",
            "RESET POSITIONS") {
            try {
                val intent = Intent(this, SpaceOverlayService::class.java)
                    .setAction(SpaceOverlayService.ACTION_RESET_POSITION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
                toast("Reset request sent to Smart Space.")
            } catch (_: Exception) {
                toast("Start Smart Space first, then reset positions.")
            }
        }, marginParams(-1, -2, bottom = 12))

        body.addView(actionCard("Permissions",
            "Review overlay, camera, brightness and Do Not Disturb access.",
            "OPEN AUTHORIZED") { showAuthorized() }, marginParams(-1, -2, bottom = 12))
        body.addView(actionCard("Device power settings",
            "Review battery and background restrictions applied by your phone.",
            "OPEN SETTINGS") {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }, marginParams(-1, -2, bottom = 12))
        addNavigation(body)
        root.removeAllViews()
        root.addView(wrapScroll(body))
    }

    private fun showAbout() {
        page = Page.ABOUT
        val body = makePage()
        addHeader(body, "ABOUT SMART SPACE", "THE EDGE, REIMAGINED")
        val mark = ImageView(this).apply { setImageResource(R.drawable.ic_space); scaleType = ImageView.ScaleType.FIT_CENTER }
        body.addView(mark, marginParams(dp(82), dp(82), top = 20))
        body.addView(text("SMART SPACE", 25f, Color.WHITE, true), marginParams(-1, -2, top = 12))
        body.addView(text("A gaming companion built around one simple idea: useful controls should stay close, without taking over the screen.", 15f, muted), marginParams(-1, -2, top = 10))
        val story = card()
        story.addView(text("BUILT FOR THE MOMENT BETWEEN APPS", 11f, gold, true))
        story.addView(text("Smart Space brings a compact edge trigger, a scrollable floating panel and practical device tools into one native Android application. It is designed to adapt to portrait and landscape screens, keep interaction direct, and make every permission understandable before it is used.\n\nThe project follows a simple rule: a metric is shown only when the device provides a real source for it. CPU and memory values come from system counters. GPU telemetry depends on the kernel and vendor. Cross-app game FPS is not guessed from display refresh rate.\n\nSmart Space is an independent open-source project. Its implementation, build history and issue tracking live in the project repository.", 14f, Color.rgb(222, 224, 229)), marginParams(-1, -2, top = 12))
        body.addView(story, marginParams(-1, -2, top = 18, bottom = 14))
        body.addView(text("CREATED BY", 10f, gold, true), marginParams(-1, -2, top = 8))
        body.addView(text("CFSTUDIO-project", 18f, Color.WHITE, true), marginParams(-1, -2, top = 5))
        body.addView(button("SEE MORE  ↗", true) { openUrl(repoUrl) }, marginParams(-1, dp(52), top = 16))
        body.addView(text("Smart Space · SS · Version 3.0.0", 11f, muted), marginParams(-1, -2, top = 14, bottom = 20))
        addNavigation(body)
        root.removeAllViews()
        root.addView(wrapScroll(body))
    }

    private fun addNavigation(parent: LinearLayout) {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(22))
        }
        listOf("HOME", "SETTINGS", "ABOUT").forEach { label ->
            val selected = when (page) {
                Page.HOME -> label == "HOME"
                Page.SETTINGS -> label == "SETTINGS"
                Page.ABOUT -> label == "ABOUT"
                Page.AUTHORIZED -> false
            }
            val item = TextView(this).apply {
                text = label
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = .08f
                gravity = Gravity.CENTER
                setTextColor(if (selected) gold else muted)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                setOnClickListener {
                    when (label) {
                        "HOME" -> showHome()
                        "SETTINGS" -> showSettings()
                        "ABOUT" -> showAbout()
                    }
                }
            }
            nav.addView(item, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        parent.addView(nav, marginParams(-1, -2, top = 10))
    }

    private fun addHeader(parent: LinearLayout, title: String, eyebrow: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = ImageView(this).apply { setImageResource(R.drawable.ic_space) }
        row.addView(mark, LinearLayout.LayoutParams(dp(38), dp(38)))
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
            addView(text(title, 16f, Color.WHITE, true))
            addView(text(eyebrow, 9f, gold, true), marginParams(-1, -2, top = 3))
        }
        row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
        val status = text("SS", 10f, gold, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(Color.rgb(39, 34, 24), 10f)
        }
        row.addView(status)
        parent.addView(row, marginParams(-1, -2, bottom = 16))
        val line = View(this).apply { setBackgroundColor(Color.rgb(44, 47, 54)) }
        parent.addView(line, marginParams(-1, dp(1), bottom = 4))
    }

    private fun addPermissionCard(parent: LinearLayout, number: String, title: String, desc: String, granted: Boolean, action: String, onClick: () -> Unit) {
        val c = card()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(number, 11f, gold, true), LinearLayout.LayoutParams(dp(34), -2))
        head.addView(text(title, 15f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(text(if (granted) "READY" else "REVIEW", 9f, if (granted) Color.rgb(118, 212, 157) else gold, true))
        c.addView(head)
        c.addView(text(desc, 12f, muted), marginParams(-1, -2, top = 8))
        c.addView(button(if (granted) "READY  ✓" else action, granted) { if (!granted) onClick() }, marginParams(-1, dp(42), top = 12))
        parent.addView(c, marginParams(-1, -2, bottom = 10))
    }

    private fun actionCard(title: String, desc: String, action: String, onClick: () -> Unit): View {
        val c = card()
        c.addView(text(title, 15f, Color.WHITE, true))
        c.addView(text(desc, 12f, muted), marginParams(-1, -2, top = 6))
        c.addView(button(action, false, onClick), marginParams(-1, dp(42), top = 10))
        return c
    }

    private fun metricTile(label: String, value: String, hint: String): View {
        return card().apply {
            addView(text(label, 10f, gold, true))
            addView(text(value, 22f, Color.WHITE, true), marginParams(-1, -2, top = 8))
            addView(text(hint, 10f, muted), marginParams(-1, -2, top = 2))
        }
    }

    private fun makePage() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(24), dp(22), dp(12))
        setBackgroundColor(this@MainActivity.background)
    }

    private fun wrapScroll(content: View): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        clipToPadding = false
        addView(content, ViewGroup.LayoutParams(-1, -2))
        setBackgroundColor(this@MainActivity.background)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(surface, 18f)
    }

    private fun button(label: String, primary: Boolean, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setTextColor(if (primary) Color.rgb(22, 21, 17) else Color.WHITE)
            setBackground(rounded(if (primary) gold else Color.rgb(45, 48, 56), 13f))
            stateListAnimator = null
            setOnClickListener { action() }
        }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun rounded(color: Int, radius: Float) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius)
    }

    private fun marginParams(width: Int, height: Int, top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, height).apply {
            topMargin = top
            bottomMargin = bottom
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun shizukuBinderReady(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Exception) {
        false
    }

    private fun shizukuGranted(): Boolean = try {
        shizukuBinderReady() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    private fun requestShizukuAccess() {
        if (!packageInstalled("moe.shizuku.privileged.api")) {
            openUrl("https://shizuku.rikka.app/download/")
            return
        }
        if (!shizukuBinderReady()) {
            val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (launch != null) startActivity(launch) else openUrl("https://shizuku.rikka.app/")
            toast("Start the Shizuku service, then return to Smart Space.")
            return
        }
        try {
            if (Shizuku.isPreV11()) {
                toast("Please update Shizuku to version 11 or newer.")
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                toast("Shizuku is already authorized.")
                showAuthorized()
            } else {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            }
        } catch (e: Exception) {
            toast("Shizuku is not ready: ${e.message ?: "check its service status"}")
        }
    }

    private fun overlayGranted() = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
    private fun notificationGranted() = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun cameraGranted() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun packageInstalled(packageName: String): Boolean = try {
        packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun startOverlay() {
        if (!overlayGranted()) {
            toast("Grant Display over other apps first.")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        prefs.edit().putBoolean("overlay_window_ready", false).apply()
        showBootSequence()
        try {
            val intent = Intent(this, SpaceOverlayService::class.java).setAction(SpaceOverlayService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) {
            bootDialog?.dismiss()
            prefs.edit().putBoolean("overlay_running", false).putBoolean("overlay_window_ready", false).apply()
            toast("Could not start the overlay: ${e.message ?: "system restriction"}")
        }
    }

    private fun showBootSequence() {
        bootRunnable?.let { handler.removeCallbacks(it) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(18))
            background = rounded(Color.rgb(17, 19, 24), 22f)
        }
        panel.addView(text("SMART SPACE  /  SYSTEM START", 10f, gold, true))
        panel.addView(text("Bringing your edge online.", 22f, Color.WHITE, true), marginParams(-1, -2, top = 13))
        val status = text("INITIALIZING DEVICE LAYER", 10f, gold, true)
        status.letterSpacing = .1f
        panel.addView(status, marginParams(-1, -2, top = 19))
        val timer = text("BOOT TIME  0.0 s", 12f, muted, true)
        panel.addView(timer, marginParams(-1, -2, top = 7))
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 8
            if (Build.VERSION.SDK_INT >= 21) {
                progressTintList = android.content.res.ColorStateList.valueOf(gold)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(52, 55, 63))
            }
        }
        panel.addView(progress, marginParams(-1, dp(5), top = 16, bottom = 17))

        val stateLabels = mutableListOf<TextView>()
        listOf("OVERLAY PERMISSION", "FOREGROUND SERVICE", "FLOATING WINDOW").forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = rounded(Color.rgb(28, 31, 38), 11f)
            }
            row.addView(text(item, 10f, Color.rgb(219, 222, 228), true), LinearLayout.LayoutParams(0, -2, 1f))
            val value = text("CHECKING", 9f, muted, true).apply { gravity = Gravity.CENTER }
            stateLabels.add(value)
            row.addView(value)
            panel.addView(row, marginParams(-1, -2, bottom = 7))
        }
        val details = text("Checking the Android display layer…", 12f, muted)
        panel.addView(details, marginParams(-1, -2, top = 7, bottom = 16))
        val stop = button("STOP STARTUP", false) {
            bootDialog?.dismiss()
            stopOverlay()
        }
        panel.addView(stop, marginParams(-1, dp(46)))
        val dialog = AlertDialog.Builder(this).setView(panel).create()
        bootDialog = dialog
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            bootRunnable?.let { handler.removeCallbacks(it) }
            bootRunnable = null
            if (bootDialog === dialog) bootDialog = null
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val startedAt = SystemClock.elapsedRealtime()
        val poll = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val serviceReady = prefs.getBoolean("overlay_running", false)
                val windowReady = prefs.getBoolean("overlay_window_ready", false)
                timer.text = String.format(java.util.Locale.US, "BOOT TIME  %.1f s", elapsed / 1000f)
                val states = listOf(
                    overlayGranted(),
                    serviceReady,
                    windowReady
                )
                states.forEachIndexed { index, ready ->
                    stateLabels[index].text = if (ready) "READY" else when (index) {
                        0 -> "CHECKING"
                        1 -> if (overlayGranted()) "STARTING" else "WAITING"
                        else -> if (serviceReady) "VERIFYING" else "WAITING"
                    }
                    stateLabels[index].setTextColor(if (ready) Color.rgb(116, 211, 153) else gold)
                }
                progress.progress = when {
                    windowReady -> 100
                    serviceReady -> 72
                    overlayGranted() -> 28
                    else -> 8
                }
                when {
                    windowReady -> {
                        status.text = "STATUS  /  READY · ACTIVE"
                        status.setTextColor(Color.rgb(116, 211, 153))
                        details.text = "The floating edge trigger is now attached to the screen."
                        handler.postDelayed({
                            if (bootDialog === dialog && dialog.isShowing) {
                                dialog.dismiss()
                                if (page == Page.HOME) showHome()
                            }
                        }, 850L)
                        return
                    }
                    elapsed > 15000L -> {
                        status.text = "STATUS  /  WINDOW NOT CONFIRMED"
                        status.setTextColor(Color.rgb(239, 174, 91))
                        details.text = "Android has not confirmed the floating window. Check overlay access and your device's background restrictions."
                        stop.text = "CLOSE"
                        stop.setOnClickListener { dialog.dismiss() }
                        return
                    }
                    serviceReady -> {
                        status.text = "VERIFYING FLOATING WINDOW"
                        details.text = "Foreground service is active. Waiting for Android to attach the edge layer."
                    }
                    else -> {
                        status.text = "INITIALIZING DEVICE LAYER"
                        details.text = "Checking permission, service availability and display access."
                    }
                }
                handler.postDelayed(this, 120L)
            }
        }
        bootRunnable = poll
        handler.post(poll)
    }

    private fun stopOverlay() {
        stopService(Intent(this, SpaceOverlayService::class.java))
        prefs.edit().putBoolean("overlay_running", false).apply()
        toast("Smart Space deactivated.")
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { toast("No browser is available.") }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized && page == Page.AUTHORIZED) showAuthorized()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        when (page) {
            Page.AUTHORIZED -> showAuthorized()
            Page.HOME -> showHome()
            Page.SETTINGS -> showSettings()
            Page.ABOUT -> showAbout()
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (page != Page.HOME && page != Page.AUTHORIZED) showHome() else super.onBackPressed()
    }
}
