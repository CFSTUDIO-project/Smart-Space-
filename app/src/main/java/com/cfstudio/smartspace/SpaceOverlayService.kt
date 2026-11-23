package com.cfstudio.smartspace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class SpaceOverlayService : Service() {
    companion object {
        const val ACTION_START = "com.cfstudio.smartspace.action.START"
        const val ACTION_RESET_POSITION = "com.cfstudio.smartspace.action.RESET_POSITION"
        private const val CHANNEL_ID = "smart_space_overlay"
        private const val NOTIFICATION_ID = 7201
    }

    private lateinit var windows: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("smart_space", Context.MODE_PRIVATE) }
    private val gold = Color.rgb(232, 188, 73)
    private val goldSoft = Color.rgb(250, 214, 126)
    private val muted = Color.rgb(158, 165, 176)
    private val panelColor = Color.rgb(14, 17, 22)
    private val tileColor = Color.rgb(27, 31, 39)
    private var handleView: View? = null
    private var panelView: View? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var contentHost: FrameLayout? = null
    private var dockView: LinearLayout? = null
    private var cpuGauge: GaugeView? = null
    private var gpuGauge: GaugeView? = null
    private var telemetry: TextView? = null
    private var sessionValue: TextView? = null
    private var batteryValue: TextView? = null
    private var refreshValue: TextView? = null
    private var currentMode = "performance"
    private var torchOn = false
    private var sessionStarted = 0L
    private val monitor by lazy { DeviceMonitor(this) }

    private val refreshTask = object : Runnable {
        override fun run() {
            if (panelView != null) refreshTelemetry()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (intent?.action == ACTION_RESET_POSITION) {
            val overlayWasActive = prefs.getBoolean("overlay_running", false) ||
                handleView != null || panelView != null
            if (overlayWasActive) {
                resetPositions()
            } else {
                prefs.edit().remove("handle_x").remove("handle_y")
                    .remove("panel_x").remove("panel_y").apply()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            prefs.edit().putBoolean("overlay_running", false)
                .putBoolean("overlay_window_ready", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }

        if (prefs.getLong("session_started_elapsed", 0L) <= 0L) {
            prefs.edit().putLong("session_started_elapsed", SystemClock.elapsedRealtime()).apply()
        }
        sessionStarted = prefs.getLong("session_started_elapsed", SystemClock.elapsedRealtime())
        prefs.edit().putBoolean("overlay_running", true)
            .putBoolean("overlay_window_ready", false).apply()

        if (handleView == null && panelView == null) showHandle()
        handler.removeCallbacks(refreshTask)
        handler.post(refreshTask)
        return START_STICKY
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Smart Space is active")
                .setContentText("Tap the edge handle to open the control layer.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
            startForeground(NOTIFICATION_ID, notification)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, Notification.Builder(this)
                .setContentTitle("Smart Space is active")
                .setContentText("Tap the edge handle to open the control layer.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Smart Space overlay", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the user-enabled Smart Space control layer available."
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun showHandle() {
        if (handleView != null || !Settings.canDrawOverlays(this)) return
        val width = dp(38)
        val height = dp(96)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val savedX = prefs.getInt("handle_x", screenW - width - dp(3))
        val savedY = prefs.getInt("handle_y", screenH / 2 - height / 2)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            contentDescription = "Smart Space edge handle. Drag to move, tap to open."
        }
        val line = View(this).apply {
            background = GradientDrawable().apply {
                setColor(gold)
                cornerRadius = dp(5).toFloat()
            }
            elevation = dp(7).toFloat()
            isClickable = false
        }
        root.addView(line, FrameLayout.LayoutParams(dp(4), dp(62), Gravity.CENTER))

        val params = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = savedX.coerceIn(0, max(0, screenW - width))
            y = savedY.coerceIn(0, max(0, screenH - height))
        }

        root.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var dragged = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = params.x
                        startY = params.y
                        dragged = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (abs(dx) > dp(7) || abs(dy) > dp(7)) dragged = true
                        if (dragged) {
                            params.x = (startX + dx.toInt()).coerceIn(0, max(0, resources.displayMetrics.widthPixels - width))
                            params.y = (startY + dy.toInt()).coerceIn(0, max(0, resources.displayMetrics.heightPixels - height))
                            try { windows.updateViewLayout(root, params) } catch (_: Exception) { }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragged) {
                            prefs.edit().putInt("handle_x", params.x).putInt("handle_y", params.y).apply()
                        } else {
                            view.performClick()
                            openPanel()
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> return true
                }
                return true
            }
        })

        try {
            windows.addView(root, params)
            handleView = root
            handleParams = params
            prefs.edit().putBoolean("overlay_running", true)
                .putBoolean("overlay_window_ready", true).apply()
        } catch (e: Exception) {
            prefs.edit().putBoolean("overlay_running", false)
                .putBoolean("overlay_window_ready", false).apply()
            Toast.makeText(this, "Smart Space could not attach its edge handle.", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun resetPositions() {
        prefs.edit().remove("handle_x").remove("handle_y")
            .remove("panel_x").remove("panel_y").apply()
        handleView?.let { try { windows.removeView(it) } catch (_: Exception) { } }
        handleView = null
        handleParams = null
        val panel = panelView
        val params = panelParams
        if (panel != null && params != null) {
            params.x = max(0, (resources.displayMetrics.widthPixels - params.width) / 2)
            params.y = max(0, (resources.displayMetrics.heightPixels - params.height) / 2)
            try { windows.updateViewLayout(panel, params) } catch (_: Exception) { }
        } else {
            showHandle()
        }
        Toast.makeText(this, "Smart Space positions reset.", Toast.LENGTH_SHORT).show()
    }

    private fun openPanel() {
        if (panelView != null) return
        handleView?.let { try { windows.removeView(it) } catch (_: Exception) { } }
        handleView = null
        prefs.edit().putBoolean("overlay_window_ready", false).apply()

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val layoutMode = prefs.getString("layout_mode", "dual") ?: "dual"
        val dual = layoutMode != "full"
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val widthLimit = if (landscape) dp(820) else dp(440)
        val width = min((screenW * if (landscape) 0.94f else 0.96f).toInt(), widthLimit)
            .coerceAtLeast(dp(300))
        val heightLimit = if (landscape) dp(700) else dp(780)
        val height = min((screenH * 0.88f).toInt(), heightLimit).coerceAtLeast(dp(390))

        val root = buildPanel(dual, width, height, landscape)
        val params = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = prefs.getInt("panel_x", max(0, (screenW - width) / 2))
                .coerceIn(0, max(0, screenW - width))
            y = prefs.getInt("panel_y", max(0, (screenH - height) / 2))
                .coerceIn(0, max(0, screenH - height))
        }

        try {
            windows.addView(root, params)
            panelView = root
            panelParams = params
            prefs.edit().putBoolean("overlay_running", true)
                .putBoolean("overlay_window_ready", true).apply()
            root.alpha = 0f
            root.translationY = dp(12).toFloat()
            root.animate().alpha(1f).translationY(0f).setDuration(260L)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            renderMode("performance")
            refreshTelemetry()
            handler.removeCallbacks(refreshTask)
            handler.post(refreshTask)
        } catch (e: Exception) {
            panelView = null
            panelParams = null
            showHandle()
            Toast.makeText(this, "Smart Space panel could not open.", Toast.LENGTH_LONG).show()
        }
    }

    private fun closePanel() {
        val panel = panelView ?: return
        panel.animate().alpha(0f).translationY(dp(10).toFloat()).setDuration(190L)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                try { windows.removeView(panel) } catch (_: Exception) { }
                panelView = null
                panelParams = null
                contentHost = null
                dockView = null
                cpuGauge = null
                gpuGauge = null
                telemetry = null
                sessionValue = null
                batteryValue = null
                refreshValue = null
                prefs.edit().putBoolean("overlay_window_ready", true).apply()
                showHandle()
            }.start()
    }

    private fun buildPanel(dual: Boolean, width: Int, height: Int, landscape: Boolean): View {
        val opacity = prefs.getInt("opacity", 96).coerceIn(72, 100) / 100f
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(Color.TRANSPARENT)
        }

        val dock = buildDock()
        dockView = dock
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.argb((255 * opacity).toInt(), Color.red(panelColor), Color.green(panelColor), Color.blue(panelColor)))
                cornerRadius = dp(25).toFloat()
                setStroke(dp(1), Color.rgb(61, 57, 45))
            }
            elevation = dp(12).toFloat()
        }

        if (dual && (prefs.getString("dock_side", "left") ?: "left") == "left") {
            shell.addView(dock, LinearLayout.LayoutParams(dp(if (landscape) 76 else 68), -1).apply { marginEnd = dp(7) })
        }
        shell.addView(main, LinearLayout.LayoutParams(0, -1, 1f))
        if (dual && (prefs.getString("dock_side", "left") ?: "left") == "right") {
            shell.addView(dock, LinearLayout.LayoutParams(dp(if (landscape) 76 else 68), -1).apply { marginStart = dp(7) })
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val dragZone = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            contentDescription = "Drag this title area to move the Smart Space panel."
            isClickable = true
        }
        dragZone.addView(label("SMART SPACE", 17f, Color.WHITE, true))
        dragZone.addView(label(if (dual) "PERFORMANCE  /  CONTROL LAYER" else "FULL CONTROL PANEL", 8.5f, gold, true).apply {
            letterSpacing = 0.11f
        }, topMargin(4))
        attachPanelDrag(dragZone)
        header.addView(dragZone, LinearLayout.LayoutParams(0, -2, 1f))

        val live = label("LIVE", 9f, Color.rgb(126, 217, 158), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = rounded(Color.rgb(25, 52, 40), 8f)
        }
        header.addView(live)
        val close = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.WHITE)
            contentDescription = "Close Smart Space"
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { closePanel() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(40), dp(40)))
        main.addView(header)
        main.addView(divider(), topMargin(1))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        contentHost = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
        }
        scroll.addView(contentHost, ViewGroup.LayoutParams(-1, -2))
        main.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        footer.addView(label(if (dual) "DRAG TITLE TO MOVE" else "MOVE FROM TITLE  ·  RESIZE CORNER", 8f, muted, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        val grip = ImageView(this).apply {
            setImageResource(R.drawable.ic_resize)
            setColorFilter(gold)
            background = rounded(Color.rgb(43, 38, 27), 8f)
            contentDescription = "Resize panel"
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setOnTouchListener(resizeTouchListener())
        }
        footer.addView(grip, LinearLayout.LayoutParams(dp(38), dp(34)))
        main.addView(footer)
        shell.contentDescription = "Smart Space V3 floating control surface"
        shell.minimumHeight = height
        return shell
    }

    private fun buildDock(): LinearLayout {
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(7), dp(13), dp(7), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(9, 12, 17))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.rgb(45, 49, 57))
            }
            elevation = dp(10).toFloat()
        }

        val logo = label("SS", 14f, goldSoft, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(35, 31, 22), 12f)
            letterSpacing = 0.04f
        }
        dock.addView(logo, LinearLayout.LayoutParams(dp(42), dp(42)).apply { bottomMargin = dp(18) })
        dock.addView(dockButton(R.drawable.ic_chip, "PERF", "performance"))
        dock.addView(dockButton(R.drawable.ic_controls, "TOOLS", "tools"))
        val separator = View(this).apply { setBackgroundColor(Color.rgb(54, 57, 65)) }
        dock.addView(separator, LinearLayout.LayoutParams(dp(34), dp(1)).apply {
            topMargin = dp(13); bottomMargin = dp(12)
        })

        if (prefs.getBoolean("show_app_shortcuts", true)) {
            addDockApps(dock)
        } else {
            val note = label("APPS OFF", 7.5f, muted, true).apply { gravity = Gravity.CENTER }
            dock.addView(note, LinearLayout.LayoutParams(-1, dp(36)))
        }
        val spacer = View(this)
        dock.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
        dock.addView(label("V3", 8f, muted, true).apply { gravity = Gravity.CENTER })
        return dock
    }

    private fun dockButton(iconRes: Int, title: String, mode: String): View {
        val button = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(9), dp(2), dp(9))
            background = rounded(if (mode == currentMode) Color.rgb(48, 40, 24) else Color.TRANSPARENT, 12f)
            isClickable = true
            isFocusable = true
            contentDescription = title
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(if (mode == currentMode) goldSoft else muted)
        }
        button.addView(icon, LinearLayout.LayoutParams(dp(27), dp(27)))
        button.addView(label(title, 7.5f, if (mode == currentMode) goldSoft else muted, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.02f
        }, topMargin(5))
        button.setOnClickListener { renderMode(mode) }
        return button
    }

    private fun addDockApps(dock: LinearLayout) {
        val apps = listOf(
            Triple("com.android.chrome", "Chrome", android.R.drawable.ic_menu_view),
            Triple("com.zhiliaoapp.musically", "TikTok", android.R.drawable.ic_media_play),
            Triple("com.whatsapp", "WhatsApp", android.R.drawable.ic_menu_send),
            Triple("com.google.android.youtube", "YouTube", android.R.drawable.ic_media_play)
        )
        var added = 0
        apps.forEach { app ->
            try {
                val info = packageManager.getApplicationInfo(app.first, 0)
                val launch = packageManager.getLaunchIntentForPackage(app.first)
                if (launch != null) {
                    val tile = FrameLayout(this).apply {
                        background = rounded(Color.rgb(25, 29, 36), 11f)
                        isClickable = true
                        isFocusable = true
                        contentDescription = "Open " + app.second
                        setOnClickListener {
                            try {
                                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(launch)
                            } catch (_: Exception) {
                                Toast.makeText(this@SpaceOverlayService, "Could not open " + app.second, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    val image = ImageView(this).apply {
                        setImageDrawable(packageManager.getApplicationIcon(info))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    tile.addView(image, FrameLayout.LayoutParams(dp(29), dp(29), Gravity.CENTER))
                    dock.addView(tile, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                        topMargin = dp(7)
                    })
                    added++
                }
            } catch (_: Exception) { }
        }
        if (added == 0) {
            dock.addView(label("NO\nAPPS", 7.5f, muted, true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, dp(40)))
        }
    }

    private fun attachPanelDrag(view: View) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var dragged = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = panelParams ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = params.x
                        startY = params.y
                        dragged = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (abs(dx) > dp(5) || abs(dy) > dp(5)) dragged = true
                        if (dragged) {
                            params.x = (startX + dx.toInt()).coerceIn(0, max(0, resources.displayMetrics.widthPixels - params.width))
                            params.y = (startY + dy.toInt()).coerceIn(0, max(0, resources.displayMetrics.heightPixels - params.height))
                            try { panelView?.let { windows.updateViewLayout(it, params) } } catch (_: Exception) { }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragged) prefs.edit().putInt("panel_x", params.x).putInt("panel_y", params.y).apply()
                        else v.performClick()
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> return true
                }
                return true
            }
        })
    }

    private fun resizeTouchListener(): View.OnTouchListener = object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startWidth = 0
        private var startHeight = 0

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = panelParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startWidth = params.width
                    startHeight = params.height
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val minWidth = dp(if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 360 else 300)
                    params.width = (startWidth + (event.rawX - downX).toInt())
                        .coerceIn(minWidth, resources.displayMetrics.widthPixels)
                    params.height = (startHeight + (event.rawY - downY).toInt())
                        .coerceIn(dp(390), resources.displayMetrics.heightPixels)
                    try { panelView?.let { windows.updateViewLayout(it, params) } } catch (_: Exception) { }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit().putInt("panel_width", params.width).putInt("panel_height", params.height).apply()
                    return true
                }
            }
            return true
        }
    }

    private fun renderMode(mode: String) {
        currentMode = when (mode) {
            "tools" -> "tools"
            "apps" -> "apps"
            else -> "performance"
        }
        val host = contentHost ?: return
        host.removeAllViews()
        cpuGauge = null
        gpuGauge = null
        telemetry = null
        sessionValue = null
        batteryValue = null
        refreshValue = null

        val screen = when (currentMode) {
            "tools" -> buildToolsView()
            "apps" -> buildAppsView()
            else -> buildPerformanceView()
        }
        host.addView(screen, FrameLayout.LayoutParams(-1, -2))
        dockView?.let {
            if (it.childCount > 1) {
                val toolsSelected = currentMode == "tools" || currentMode == "apps"
                it.getChildAt(1).background = rounded(
                    if (currentMode == "performance") Color.rgb(48, 40, 24) else Color.TRANSPARENT, 12f
                )
                it.getChildAt(2).background = rounded(
                    if (toolsSelected) Color.rgb(48, 40, 24) else Color.TRANSPARENT, 12f
                )
            }
        }
        refreshTelemetry()
    }

    private fun buildPerformanceView(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(14))
            setBackgroundColor(Color.TRANSPARENT)
        }
        content.addView(sectionHeader(R.drawable.ic_chip, "PERFORMANCE OVERVIEW"), margins(bottom = 10))

        val gauges = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(22, 25, 32), 18f)
            setPadding(dp(5), dp(10), dp(5), dp(8))
        }
        cpuGauge = GaugeView(this, "CPU LOAD")
        gpuGauge = GaugeView(this, "GPU LOAD")
        gauges.addView(cpuGauge, LinearLayout.LayoutParams(0, dp(126), 1f))
        gauges.addView(gpuGauge, LinearLayout.LayoutParams(0, dp(126), 1f))
        content.addView(gauges, margins(bottom = 10))

        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val session = statCard("SESSION", "--:--", "Service uptime")
        sessionValue = session.findViewWithTag("metric-value") as? TextView
        val fps = statCard("GAME FPS", "N/A", "Not exposed cross-app")
        val battery = statCard("BATTERY", "N/A", "Android battery level")
        batteryValue = battery.findViewWithTag("metric-value") as? TextView
        summary.addView(session, LinearLayout.LayoutParams(0, dp(94), 1f).apply { marginEnd = dp(5) })
        summary.addView(fps, LinearLayout.LayoutParams(0, dp(94), 1f).apply { marginEnd = dp(5) })
        summary.addView(battery, LinearLayout.LayoutParams(0, dp(94), 1f))
        content.addView(summary, margins(bottom = 10))

        val refresh = statCard("DISPLAY", "N/A", "Refresh rate · Hz")
        refreshValue = refresh.findViewWithTag("metric-value") as? TextView
        content.addView(refresh, margins(bottom = 10))

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = rounded(tileColor, 15f)
        }
        details.addView(label("LIVE DEVICE READINGS", 9f, gold, true))
        telemetry = label("Reading real device counters…", 11f, Color.rgb(226, 229, 235), false).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        details.addView(telemetry, topMargin(9))
        content.addView(details, margins(bottom = 10))

        val note = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = rounded(Color.rgb(33, 30, 24), 14f)
        }
        note.addView(label("NO SYNTHETIC PERFORMANCE", 9f, goldSoft, true))
        note.addView(label("Game FPS is shown as N/A unless Android provides a real per-game source. Smart Space never substitutes display refresh rate for game FPS.", 10f, muted, false),
            topMargin(6))
        content.addView(note)
        return content
    }

    private fun buildToolsView(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(14))
            setBackgroundColor(Color.TRANSPARENT)
        }
        content.addView(sectionHeader(R.drawable.ic_controls, "QUICK DEVICE TOOLS"), margins(bottom = 10))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = listOf(
            listOf(Triple("Wi-Fi", R.drawable.ic_wifi, "Internet settings"), Triple("Bluetooth", R.drawable.ic_bluetooth, "Bluetooth settings")),
            listOf(Triple("Flashlight", R.drawable.ic_flashlight, "Camera torch"), Triple("Rotation", R.drawable.ic_rotate, "Display settings")),
            listOf(Triple("DND", R.drawable.ic_dnd, "Do Not Disturb"), Triple("Battery", R.drawable.ic_battery, "Power settings")),
            listOf(Triple("Settings", R.drawable.ic_settings, "Android settings"), Triple("Apps", R.drawable.ic_apps, "Installed shortcuts"))
        )
        rows.forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { item ->
                line.addView(controlTile(item.first, item.second, item.third),
                    LinearLayout.LayoutParams(0, dp(86), 1f).apply { marginEnd = dp(6); bottomMargin = dp(6) })
            }
            grid.addView(line)
        }
        content.addView(grid)

        content.addView(sectionHeader(R.drawable.ic_brightness, "SCREEN BRIGHTNESS"), margins(top = 13, bottom = 4))
        content.addView(brightnessControl(), margins(bottom = 8))
        content.addView(sectionHeader(R.drawable.ic_volume, "MEDIA VOLUME"), margins(top = 10, bottom = 4))
        content.addView(volumeControl(), margins(bottom = 8))
        content.addView(label("Wi-Fi and Bluetooth open Android's own controls. Rotation opens display settings. These are system hand-offs, not fake switches.", 10f, muted, false),
            margins(top = 7, bottom = 8))
        return content
    }

    private fun controlTile(title: String, iconRes: Int, hint: String): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(8), dp(9))
            background = GradientDrawable().apply {
                setColor(tileColor)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.rgb(48, 53, 62))
            }
            isClickable = true
            isFocusable = true
            contentDescription = title + ". " + hint
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(gold)
        }
        tile.addView(icon, LinearLayout.LayoutParams(dp(25), dp(25)).apply { marginEnd = dp(9) })
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(label(title, 10f, Color.WHITE, true))
        labels.addView(label(hint, 8f, muted, false), topMargin(3))
        tile.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        tile.setOnClickListener {
            when (title) {
                "Wi-Fi" -> openInternetPanel()
                "Bluetooth" -> launchSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
                "Flashlight" -> toggleTorch()
                "Rotation" -> launchSettings(Settings.ACTION_DISPLAY_SETTINGS)
                "DND" -> toggleDnd()
                "Battery" -> launchSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                "Settings" -> launchSettings(Settings.ACTION_SETTINGS)
                "Apps" -> renderMode("apps")
            }
        }
        return tile
    }

    private fun brightnessControl(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(8))
            background = rounded(tileColor, 14f)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(label("SYSTEM LEVEL", 8f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
        val value = label("", 9f, gold, true).apply { gravity = Gravity.END }
        top.addView(value)
        wrap.addView(top)

        val current = try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) } catch (_: Exception) { 128 }
        value.text = if (Settings.System.canWrite(this)) current.toString() + " / 255" else "PERMISSION NEEDED"
        val slider = SeekBar(this).apply {
            max = 255
            progress = current.coerceIn(1, 255)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, amount: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    if (Settings.System.canWrite(this@SpaceOverlayService)) {
                        val level = amount.coerceAtLeast(1)
                        try {
                            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
                            value.text = level.toString() + " / 255"
                        } catch (_: Exception) { }
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { }
                override fun onStopTrackingTouch(bar: SeekBar?) { }
            })
        }
        wrap.addView(slider, topMargin(5))
        if (!Settings.System.canWrite(this)) {
            wrap.setOnClickListener {
                launchSettings(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:" + packageName)
            }
        }
        return wrap
    }

    private fun volumeControl(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(7))
            background = rounded(tileColor, 14f)
        }
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(label("MUSIC STREAM", 8f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
        val value = label(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toString() + " / " + maxVolume, 9f, gold, true)
        top.addView(value)
        wrap.addView(top)
        val slider = SeekBar(this).apply {
            max = maxVolume
            progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, amount: Int, fromUser: Boolean) {
                    if (fromUser) {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, amount, 0)
                        value.text = amount.toString() + " / " + maxVolume
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { }
                override fun onStopTrackingTouch(bar: SeekBar?) { }
            })
        }
        wrap.addView(slider, topMargin(5))
        return wrap
    }

    private fun buildAppsView(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(14))
            setBackgroundColor(Color.TRANSPARENT)
        }
        content.addView(sectionHeader(R.drawable.ic_apps, "INSTALLED APP SHORTCUTS"), margins(bottom = 10))
        content.addView(label("Only apps found on this device are shown below.", 10f, muted, false),
            margins(bottom = 10))
        content.addView(buildAppsGrid())
        content.addView(label("App shortcuts are launched directly. Smart Space does not read their private data.",
            9f, muted, false), margins(top = 10))
        val back = TextView(this).apply {
            text = "BACK TO DEVICE TOOLS"
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(gold)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(13), dp(12), dp(13))
            background = rounded(Color.rgb(43, 38, 27), 10f)
            setOnClickListener { renderMode("tools") }
        }
        content.addView(back, margins(top = 16))
        return content
    }

    private fun buildAppsGrid(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val apps = listOf(
            "com.android.chrome" to "Chrome",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.whatsapp" to "WhatsApp",
            "com.google.android.youtube" to "YouTube"
        )
        var count = 0
        apps.forEach { pair ->
            try {
                val info = packageManager.getApplicationInfo(pair.first, 0)
                val launch = packageManager.getLaunchIntentForPackage(pair.first)
                if (launch != null) {
                    val tile = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(4), dp(7), dp(4), dp(7))
                        background = rounded(tileColor, 12f)
                        setOnClickListener {
                            try {
                                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(launch)
                            } catch (_: Exception) { }
                        }
                    }
                    val image = ImageView(this).apply {
                        setImageDrawable(packageManager.getApplicationIcon(info))
                    }
                    tile.addView(image, LinearLayout.LayoutParams(dp(28), dp(28)))
                    tile.addView(label(pair.second, 8f, Color.WHITE, true).apply { gravity = Gravity.CENTER }, topMargin(4))
                    row.addView(tile, LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginEnd = dp(5) })
                    count++
                }
            } catch (_: Exception) { }
        }
        if (count == 0) row.addView(label("No selected apps are installed.", 10f, muted, false))
        return row
    }

    private fun refreshTelemetry() {
        if (panelView == null) return
        val sample = monitor.sample()
        cpuGauge?.setValue(sample.cpuPercent)
        gpuGauge?.setValue(sample.gpuPercent)

        val elapsed = (SystemClock.elapsedRealtime() - sessionStarted).coerceAtLeast(0L)
        val seconds = elapsed / 1000L
        val session = String.format(java.util.Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
        sessionValue?.text = session
        batteryValue?.text = sample.batteryPercent?.let { it.toString() + "%" } ?: "N/A"

        val hz = try { windows.defaultDisplay.refreshRate } catch (_: Exception) { 0f }
        refreshValue?.text = if (hz > 0f) hz.roundToInt().toString() + " Hz" else "N/A"

        val cpuClock = sample.cpuMHz?.let { it.toString() + " MHz" } ?: "N/A"
        val gpuClock = sample.gpuMHz?.let { it.toString() + " MHz" } ?: "N/A"
        val ram = if (sample.totalRamMb > 0) {
            String.format(java.util.Locale.US, "%.1f / %.1f GB", sample.usedRamMb / 1024f, sample.totalRamMb / 1024f)
        } else "N/A"
        val batteryTemp = sample.batteryTemperatureC?.let { it.toString() + " °C" } ?: "N/A"

        telemetry?.text = "CPU CLOCK      " + cpuClock +
            "\nGPU CLOCK      " + gpuClock +
            "\nRAM USED      " + ram +
            "\nBATTERY TEMP      " + batteryTemp +
            "\nTHERMAL STATUS      " + sample.thermalStatus +
            "\nGAME FPS      N/A  ·  No public cross-app source"
    }

    private fun toggleDnd() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!manager.isNotificationPolicyAccessGranted) {
            launchSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            Toast.makeText(this, "Grant Notification Policy Access, then tap DND again.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val dndOn = manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
            manager.setInterruptionFilter(
                if (dndOn) NotificationManager.INTERRUPTION_FILTER_NONE
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            Toast.makeText(this, if (dndOn) "Do Not Disturb ON" else "Do Not Disturb OFF", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Android did not allow the DND change.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleTorch() {
        try {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                launchSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:" + packageName)
                Toast.makeText(this, "Grant Camera permission in Smart Space app settings first.", Toast.LENGTH_LONG).show()
                return
            }
            val camera = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = camera.cameraIdList.firstOrNull { id ->
                camera.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId == null) {
                Toast.makeText(this, "No camera flash is available on this device.", Toast.LENGTH_SHORT).show()
                return
            }
            torchOn = !torchOn
            camera.setTorchMode(cameraId, torchOn)
            Toast.makeText(this, if (torchOn) "Flashlight ON" else "Flashlight OFF", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            torchOn = false
            Toast.makeText(this, "Flashlight is unavailable right now.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openInternetPanel() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        launchIntent(intent)
    }

    private fun launchSettings(action: String, data: String? = null) {
        val intent = Intent(action)
        if (data != null) intent.data = android.net.Uri.parse(data)
        launchIntent(intent)
    }

    private fun launchIntent(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Android could not open that settings screen.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun statCard(title: String, value: String, hint: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(8), dp(9))
            background = rounded(tileColor, 13f)
        }
        box.addView(label(title, 7.5f, muted, true))
        val valueView = label(value, 16f, Color.WHITE, true).apply { tag = "metric-value" }
        box.addView(valueView, topMargin(8))
        box.addView(label(hint, 7.2f, muted, false), topMargin(4))
        return box
    }

    private fun sectionHeader(iconRes: Int, title: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(gold)
            contentDescription = title
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(17), dp(17)))
        row.addView(label(title, 9f, gold, true), LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(8)
        })
        val rule = View(this).apply { setBackgroundColor(Color.rgb(50, 54, 62)) }
        row.addView(rule, LinearLayout.LayoutParams(0, dp(1), 1f).apply { marginStart = dp(10) })
        return row
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    private fun rounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius)
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.rgb(48, 52, 60))
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun topMargin(value: Int) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(value)
    }

    private fun margins(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val panelWasOpen = panelView != null
        removeOverlayWindows()
        showHandle()
        if (panelWasOpen) handler.postDelayed({ openPanel() }, 120L)
    }

    private fun removeOverlayWindows() {
        handleView?.let { try { windows.removeView(it) } catch (_: Exception) { } }
        panelView?.let { try { windows.removeView(it) } catch (_: Exception) { } }
        handleView = null
        panelView = null
        handleParams = null
        panelParams = null
        contentHost = null
        dockView = null
        cpuGauge = null
        gpuGauge = null
        telemetry = null
        sessionValue = null
        batteryValue = null
        refreshValue = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTask)
        removeOverlayWindows()
        prefs.edit().putBoolean("overlay_running", false)
            .putBoolean("overlay_window_ready", false)
            .remove("session_started_elapsed").apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private inner class GaugeView(context: Context, private val title: String) : View(context) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(9).toFloat()
            strokeCap = Paint.Cap.ROUND
            color = Color.rgb(49, 53, 61)
        }
        private val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(9).toFloat()
            strokeCap = Paint.Cap.ROUND
            color = gold
        }
        private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textAlign = Paint.Align.CENTER
        }
        private var valid = false
        private var animated = 0f

        fun setValue(value: Float?) {
            valid = value != null
            if (value == null) {
                animated = 0f
                invalidate()
                return
            }
            android.animation.ValueAnimator.ofFloat(animated, value.coerceIn(0f, 100f)).apply {
                duration = 450L
                addUpdateListener {
                    animated = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f - dp(5)
            val radius = min(width, height) / 2f - dp(17)
            val bounds = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(bounds, 135f, 270f, false, track)
            if (valid) {
                progress.color = if (animated >= 85f) Color.rgb(235, 102, 82) else gold
                canvas.drawArc(bounds, 135f, 270f * animated / 100f, false, progress)
            }
            number.textSize = dp(19).toFloat()
            canvas.drawText(if (valid) animated.roundToInt().toString() + "%" else "N/A", cx, cy + dp(6), number)
            caption.textSize = dp(8).toFloat()
            canvas.drawText(title, cx, height - dp(7).toFloat(), caption)
        }
    }
}
