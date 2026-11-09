package com.alkisstam.sidebar

import android.animation.*
import android.app.*
import android.content.*
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class SidebarOverlayService : Service() {

    private lateinit var wm: WindowManager
    private var handleView: View? = null
    private var handleViewAlt: View? = null
    private var pillInnerView: View? = null
    private var pillInnerViewAlt: View? = null
    private var lastActiveSide = "right"
    private var panelView: View? = null
    private var dismissOverlay: View? = null
    private var allAppsView: View? = null
    private var allAppsDismissOverlay: View? = null
    private var contextMenuView: View? = null
    private var showingDrawer = false
    private var shown = false

    private var vibrationEnabled = true

    private val iconTypeface: android.graphics.Typeface by lazy {
        android.graphics.Typeface.createFromAsset(assets, "fonts/MaterialIcons-Regular.ttf")
    }

    private val appLoadExecutor = Executors.newSingleThreadExecutor()
    private val appIconCache = ConcurrentHashMap<String, Pair<String, Drawable>>()
    @Volatile private var cachedAllApps: List<Triple<String, String, Drawable>>? = null

    // Fullscreen detection — only active when auto-hide-fullscreen pref is on.
    private val fsHandler = Handler(Looper.getMainLooper())
    private val fsRunnable = object : Runnable {
        override fun run() {
            if (!shown) {
                val vis = if (isSystemFullscreen()) View.INVISIBLE else View.VISIBLE
                handleView?.visibility = vis
                handleViewAlt?.visibility = vis
            }
            fsHandler.postDelayed(this, 2000)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                fsHandler.removeCallbacks(fsRunnable)
                return
            }
            if (!Settings.canDrawOverlays(context)) return
            Handler(Looper.getMainLooper()).post {
                if (handleView?.windowToken == null) {
                    addHandle()
                } else {
                    val autoHide = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
                        .getBoolean(Prefs.AUTO_HIDE_FULLSCREEN, false)
                    if (autoHide) {
                        fsHandler.removeCallbacks(fsRunnable)
                        fsHandler.post(fsRunnable)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)

        if (Settings.canDrawOverlays(this)) {
            Handler(Looper.getMainLooper()).post { addHandle() }
        }
        appLoadExecutor.submit { prefetchAppData() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Settings.canDrawOverlays(this)) {
            Handler(Looper.getMainLooper()).post { refreshHandle() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        fsHandler.removeCallbacks(fsRunnable)
        appLoadExecutor.shutdown()
        runCatching { unregisterReceiver(screenReceiver) }
        Handler(Looper.getMainLooper()).post {
            handleView?.let { runCatching { wm.removeView(it) } }
            handleViewAlt?.let { runCatching { wm.removeView(it) } }
            panelView?.let { runCatching { wm.removeView(it) } }
            dismissOverlay?.let { runCatching { wm.removeView(it) } }
            allAppsView?.let { runCatching { wm.removeView(it) } }
            allAppsDismissOverlay?.let { runCatching { wm.removeView(it) } }
            contextMenuView?.let { runCatching { wm.removeView(it) } }
        }
    }

    private fun refreshHandle() {
        fsHandler.removeCallbacks(fsRunnable)
        handleView?.let { runCatching { wm.removeView(it) } }
        handleViewAlt?.let { runCatching { wm.removeView(it) } }
        handleView = null; handleViewAlt = null
        pillInnerView = null; pillInnerViewAlt = null
        addHandle()
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // ── Prefs ─────────────────────────────────────────────────────────────────

    data class PillPrefs(
        val height: Int, val width: Int, val position: Float,
        val side: String, val opacity: Float, val theme: String,
        val panelColor: String
    )

    data class OverlayPrefs(
        val autoHideFullscreen: Boolean,
        val showLabels: Boolean,
        val vibration: Boolean,
        val sensitivity: Int,
        val showAllApps: Boolean
    )

    private fun pillPrefs(): PillPrefs {
        val p = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        val themeChoice = p.getString(Prefs.THEME_CHOICE, "system") ?: "system"
        val theme = if (themeChoice == "system") {
            val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (nightMode == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        } else {
            p.getString(Prefs.PILL_THEME, "dark") ?: "dark"
        }
        return PillPrefs(
            p.getInt(Prefs.PILL_HEIGHT, 80), p.getInt(Prefs.PILL_WIDTH, 10),
            p.getFloat(Prefs.PILL_POSITION, 0.5f),
            p.getString(Prefs.PILL_SIDE, "right") ?: "right",
            p.getFloat(Prefs.PILL_OPACITY, 1.0f),
            theme,
            p.getString(Prefs.PANEL_COLOR, "") ?: ""
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Handler(Looper.getMainLooper()).post {
            if (shown) hidePanel()
            refreshHandle()
        }
    }

    private fun resolvePanelBg(rawColor: String, alpha: Int, light: Boolean): Int {
        if (rawColor.isNotEmpty()) {
            try {
                val c = Color.parseColor(rawColor)
                return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
            } catch (_: IllegalArgumentException) {}
        }
        return if (light) Color.argb(alpha, 255, 251, 254) else Color.argb(alpha, 28, 27, 31)
    }

    private fun overlayPrefs(): OverlayPrefs {
        val p = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        return OverlayPrefs(
                  false, // Auto-hide desactivado permanentemente
            p.getBoolean(Prefs.SHOW_LABELS, true),
            p.getBoolean(Prefs.VIBRATION, true),
            p.getInt(Prefs.SWIPE_SENSITIVITY, 16),
            p.getBoolean(Prefs.SHOW_ALL_APPS, true)
        )
    }

    // ── Handle ────────────────────────────────────────────────────────────────

    private fun handleParams(prefs: PillPrefs, side: String): WindowManager.LayoutParams {
        val pillHeight = dp(prefs.height)
        val screenHeight = resources.displayMetrics.heightPixels
        val yPos = (prefs.position * screenHeight - pillHeight / 2).toInt()
            .coerceIn(0, screenHeight - pillHeight)
                return WindowManager.LayoutParams(
            maxOf(dp(prefs.width), dp(24)), pillHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (side == "left") Gravity.START or Gravity.TOP
                      else Gravity.END or Gravity.TOP
            x = dp(2)
            y = yPos
        }
    }

    private fun buildHandle(prefs: PillPrefs, oPrefs: OverlayPrefs, side: String): Pair<View, View> {
        val pill = View(this).apply {
            background = PullTabDrawable(highlighted = false, prefs.theme, prefs.panelColor)
            alpha = prefs.opacity
        }
        val container = FrameLayout(this).apply {
            addView(pill, FrameLayout.LayoutParams(dp(prefs.width), FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = if (side == "left") Gravity.START else Gravity.END
            })
        }
        installSwipeListener(container, oPrefs.sensitivity, side)
        wm.addView(container, handleParams(prefs, side))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            container.post {
                container.systemGestureExclusionRects = listOf(
                    android.graphics.Rect(0, 0, container.width, container.height)
                )
            }
        }
        return Pair(container, pill)
    }

    private fun addHandle() {
        val prefs = pillPrefs()
        val oPrefs = overlayPrefs()
        vibrationEnabled = oPrefs.vibration
        val primarySide = if (prefs.side == "both") "left" else prefs.side
        val (c, p) = buildHandle(prefs, oPrefs, primarySide)
        handleView = c; pillInnerView = p
        if (prefs.side == "both") {
            val (cR, pR) = buildHandle(prefs, oPrefs, "right")
            handleViewAlt = cR; pillInnerViewAlt = pR
            lastActiveSide = "right"
        }
        if (oPrefs.autoHideFullscreen) {
            fsHandler.removeCallbacks(fsRunnable)
            fsHandler.post(fsRunnable)
        }
    }

        private fun installSwipeListener(handle: View, sensitivityDp: Int = 16, triggerSide: String = "right") {
        var downX = 0f
        var downY = 0f
        var isSwipe = false
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    isSwipe = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(event.rawX - downX) > dp(10) || Math.abs(event.rawY - downY) > dp(10)) {
                        isSwipe = true
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isSwipe) {
                        lastActiveSide = triggerSide
                        toggle()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun vibrate() {
        if (!vibrationEnabled) return
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vib.vibrate(30)
        }
    }

    private fun toggle() {
        vibrate()
        if (shown) hidePanel() else showPanel()
    }

    // ── Panel ─────────────────────────────────────────────────────────────────

    private fun showPanel() {
        if (shown) return
        shown = true
        val prefs = pillPrefs()
        val oPrefs = overlayPrefs()
        val pkgs = loadFavorites()
        val pm = packageManager
        val light = prefs.theme == "light"
        val effectiveSide = if (prefs.side == "both") lastActiveSide else prefs.side

        val screenHeight = resources.displayMetrics.heightPixels
        val panelHeight = screenHeight // Ocupa toda la pantalla
        val panelY = 0 // Empieza desde arriba
        
        val overlay = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { hidePanel() }
        }
        wm.addView(overlay, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ))
        dismissOverlay = overlay

        val panelBg = resolvePanelBg(prefs.panelColor, 248, light)
        val effectiveLight = if (prefs.panelColor.isNotEmpty()) {
            val hsv = FloatArray(3); Color.colorToHSV(panelBg, hsv); hsv[2] > 0.5f
        } else light
        val labelColor = if (effectiveLight) Color.argb(230, 28, 27, 31) else Color.argb(230, 230, 225, 229)
        val emptyColor = if (effectiveLight) Color.argb(160, 73, 69, 79) else Color.argb(160, 202, 196, 208)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(panelBg)
                cornerRadius = dp(32).toFloat()
                setStroke(dp(2), Color.parseColor("#39FF14"))
            }
            elevation = dp(8).toFloat()
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
               val container = GridLayout(this).apply {
            columnCount = 3 // 3 Columnas
            alignmentMode = GridLayout.ALIGN_BOUNDS
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }

        fun loadCell(pkg: String): View? {
            val cached = appIconCache[pkg]
            val cell = if (cached != null) {
                makeAppCell(pkg, cached.first, cached.second, labelColor, false)
            } else {
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    makeAppCell(pkg, pm.getApplicationLabel(info).toString(),
                        pm.getApplicationIcon(pkg), labelColor, false)
                } catch (e: Exception) { null }
            }
            return cell?.also { v ->
                (v as? ViewGroup)?.getChildAt(0)?.let { iv ->
                    iv.clipToOutline = true
                    iv.outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                            outline.setOval(0, 0, view.width, view.height)
                        }
                    }
                }
            }
        }

               if (pkgs.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No\nfavs"
                setTextColor(emptyColor)
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(16), dp(4), dp(16))
                layoutParams = GridLayout.LayoutParams().apply {
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 2f) // Ocupa las 2 columnas
                }
            })
                } else {
            for (pkg in pkgs) {
                loadCell(pkg)?.let { cell ->
                    val params = GridLayout.LayoutParams()
                    params.width = 0
                    params.height = GridLayout.LayoutParams.WRAP_CONTENT
                    params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    params.setMargins(dp(2), dp(2), dp(2), dp(2))
                    cell.layoutParams = params
                    container.addView(cell)
                }
            }
        }

        scroll.addView(container)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val panelGravity = if (effectiveSide == "left") Gravity.START or Gravity.TOP
                           else Gravity.END or Gravity.TOP
        wm.addView(root, WindowManager.LayoutParams(
            dp(200), panelHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = panelGravity; x = dp(8); y = panelY })

        panelView = root
        handleView?.visibility = View.INVISIBLE
        handleViewAlt?.visibility = View.INVISIBLE

        val slideFrom = if (effectiveSide == "left") -dp(200).toFloat() else dp(200).toFloat()
        root.translationX = slideFrom
        root.alpha = 0f
        root.scaleX = 0.92f
        root.scaleY = 0.96f
        root.animate().withLayer()
            .translationX(0f).alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(220).setInterpolator(DecelerateInterpolator(2.5f))
            .start()
    }

    private fun hidePanel() {
        if (!shown) return
        shown = false
        val panel = panelView ?: run {
            dismissOverlay?.let { runCatching { wm.removeViewImmediate(it) } }
            dismissOverlay = null
            handleView?.visibility = View.VISIBLE
            handleViewAlt?.visibility = View.VISIBLE
            return
        }
        panelView = null
        val prefs = pillPrefs()
        val effectiveSide = if (prefs.side == "both") lastActiveSide else prefs.side
        val overlay = dismissOverlay
        dismissOverlay = null
        val handle = handleView
        val handleAlt = handleViewAlt

        val slideTo = if (effectiveSide == "left") -dp(200).toFloat() else dp(200).toFloat()
        panel.animate().withLayer()
            .translationX(slideTo).alpha(0f)
            .setDuration(160).setInterpolator(AccelerateInterpolator(2.2f))
            .withEndAction {
                panel.alpha = 0f
                runCatching { wm.removeViewImmediate(panel) }
                overlay?.let { runCatching { wm.removeViewImmediate(it) } }
                if (!showingDrawer) {
                    handle?.visibility = View.VISIBLE
                    handleAlt?.visibility = View.VISIBLE
                }
            }
            .start()
    }

    // ── All Apps drawer ───────────────────────────────────────────────────────

    private fun showAllAppsDrawer() {
        showingDrawer = true
        hidePanel()
        vibrate()
        val p = pillPrefs()
        val light = p.theme == "light"
        val dm = resources.displayMetrics
        val drawerW = (dm.widthPixels * 0.8).toInt()
        val drawerH = (dm.heightPixels * 0.72).toInt()

        val panelBg = resolvePanelBg(p.panelColor, 252, light)
        val effectiveLight = if (p.panelColor.isNotEmpty()) {
            val hsv = FloatArray(3); Color.colorToHSV(panelBg, hsv); hsv[2] > 0.5f
        } else light
        val tileBg = if (effectiveLight) Color.argb(130, 115, 105, 130) else Color.argb(130, 55, 52, 62)
        val labelColor = if (effectiveLight) Color.argb(140, 28, 27, 31) else Color.argb(140, 230, 225, 229)
        val indClr = if (effectiveLight) Color.argb(60, 0, 0, 0) else Color.argb(80, 255, 255, 255)

        val dismissView = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { hideAllAppsDrawer() }
        }
        wm.addView(dismissView, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ))
        allAppsDismissOverlay = dismissView

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(panelBg)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(2), Color.parseColor("#39FF14"))
            }
            elevation = dp(8).toFloat()
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        header.addView(TextView(this).apply {
            text = "All Apps"
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "✕"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(labelColor)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply { setColor(tileBg); cornerRadius = dp(20).toFloat() }
            isClickable = true; isFocusable = true
            setOnClickListener { hideAllAppsDrawer() }
        })
        root.addView(header)

        root.addView(View(this).apply {
            setBackgroundColor(indClr)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        val hintColor = Color.argb(120, Color.red(labelColor), Color.green(labelColor), Color.blue(labelColor))
        val searchBox = android.widget.EditText(this).apply {
            hint = "Search apps"
            textSize = 14f
            setTextColor(labelColor)
            setHintTextColor(hintColor)
            background = GradientDrawable().apply { setColor(tileBg); cornerRadius = dp(20).toFloat() }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(12), dp(8), dp(12), dp(8)) }
        }
        root.addView(searchBox)

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(16))
        }
        grid.addView(TextView(this).apply {
            text = "Loading…"
            setTextColor(labelColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(40), 0, dp(40))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        wm.addView(root, WindowManager.LayoutParams(
            drawerW, drawerH,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        })
        allAppsView = root

        root.alpha = 0f; root.scaleX = 0.92f; root.scaleY = 0.92f
        root.animate().withLayer().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(240).setInterpolator(OvershootInterpolator(1.0f)).start()

        val pm = packageManager
        val appList = mutableListOf<Triple<String, String, Drawable>>()
        var currentFiltered = listOf<Triple<String, String, Drawable>>()

        fun rebuildGrid(query: String) {
            val filtered = if (query.isEmpty()) appList
                           else appList.filter { it.second.lowercase().contains(query.lowercase()) }
            currentFiltered = filtered
            grid.removeAllViews()
            if (filtered.isEmpty()) {
                grid.addView(TextView(this).apply {
                    text = if (query.isEmpty()) "" else "No results"
                    setTextColor(labelColor)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(40), 0, dp(40))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                return
            }
            var row: LinearLayout? = null
            for ((i, app) in filtered.withIndex()) {
                if (i % 4 == 0) {
                    row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                    grid.addView(row)
                }
                val (pkg, name, icon) = app
                row?.addView(makeAppCell(pkg, name, icon, labelColor, true,
                    isHighlighted = query.isNotEmpty() && i == 0) {
                    hideAllAppsDrawer()
                    doLaunch(pkg)
                })
            }
            val rem = filtered.size % 4
            if (rem != 0) repeat(4 - rem) {
                row?.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
        }

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { rebuildGrid(s?.toString() ?: "") }
        })

        searchBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                currentFiltered.firstOrNull()?.let { (pkg, _, _) ->
                    hideAllAppsDrawer()
                    doLaunch(pkg)
                }
                true
            } else false
        }

        val snapshot = cachedAllApps
        if (snapshot != null) {
            appList.addAll(snapshot)
            rebuildGrid(searchBox.text.toString())
        } else {
            appLoadExecutor.submit {
                try {
                    val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                    @Suppress("DEPRECATION")
                    val rawApps: List<ResolveInfo> = pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
                    val entries = rawApps.sortedBy { it.loadLabel(pm).toString().lowercase() }.mapNotNull { app ->
                        val pkg = app.activityInfo.packageName
                        val name = app.loadLabel(pm).toString()
                        try { Triple(pkg, name, pm.getApplicationIcon(pkg)) }
                        catch (e: Exception) { Log.w(TAG, "No icon for $pkg", e); null }
                    }
                    Handler(Looper.getMainLooper()).post {
                        if (allAppsView == null) return@post
                        appList.addAll(entries)
                        rebuildGrid(searchBox.text.toString())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load app list", e)
                }
            }
        }
    }

    private fun hideAllAppsDrawer() {
        val drawer = allAppsView ?: return
        allAppsView = null
        showingDrawer = false
        val dismiss = allAppsDismissOverlay
        allAppsDismissOverlay = null
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(drawer.windowToken, 0)
        handleView?.visibility = View.VISIBLE
        handleViewAlt?.visibility = View.VISIBLE
        drawer.animate().withLayer().alpha(0f).scaleX(0.92f).scaleY(0.92f)
            .setDuration(160).setInterpolator(AccelerateInterpolator(1.5f))
            .withEndAction {
                runCatching { wm.removeViewImmediate(drawer) }
                dismiss?.let { runCatching { wm.removeViewImmediate(it) } }
            }
            .start()
    }

    private fun makeAppCell(
        pkg: String, name: String, icon: Drawable,
        labelColor: Int = Color.WHITE, showLabel: Boolean = true,
        isHighlighted: Boolean = false,
        onClick: () -> Unit = { launch(pkg) }
    ): View {
           val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(6), dp(2), dp(6))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (isHighlighted) {
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(2), Color.parseColor("#39FF14"))
                }
            }
            setOnClickListener { onClick() }
            setOnLongClickListener { showAppContextMenu(pkg, name, it); true }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        animate().withLayer().scaleX(0.85f).scaleY(0.85f).setDuration(80).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        animate().withLayer().scaleX(1f).scaleY(1f).setDuration(160)
                            .setInterpolator(OvershootInterpolator(2f)).start()
                }
                false
            }
        }
        cell.addView(ImageView(this).apply {
            setImageDrawable(icon)
            val size = dp(48)
            layoutParams = LinearLayout.LayoutParams(size, size)
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
        if (showLabel) {
            cell.addView(TextView(this).apply {
                text = name
                setTextColor(labelColor)
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(5) }
            })
        }
        return cell
    }

    private fun launch(pkg: String) {
        hidePanel()
        doLaunch(pkg)
    }

    private fun doLaunch(pkg: String) {
        vibrate()
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Log.w(TAG, "No launch intent for $pkg")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Can't open this app", Toast.LENGTH_SHORT).show()
            }
            return
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $pkg", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Failed to launch app", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAppContextMenu(pkg: String, name: String, anchor: View) {
        hideContextMenu()
        val prefs = pillPrefs()
        val light = prefs.theme == "light"
        val panelBg = resolvePanelBg(prefs.panelColor, 252, light)
        val effectiveLight = if (prefs.panelColor.isNotEmpty()) {
            val hsv = FloatArray(3); Color.colorToHSV(panelBg, hsv); hsv[2] > 0.5f
        } else light
        val tileBg = if (effectiveLight) Color.argb(130, 115, 105, 130) else Color.argb(130, 55, 52, 62)
        val labelColor = if (effectiveLight) Color.argb(140, 28, 27, 31) else Color.argb(140, 230, 225, 229)

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val dm = resources.displayMetrics
        val menuW = dp(180)
        var mx = loc[0] + anchor.width / 2 - menuW / 2
        var my = loc[1] + anchor.height / 2
        mx = mx.coerceIn(dp(8), dm.widthPixels - menuW - dp(8))
        my = my.coerceIn(dp(8), dm.heightPixels - dp(140))

        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(panelBg); cornerRadius = dp(16).toFloat() }
            elevation = dp(10).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        menu.addView(TextView(this).apply {
            text = name
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.argb(160, Color.red(labelColor), Color.green(labelColor), Color.blue(labelColor)))
            setPadding(dp(14), dp(10), dp(14), dp(6))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        menu.addView(View(this).apply {
            setBackgroundColor(Color.argb(30, 0, 0, 0))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(dp(8), dp(2), dp(8), dp(2))
            }
        })
        fun item(label: String, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(labelColor)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true; isFocusable = true
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(12).toFloat() }
            setOnClickListener { hideContextMenu(); action() }
        }
        menu.addView(item("Open") { doLaunch(pkg) })

        val container = FrameLayout(this).apply {
            setOnClickListener { hideContextMenu() }
            addView(menu, FrameLayout.LayoutParams(menuW, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = mx; topMargin = my
            })
        }
        wm.addView(container, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ))
        contextMenuView = container
        menu.alpha = 0f; menu.scaleX = 0.88f; menu.scaleY = 0.88f
        menu.animate().withLayer().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(180).setInterpolator(OvershootInterpolator(1.5f)).start()
    }

    private fun hideContextMenu() {
        contextMenuView?.let { runCatching { wm.removeView(it) } }
        contextMenuView = null
    }

    private fun prefetchAppData() {
        try {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            @Suppress("DEPRECATION")
            val entries = pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
                .mapNotNull { app ->
                    val pkg = app.activityInfo.packageName
                    val name = app.loadLabel(pm).toString()
                    try {
                        val icon = pm.getApplicationIcon(pkg)
                        appIconCache[pkg] = Pair(name, icon)
                        Triple(pkg, name, icon)
                    } catch (e: Exception) { null }
                }
            cachedAllApps = entries
        } catch (e: Exception) {
            Log.e(TAG, "Prefetch failed", e)
        }
    }

    private fun loadFavorites(): List<String> {
        val json = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
            .getString(Prefs.FAVORITES, "[]") ?: "[]"
        return buildList {
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) add(arr.getString(i))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse favorites JSON", e)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

      private fun isSystemFullscreen(): Boolean = false

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("sidebar_svc", "Floating Panel", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, "sidebar_svc")
            .setContentTitle("Floating Panel")
            .setContentText("Pull-tab active on screen edge")
            .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private inner class PullTabDrawable(
        private val highlighted: Boolean,
        private val theme: String = "dark",
        private val handleColor: String = ""
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (handleColor.isNotEmpty()) {
                runCatching {
                    val base = Color.parseColor(handleColor)
                    val alpha = if (highlighted) 230 else 210
                    Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
                }.getOrElse { if (highlighted) Color.argb(230, 100, 100, 100) else Color.argb(210, 100, 100, 100) }
            } else when {
                highlighted && theme == "light" -> Color.argb(230, 160, 160, 160)
                highlighted                     -> Color.argb(230, 80, 80, 80)
                theme == "light"                -> Color.argb(210, 255, 251, 254)
                else                            -> Color.argb(210, 28, 27, 31)
            }
        }
        private val rect = RectF()
        override fun draw(canvas: Canvas) {
            rect.set(bounds)
            canvas.drawRoundRect(rect, rect.width() / 2f, rect.width() / 2f, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf; invalidateSelf() }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        private const val TAG = "SidebarOverlayService"
    }
}
