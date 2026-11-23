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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import kotlin.math.min
import kotlin.math.roundToInt

class SpaceOverlayService : Service() {
    companion object {
        const val ACTION_START = "com.cfstudio.smartspace.action.START"
        private const val CHANNEL_ID = "smart_space_overlay"
        private const val NOTIFICATION_ID = 7201
    }

    private lateinit var windows: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("smart_space", Context.MODE_PRIVATE) }
    private val gold = Color.rgb(226, 183, 73)
    private val muted = Color.rgb(154, 160, 170)
    private val panelColor = Color.rgb(17, 19, 24)
    private var handleView: View? = null
    private var panelView: View? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var cpuGauge: GaugeView? = null
    private var gpuGauge: GaugeView? = null
    private var telemetry: TextView? = null
    private var torchOn = false
    private var lastCpu = "N/A"
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Smart Space is active")
                .setContentText("Tap the edge handle to open the floating control panel.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
            startForeground(NOTIFICATION_ID, notification)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, Notification.Builder(this)
                .setContentTitle("Smart Space is active")
                .setContentText("Tap the edge handle to open the floating control panel.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build())
        }

        if (!Settings.canDrawOverlays(this)) {
            prefs.edit().putBoolean("overlay_running", false).putBoolean("overlay_window_ready", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        prefs.edit().putBoolean("overlay_running", true).putBoolean("overlay_window_ready", false).apply()
        if (handleView == null && panelView == null) showHandle()
        handler.removeCallbacks(refreshTask)
        handler.post(refreshTask)
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Smart Space overlay", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the user-enabled floating gaming control panel active."
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun showHandle() {
        if (handleView != null) return
        val density = resources.displayMetrics.density
        val width = (34 * density).toInt()
        val height = (88 * density).toInt()
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val prefsX = prefs.getInt("handle_x", screenW - width)
        val prefsY = prefs.getInt("handle_y", screenH / 2 - height / 2)
        val root = FrameLayout(this)
        val line = View(this).apply {
            background = GradientDrawable().apply {
                setColor(gold)
                cornerRadius = 8f * density
            }
            elevation = 7f * density
        }
        val lp = FrameLayout.LayoutParams((4 * density).toInt(), (58 * density).toInt(), Gravity.CENTER)
        root.addView(line, lp)
        val params = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = prefsX.coerceIn(0, (screenW - width).coerceAtLeast(0))
            y = prefsY.coerceIn(0, (screenH - height).coerceAtLeast(0))
        }
        root.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = params.x
                        startY = params.y
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (abs(dx) > 5 * density || abs(dy) > 5 * density) moved = true
                        if (moved) {
                            val maxX = (resources.displayMetrics.widthPixels - width).coerceAtLeast(0)
                            val maxY = (resources.displayMetrics.heightPixels - height).coerceAtLeast(0)
                            params.x = (startX + dx).toInt().coerceIn(0, maxX)
                            params.y = (startY + dy).toInt().coerceIn(0, maxY)
                            try { windows.updateViewLayout(root, params) } catch (_: Exception) { }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
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
        root.contentDescription = "Smart Space edge trigger. Tap to open; drag to reposition."
        root.setOnClickListener { openPanel() }
        try {
            windows.addView(root, params)
            handleView = root
            handleParams = params
            prefs.edit().putBoolean("overlay_running", true).putBoolean("overlay_window_ready", true).apply()
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun openPanel() {
        if (panelView != null) return
        handleView?.let { try { windows.removeView(it) } catch (_: Exception) { } }
        handleView = null
        prefs.edit().putBoolean("overlay_window_ready", false).apply()
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.density
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val desiredWidthDp = if (landscape) 500 else 420
        val width = min((desiredWidthDp * density).toInt(), (screenW * if (landscape) .68f else .92f).toInt())
        val height = (screenH * if (landscape) .92f else .88f).toInt()
        val panel = buildPanel()
        val p = WindowManager.LayoutParams(
            width.coerceAtLeast((280 * density).toInt()), height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL }
        panel.translationX = p.width.toFloat()
        try {
            windows.addView(panel, p)
            panelView = panel
            panelParams = p
            prefs.edit().putBoolean("overlay_running", true).putBoolean("overlay_window_ready", true).apply()
            panel.post {
                panel.animate().translationX(0f).setDuration(360L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
            refreshTelemetry()
            handler.removeCallbacks(refreshTask)
            handler.post(refreshTask)
        } catch (_: Exception) {
            panelView = null
            showHandle()
        }
    }

    private fun closePanel() {
        val panel = panelView ?: return
        val width = panelParams?.width?.toFloat() ?: panel.width.toFloat()
        panel.animate().translationX(width).setDuration(300L)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                try { windows.removeView(panel) } catch (_: Exception) { }
                panelView = null
                panelParams = null
                cpuGauge = null
                gpuGauge = null
                telemetry = null
                showHandle()
            }.start()
    }

    private fun buildPanel(): View {
        val density = resources.displayMetrics.density
        val opacity = prefs.getInt("opacity", 96).coerceIn(65, 100) / 100f
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.argb((255 * opacity).toInt(), Color.red(panelColor), Color.green(panelColor), Color.blue(panelColor)))
                cornerRadius = 26f * density
                setStroke((1 * density).toInt(), Color.rgb(61, 54, 38))
            }
            elevation = 18f * density
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            contentDescription = "Drag here to move the floating panel"
            setOnTouchListener(object : View.OnTouchListener {
                private var downX = 0f
                private var downY = 0f
                private var startX = 0
                private var startY = 0
                private var moved = false

                override fun onTouch(view: View, event: MotionEvent): Boolean {
                    val params = panelParams ?: return false
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.rawX
                            downY = event.rawY
                            startX = params.x
                            startY = params.y
                            moved = false
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - downX
                            val dy = event.rawY - downY
                            if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                            if (moved) {
                                val maxX = (resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0)
                                val maxY = resources.displayMetrics.heightPixels / 2
                                params.x = (startX - dx.toInt()).coerceIn(0, maxX)
                                params.y = (startY + dy.toInt()).coerceIn(-maxY, maxY)
                                try { panelView?.let { windows.updateViewLayout(it, params) } } catch (_: Exception) { }
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
                    }
                    return true
                }
            })
        }
        brand.addView(label("SMART SPACE", 16f, Color.WHITE, true))
        brand.addView(label("GAMING CONTROL LAYER", 9f, gold, true).apply { letterSpacing = .12f }, topMargin(4))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label("LIVE", 9f, Color.rgb(118, 211, 153), true).apply {
            setPadding((9 * density).toInt(), (6 * density).toInt(), (9 * density).toInt(), (6 * density).toInt())
            background = rounded(Color.rgb(26, 48, 39), 8f)
        })
        val close = label("×", 28f, Color.WHITE, false).apply {
            gravity = Gravity.CENTER
            setPadding((12 * density).toInt(), 0, 0, 0)
            setOnClickListener { closePanel() }
        }
        header.addView(close, LinearLayout.LayoutParams((42 * density).toInt(), (42 * density).toInt()))
        root.addView(header)
        root.addView(divider(), topMargin(12))

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(label("DEVICE PERFORMANCE", 10f, gold, true), margins(top = 14, bottom = 10))
        val gauges = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        cpuGauge = GaugeView(this, "CPU")
        gpuGauge = GaugeView(this, "GPU")
        gauges.addView(cpuGauge, LinearLayout.LayoutParams(0, (116 * density).toInt(), 1f))
        gauges.addView(gpuGauge, LinearLayout.LayoutParams(0, (116 * density).toInt(), 1f))
        content.addView(gauges)
        telemetry = label("Reading device counters…", 12f, Color.WHITE, false)
        telemetry?.setLineSpacing(4f * density, 1f)
        telemetry?.setPadding((13 * density).toInt(), (12 * density).toInt(), (13 * density).toInt(), (12 * density).toInt())
        telemetry?.background = rounded(Color.rgb(29, 32, 39), 14f)
        content.addView(telemetry, margins(top = 8))
        content.addView(label("QUICK CONTROLS", 10f, gold, true), margins(top = 18, bottom = 10))
        content.addView(controlGrid())
        content.addView(label("BRIGHTNESS", 10f, gold, true), margins(top = 16, bottom = 5))
        content.addView(brightnessControl())
        content.addView(label("VOLUME", 10f, gold, true), margins(top = 14, bottom = 5))
        content.addView(volumeControl())
        content.addView(label("YOUR APPS", 10f, gold, true), margins(top = 18, bottom = 8))
        content.addView(installedApps())
        content.addView(label("FPS for other apps is not exposed on this device/API. Smart Space will not substitute refresh rate or make up a value.", 11f, muted, false), margins(top = 16, bottom = 18))
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        footer.addView(label("DRAG TITLE TO MOVE · CORNER TO RESIZE", 9f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
        val grip = label("◢", 20f, gold, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(39, 35, 27), 9f)
            setOnTouchListener(object : View.OnTouchListener {
                private var downX = 0f
                private var downY = 0f
                private var startWidth = 0
                private var startHeight = 0
                override fun onTouch(view: View, event: MotionEvent): Boolean {
                    val params = panelParams ?: return false
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.rawX
                            downY = event.rawY
                            startWidth = params.width
                            startHeight = params.height
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val maxWidth = resources.displayMetrics.widthPixels
                            val maxHeight = resources.displayMetrics.heightPixels
                            params.width = (startWidth - (event.rawX - downX).toInt()).coerceIn(dp(280), maxWidth)
                            params.height = (startHeight - (event.rawY - downY).toInt()).coerceIn(dp(360), maxHeight)
                            try { panelView?.let { windows.updateViewLayout(it, params) } } catch (_: Exception) { }
                            return true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
                    }
                    return true
                }
            })
        }
        footer.addView(grip, LinearLayout.LayoutParams(dp(38), dp(34)))
        root.addView(footer, LinearLayout.LayoutParams(-1, dp(42)))
        return root
    }

    private fun controlGrid(): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = listOf(
            listOf("Wi-Fi", "Bluetooth", "Flashlight", "Auto-rotate"),
            listOf("DND", "Settings", "Battery", "Close")
        )
        rows.forEach { rowNames ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowNames.forEach { name ->
                row.addView(controlTile(name), LinearLayout.LayoutParams(0, dp(72), 1f).apply {
                    marginEnd = dp(5)
                    bottomMargin = dp(5)
                })
            }
            grid.addView(row)
        }
        return grid
    }

    private fun controlTile(name: String): View {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
            background = rounded(Color.rgb(30, 33, 40), 13f)
            isClickable = true
            isFocusable = true
        }
        val icon = when (name) {
            "Wi-Fi" -> "⌁"
            "Bluetooth" -> "ᛒ"
            "Flashlight" -> "✦"
            "Auto-rotate" -> "↻"
            "DND" -> "◐"
            "Settings" -> "⚙"
            "Battery" -> "▰"
            else -> "×"
        }
        item.addView(label(icon, 21f, gold, true).apply { gravity = Gravity.CENTER })
        item.addView(label(name.uppercase(), 9f, Color.WHITE, true).apply { gravity = Gravity.CENTER }, topMargin(5))
        item.setOnClickListener {
            when (name) {
                "Wi-Fi" -> openSystemPanel("wifi")
                "Bluetooth" -> launchSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
                "Flashlight" -> toggleTorch(item)
                "Auto-rotate" -> launchSettings(Settings.ACTION_DISPLAY_SETTINGS)
                "DND" -> launchSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                "Settings" -> launchSettings(Settings.ACTION_SETTINGS)
                "Battery" -> launchSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                "Close" -> closePanel()
            }
        }
        return item
    }

    private fun brightnessControl(): View {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val current = try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) } catch (_: Exception) { 128 }
        val slider = SeekBar(this).apply {
            max = 255
            progress = current
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    if (Settings.System.canWrite(this@SpaceOverlayService)) {
                        try { Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, value.coerceAtLeast(1)) }
                        catch (_: Exception) { }
                    } else {
                        Toast.makeText(this@SpaceOverlayService, "Allow Modify system settings first.", Toast.LENGTH_SHORT).show()
                        launchSettings(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:$packageName")
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        wrapper.addView(slider)
        return wrapper
    }

    private fun volumeControl(): View {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return SeekBar(this).apply {
            this.max = max
            progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
    }

    private fun installedApps(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val requested = listOf(
            "com.android.chrome" to "Chrome",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.whatsapp" to "WhatsApp",
            "com.google.android.youtube" to "YouTube"
        )
        var found = 0
        requested.forEach { (pkg, fallback) ->
            try {
                val info = packageManager.getApplicationInfo(pkg, 0)
                val launch = packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    found++
                    val tile = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(4), dp(8), dp(4), dp(8))
                        background = rounded(Color.rgb(30, 33, 40), 12f)
                        setOnClickListener {
                            try { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(launch) }
                            catch (_: Exception) { Toast.makeText(this@SpaceOverlayService, "App could not be opened.", Toast.LENGTH_SHORT).show() }
                        }
                    }
                    val icon = ImageView(this).apply { setImageDrawable(packageManager.getApplicationIcon(info)); scaleType = ImageView.ScaleType.FIT_CENTER }
                    tile.addView(icon, LinearLayout.LayoutParams(dp(30), dp(30)))
                    tile.addView(label(fallback, 9f, Color.WHITE, true).apply { gravity = Gravity.CENTER }, topMargin(5))
                    row.addView(tile, LinearLayout.LayoutParams(0, dp(68), 1f).apply { marginEnd = dp(5) })
                }
            } catch (_: Exception) { }
        }
        if (found == 0) row.addView(label("None of the selected apps are installed.", 12f, muted, false))
        return row
    }

    private fun refreshTelemetry() {
        if (panelView == null) return
        val sample = monitor.sample()
        lastCpu = sample.cpuPercent?.let { "${it.roundToInt()}%" } ?: "N/A"
        cpuGauge?.setValue(sample.cpuPercent)
        gpuGauge?.setValue(sample.gpuPercent)
        val cpuClock = sample.cpuMHz?.let { "${it} MHz" } ?: "N/A"
        val gpuClock = sample.gpuMHz?.let { "${it} MHz" } ?: "N/A"
        val ram = if (sample.totalRamMb > 0) "${String.format("%.1f", sample.usedRamMb / 1024f)} / ${String.format("%.1f", sample.totalRamMb / 1024f)} GB" else "N/A"
        val battery = sample.batteryPercent?.let { "${it}%" } ?: "N/A"
        val batteryTemp = sample.batteryTemperatureC?.let { "${it} °C" } ?: "N/A"
        telemetry?.text = "CPU LOAD       $lastCpu      •      CPU CLOCK       $cpuClock\nGPU LOAD       ${sample.gpuPercent?.let { "${it.roundToInt()}%" } ?: "N/A"}      •      GPU CLOCK       $gpuClock\nRAM USED       $ram\nBATTERY       $battery      •      BATTERY TEMP       $batteryTemp\nTHERMAL STATUS       ${sample.thermalStatus}\nGAME FPS       N/A  ·  Not exposed by standard cross-app APIs"
    }

    private fun toggleTorch(view: View) {
        try {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Grant Camera permission in Smart Space first.", Toast.LENGTH_LONG).show()
                return
            }
            val camera = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = camera.cameraIdList.firstOrNull { id ->
                camera.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId == null) {
                Toast.makeText(this, "This device has no available camera flash.", Toast.LENGTH_SHORT).show()
                return
            }
            torchOn = !torchOn
            camera.setTorchMode(cameraId, torchOn)
            Toast.makeText(this, if (torchOn) "Flashlight ON" else "Flashlight OFF", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            torchOn = false
            Toast.makeText(this, "Flashlight unavailable: ${e.message ?: "camera busy"}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSystemPanel(kind: String) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(if (kind == "wifi") Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.Panel.ACTION_WIFI)
        } else Intent(Settings.ACTION_WIFI_SETTINGS)
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

    private fun label(value: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * resources.displayMetrics.density
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.rgb(51, 53, 60))
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun topMargin(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(value) }
    private fun margins(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val wasOpen = panelView != null
        removeWindows()
        showHandle()
        if (wasOpen) {
            handler.postDelayed({ openPanel() }, 80L)
        }
    }

    private fun removeWindows() {
        handleView?.let { try { windows.removeView(it) } catch (_: Exception) { } }
        panelView?.let { try { windows.removeView(it) } catch (_: Exception) { } }
        handleView = null
        panelView = null
        panelParams = null
        handleParams = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTask)
        removeWindows()
        prefs.edit().putBoolean("overlay_running", false).putBoolean("overlay_window_ready", false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private inner class GaugeView(context: Context, private val title: String) : View(context) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(8).toFloat(); strokeCap = Paint.Cap.ROUND; color = Color.rgb(49, 52, 60) }
        private val active = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(8).toFloat(); strokeCap = Paint.Cap.ROUND; color = gold }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = dp(19).toFloat() }
        private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textAlign = Paint.Align.CENTER; textSize = dp(10).toFloat() }
        private var value: Float? = null
        private var animated = 0f

        fun setValue(next: Float?) {
            value = next
            if (next == null) {
                animated = 0f
                invalidate()
                return
            }
            val start = animated
            android.animation.ValueAnimator.ofFloat(start, next.coerceIn(0f, 100f)).apply {
                duration = 650L
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
            val cy = height / 2f - dp(4)
            val radius = min(width, height) / 2f - dp(17)
            val bounds = android.graphics.RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(bounds, 135f, 270f, false, ring)
            if (value != null) {
                active.color = if (animated >= 85f) Color.rgb(235, 104, 82) else gold
                canvas.drawArc(bounds, 135f, 270f * animated / 100f, false, active)
            }
            textPaint.textSize = dp(19).toFloat()
            canvas.drawText(value?.let { "${animated.roundToInt()}%" } ?: "N/A", cx, cy + dp(6), textPaint)
            smallPaint.textSize = dp(10).toFloat()
            canvas.drawText(title, cx, height - dp(7).toFloat(), smallPaint)
        }
    }
}
