package com.br.resonance

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.Toast
import android.widget.FrameLayout
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import java.net.Inet4Address
import java.net.NetworkInterface

enum class AppMode {
    SENDER,
    RECEIVER
}

/**
 * ResonanceActivity: Unified Dual Audio System with 2 Switchable Modes (Sender vs Receiver).
 * Styled with Cream Neobrutalist Mobile design system:
 * - Warm cream canvas (#F5F0E8)
 * - 2px solid ink-black borders (#1A1A1A) on all components
 * - Hard offset drop shadows (4px / 2px)
 * - Segmented Mode Pill Switcher ([ SENDER ] / [ RECEIVER ])
 * - Dynamic mutual exclusion of background services & network ports
 * - Sender Mode: 5 circular receiver slots, auto-discovery & multi-client streaming
 * - Receiver Mode: 1ms continuous sync offset slider with 7 micro-nudges, screen-off locks
 * - Safe status bar and display cutout insets to prevent notification icon overlap
 */
class ResonanceActivity : Activity() {

    private val permissionRequestCodeSender = 3001
    private val permissionRequestCodeReceiver = 3002
    private val captureRequestCode = 3003

    private val PREFS_NAME = "dual_audio_prefs"
    private val KEY_CURRENT_MODE = "current_mode"
    private val KEY_DELAY_MS = "receiver_delay_ms"

    private var currentMode = AppMode.SENDER

    // --- Side Bar UI ---
    private lateinit var rootFrame: FrameLayout
    private lateinit var scrimView: View
    private lateinit var sideBarContainer: LinearLayout
    private var isSideBarOpen = false
    private var backCallback: Any? = null

    private fun openSideBar() {
        if (isSideBarOpen) return
        isSideBarOpen = true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val callback = android.window.OnBackInvokedCallback {
                closeSideBar()
            }
            backCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
            )
        }
        scrimView.visibility = View.VISIBLE
        scrimView.animate().alpha(1f).setDuration(220).start()
        sideBarContainer.visibility = View.VISIBLE
        sideBarContainer.animate().translationX(0f).setDuration(250).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun closeSideBar() {
        if (!isSideBarOpen) return
        isSideBarOpen = false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            (backCallback as? android.window.OnBackInvokedCallback)?.let {
                try {
                    onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
                } catch (_: Exception) {}
                backCallback = null
            }
        }
        scrimView.animate().alpha(0f).setDuration(200).withEndAction {
            scrimView.visibility = View.GONE
        }.start()
        val width = sideBarContainer.width.toFloat().let { if (it > 0f) it else NeobrutalistTheme.dpToPx(this, 320f).toFloat() }
        sideBarContainer.animate().translationX(-width).setDuration(220).setInterpolator(AccelerateInterpolator()).withEndAction {
            sideBarContainer.visibility = View.GONE
        }.start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && isSideBarOpen) {
            closeSideBar()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (isSideBarOpen) {
            closeSideBar()
            return
        }
        super.onBackPressed()
    }

    // --- Services and Managers ---
    private var captureService: CaptureService? = null
    private var isCaptureBound = false

    private var playbackService: PlaybackService? = null
    private var isPlaybackBound = false

    private lateinit var discoveryManager: DeviceDiscoveryManager

    // --- Mode Switcher UI ---
    private lateinit var tabSender: TextView
    private lateinit var tabReceiver: TextView
    private lateinit var senderContainer: LinearLayout
    private lateinit var receiverContainer: LinearLayout

    // --- Sender UI Elements ---
    private lateinit var senderStatusBadge: TextView
    private lateinit var senderStatusDetailText: TextView
    private lateinit var senderAudioLevelBar: ProgressBar
    private lateinit var senderAudioLevelText: TextView
    private lateinit var senderSilenceIndicator: TextView

    private lateinit var slotsCountBadge: TextView
    private val circularSlotViews = mutableListOf<CircularSlotViewHolder>()

    private lateinit var senderDevicesContainer: LinearLayout
    private lateinit var manualIpInput: EditText
    private lateinit var btnAddManualIp: Button

    private lateinit var statFramesText: TextView
    private lateinit var statSamplesText: TextView
    private lateinit var statDataText: TextView
    private lateinit var statDurationText: TextView
    private lateinit var statPacketsSentText: TextView
    private lateinit var statDataSentText: TextView
    private lateinit var statNetworkErrorsText: TextView

    private lateinit var btnStartSender: Button
    private lateinit var btnStopSender: Button

    // --- Receiver UI Elements ---
    private lateinit var receiverStatusBadge: TextView
    private lateinit var receiverStatusDetailText: TextView
    private lateinit var receiverAudioLevelBar: ProgressBar
    private lateinit var receiverAudioLevelText: TextView
    private lateinit var receiverPacketsText: TextView
    private lateinit var receiverBytesText: TextView
    private lateinit var receiverBufferDepthText: TextView
    private lateinit var receiverDroppedPacketsText: TextView
    private lateinit var receiverAudioSinkText: TextView

    private lateinit var delayLabel: TextView
    private lateinit var delaySubLabel: TextView
    private lateinit var delaySeekBar: SeekBar
    private lateinit var btnResetDelay: Button

    private lateinit var btnStartReceiver: Button
    private lateinit var btnStopReceiver: Button

    private var receiverDelayMs = 0

    class CircularSlotViewHolder(
        val container: LinearLayout,
        val circleView: TextView,
        val labelView: TextView,
        val statusDot: View
    )

    // --- Service Connections ---
    private val captureConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? CaptureService.LocalBinder
            captureService = binder?.getService()
            isCaptureBound = true

            // Sync currently selected devices with service
            captureService?.setTargetHosts(discoveryManager.getSelectedIps())
            captureService?.setDiagnosticListener { snapshot ->
                runOnUiThread { updateSenderUi(snapshot) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            captureService = null
            isCaptureBound = false
        }
    }

    private val playbackConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? PlaybackService.LocalBinder
            playbackService = binder?.getService()
            isPlaybackBound = true

            playbackService?.setOffsetMs(receiverDelayMs)
            playbackService?.setDiagnosticListener { snapshot ->
                runOnUiThread { updateReceiverUi(snapshot) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isPlaybackBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load saved mode preference or intent extra
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedModeStr = prefs.getString(KEY_CURRENT_MODE, AppMode.SENDER.name)
        val initialModeStr = intent?.getStringExtra("EXTRA_MODE") ?: savedModeStr
        currentMode = try {
            AppMode.valueOf(initialModeStr ?: AppMode.SENDER.name)
        } catch (_: Exception) {
            AppMode.SENDER
        }
        receiverDelayMs = prefs.getInt(KEY_DELAY_MS, 0)

        // Initialize discovery manager for sender
        discoveryManager = DeviceDiscoveryManager(this)

        buildNeobrutalistLayout()
        applyCurrentModeUi(initialSetup = true)
        handleIntent(intent)

        // Asynchronously check for GitHub updates on startup (non-blocking)
        checkAppUpdatesOnStartup()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val modeExtra = intent.getStringExtra("EXTRA_MODE")
        if (modeExtra != null) {
            try {
                val targetMode = AppMode.valueOf(modeExtra)
                if (currentMode != targetMode) {
                    switchMode(targetMode)
                }
            } catch (_: Exception) {}
        }
        if (intent.getBooleanExtra("EXTRA_START_SENDER", false)) {
            if (currentMode != AppMode.SENDER) {
                switchMode(AppMode.SENDER)
            }
            checkSenderPermissionsAndStart()
        }
        if (intent.action == "com.br.resonance.SHARE_APK" || intent.getStringExtra("EXTRA_ACTION") == "SHARE_APK") {
            shareInstalledApk()
        }
    }

    private fun shareInstalledApk() {
        kotlin.concurrent.thread(name = "ShareApkThread") {
            try {
                val appInfo = applicationInfo
                val sourceFile = java.io.File(appInfo.sourceDir)
                if (!sourceFile.exists()) {
                    runOnUiThread {
                        Toast.makeText(this@ResonanceActivity, "APK source not found", Toast.LENGTH_SHORT).show()
                    }
                    return@thread
                }

                val shareDir = java.io.File(cacheDir, "shared_apk").apply { mkdirs() }
                val destFile = java.io.File(shareDir, "Resonance.apk")
                sourceFile.copyTo(destFile, overwrite = true)
                destFile.setReadable(true, false)

                val uri = ApkFileProvider.getUriForFile(destFile)

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newRawUri("Resonance APK", uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Resonance APK")
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "Here is the Resonance APK! Install it on another Android device to sync audio across devices."
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(shareIntent, "Share Resonance APK").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                try {
                    grantUriPermission("com.android.intentresolver", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}

                runOnUiThread {
                    startActivity(chooser)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@ResonanceActivity, "Could not share APK: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildNeobrutalistLayout() {
        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(NeobrutalistTheme.COLOR_BACKGROUND)
        }
        setContentView(rootFrame)

        val rootScroll = ScrollView(this).apply {
            isFillViewport = true
        }
        rootFrame.addView(
            rootScroll,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        val defaultPadX = NeobrutalistTheme.dpToPx(this, 16f)
        val defaultPadTop = NeobrutalistTheme.dpToPx(this, 56f) // Generous top baseline
        val defaultPadBottom = NeobrutalistTheme.dpToPx(this, 28f)

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(defaultPadX, defaultPadTop, defaultPadX, defaultPadBottom)
        }
        rootScroll.addView(mainLayout)

        // Dynamically adjust padding for device status bar and camera punch-hole / cutout
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rootScroll.setOnApplyWindowInsetsListener { _, insets ->
                val statusBarInsets = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
                val topPad = maxOf(statusBarInsets.top, NeobrutalistTheme.dpToPx(this, 36f)) + NeobrutalistTheme.dpToPx(this, 16f)
                val navBarInsets = insets.getInsets(WindowInsets.Type.navigationBars())
                val bottomPad = navBarInsets.bottom + NeobrutalistTheme.dpToPx(this, 24f)
                mainLayout.setPadding(defaultPadX, topPad, defaultPadX, bottomPad)
                insets
            }
        } else {
            @Suppress("DEPRECATION")
            rootScroll.setOnApplyWindowInsetsListener { _, insets ->
                val topPad = maxOf(insets.systemWindowInsetTop, NeobrutalistTheme.dpToPx(this, 36f)) + NeobrutalistTheme.dpToPx(this, 16f)
                val bottomPad = insets.systemWindowInsetBottom + NeobrutalistTheme.dpToPx(this, 24f)
                mainLayout.setPadding(defaultPadX, topPad, defaultPadX, bottomPad)
                insets
            }
        }

        // 1. Header (Brand Logo + Fraunces Editorial Title + Subtitle)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }
        mainLayout.addView(headerRow)

        val logoSize = NeobrutalistTheme.dpToPx(this, 50f)
        val logoView = ImageView(this).apply {
            setImageResource(R.drawable.app_logo)
            layoutParams = LinearLayout.LayoutParams(logoSize, logoSize).apply {
                marginEnd = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 12f)
            }
            background = NeobrutalistTheme.card(
                this@ResonanceActivity,
                fillColor = Color.WHITE,
                cornerRadiusDp = 12f,
                shadowOffsetDp = 3f,
                strokeWidthDp = 2f
            )
            setPadding(
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 3f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 3f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 3f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 3f)
            )
            contentDescription = "Resonance Logo - Tap to open menu"
            isClickable = true
            setOnClickListener {
                openSideBar()
            }
        }
        headerRow.addView(logoView)

        val headerTextColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(headerTextColumn)

        val headerTitle = TextView(this).apply {
            text = NeobrutalistTheme.createEditorialTitle("Reso", "nance")
            textSize = 28f
            typeface = Typeface.SERIF
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
            setPadding(0, 0, 0, 2)
        }
        headerTextColumn.addView(headerTitle)

        val headerSubtitle = TextView(this).apply {
            text = "MULTI-DEVICE LOW-LATENCY AUDIO RELAY"
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.10f
            setTextColor(NeobrutalistTheme.COLOR_MUTED)
        }
        headerTextColumn.addView(headerSubtitle)

        // 2. SEGMENTED MODE SWITCHER PILL ([ SENDER ] / [ RECEIVER ])
        val modeSwitcherCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = NeobrutalistTheme.card(this@ResonanceActivity, fillColor = NeobrutalistTheme.COLOR_SURFACE, cornerRadiusDp = 14f, shadowOffsetDp = 4f)
            setPadding(6, 6, 6, 6)
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 14f)
            }
        }

        tabSender = TextView(this).apply {
            text = "🎙️  SENDER MODE"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, NeobrutalistTheme.dpToPx(this@ResonanceActivity, 12f), 0, NeobrutalistTheme.dpToPx(this@ResonanceActivity, 12f))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 4
            }
            setOnClickListener { switchMode(AppMode.SENDER) }
        }
        modeSwitcherCard.addView(tabSender)

        tabReceiver = TextView(this).apply {
            text = "🎧  RECEIVER MODE"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, NeobrutalistTheme.dpToPx(this@ResonanceActivity, 12f), 0, NeobrutalistTheme.dpToPx(this@ResonanceActivity, 12f))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 4
            }
            setOnClickListener { switchMode(AppMode.RECEIVER) }
        }
        modeSwitcherCard.addView(tabReceiver)
        mainLayout.addView(modeSwitcherCard)

        // 3. SENDER CONTAINER
        senderContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        buildSenderSection(senderContainer)
        mainLayout.addView(senderContainer)

        // 4. RECEIVER CONTAINER
        receiverContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        buildReceiverSection(receiverContainer)
        mainLayout.addView(receiverContainer)

        // 5. BOTTOM TAGLINE ("SAME SOUND. SAME SECOND. Together forever. ♥")
        buildBottomTaglineBanner(mainLayout)

        // 6. Scrim Overlay
        scrimView = View(this).apply {
            setBackgroundColor(Color.parseColor("#80000000"))
            visibility = View.GONE
            alpha = 0f
            setOnClickListener { closeSideBar() }
        }
        rootFrame.addView(
            scrimView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        // 7. Slide-out Side Bar
        buildSideBar(rootFrame)
    }

    // =========================================================================
    // SENDER SECTION BUILDER
    // =========================================================================
    private fun buildSenderSection(container: LinearLayout) {
        // --- Card: 5 Connected Device Circular Slots ---
        val slotsCard = createCardLayout(fillColor = NeobrutalistTheme.COLOR_SURFACE)
        val slotsHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 10)
        }

        val slotsTitle = TextView(this).apply {
            text = "CONNECTED RECEIVERS (MAX 5)"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        slotsHeaderRow.addView(slotsTitle)

        slotsCountBadge = TextView(this).apply {
            text = "0 / 5 ACTIVE"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = NeobrutalistTheme.pill(this@ResonanceActivity, fillColor = NeobrutalistTheme.COLOR_SECONDARY, shadowOffsetDp = 2f)
            setPadding(
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 10f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 4f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 10f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 4f)
            )
        }
        slotsHeaderRow.addView(slotsCountBadge)
        slotsCard.addView(slotsHeaderRow)

        // Horizontal Row of 5 Circular Slots
        val slotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 5f
            setPadding(0, 8, 0, 4)
        }

        for (i in 0 until 5) {
            val slotContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val circleView = TextView(this).apply {
                text = "+"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(NeobrutalistTheme.COLOR_MUTED)
                background = NeobrutalistTheme.circle(this@ResonanceActivity, fillColor = NeobrutalistTheme.COLOR_BACKGROUND, shadowOffsetDp = 2f)
                val size = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 48f)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }
            slotContainer.addView(circleView)

            val statusDot = View(this).apply {
                val dotSize = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 6f)
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 4f)
                    bottomMargin = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 2f)
                }
                background = NeobrutalistTheme.circle(this@ResonanceActivity, fillColor = Color.parseColor("#BDBDBD"), shadowOffsetDp = 0f, strokeWidthDp = 1f)
            }
            slotContainer.addView(statusDot)

            val labelView = TextView(this).apply {
                text = "Slot ${i + 1}"
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(NeobrutalistTheme.COLOR_MUTED)
                gravity = Gravity.CENTER
                maxLines = 1
            }
            slotContainer.addView(labelView)

            circularSlotViews.add(CircularSlotViewHolder(slotContainer, circleView, labelView, statusDot))
            slotsRow.addView(slotContainer)
        }
        slotsCard.addView(slotsRow)
        container.addView(slotsCard)

        // --- Card: Hero Audio Monitor Card ---
        val heroCard = createCardLayout(fillColor = NeobrutalistTheme.COLOR_SECONDARY)
        val heroHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }

        val heroTitle = TextView(this).apply {
            text = "AUDIO CAPTURE STREAM"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        heroHeaderRow.addView(heroTitle)

        senderStatusBadge = TextView(this).apply {
            text = "IDLE"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
            background = NeobrutalistTheme.pill(this@ResonanceActivity, fillColor = NeobrutalistTheme.COLOR_SURFACE, shadowOffsetDp = 2f)
            setPadding(
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 10f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 4f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 10f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 4f)
            )
        }
        heroHeaderRow.addView(senderStatusBadge)
        heroCard.addView(heroHeaderRow)

        senderStatusDetailText = TextView(this).apply {
            text = "Ready to capture and stream to selected receivers."
            textSize = 13f
            typeface = Typeface.DEFAULT
            setTextColor(Color.parseColor("#E0E0E0"))
            setPadding(0, 0, 0, 12)
        }
        heroCard.addView(senderStatusDetailText)

        senderAudioLevelText = TextView(this).apply {
            text = "Level: -100 dBFS (0%)"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 6)
        }
        heroCard.addView(senderAudioLevelText)

        senderAudioLevelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            setPadding(0, 0, 0, 8)
        }
        heroCard.addView(senderAudioLevelBar)

        senderSilenceIndicator = TextView(this).apply {
            text = "SILENCE (NO MEDIA DETECTED)"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(Color.parseColor("#FFA726"))
        }
        heroCard.addView(senderSilenceIndicator)
        container.addView(heroCard)

        // --- Card: Auto-Discovery & Receiver List ---
        val discoveryCard = createCardLayout(fillColor = NeobrutalistTheme.COLOR_SURFACE)
        val discHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
        }

        val discTitle = TextView(this).apply {
            text = "AVAILABLE RECEIVERS"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        discHeaderRow.addView(discTitle)

        val btnRescan = Button(this).apply {
            text = "⚡ Scan"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(NeobrutalistTheme.COLOR_BORDER)
            background = NeobrutalistTheme.button(this@ResonanceActivity, bgColor = NeobrutalistTheme.COLOR_ACCENT, shadowOffsetDp = 2f)
            setPadding(
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 12f),
                0,
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 12f),
                0
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 36f)
            )
            setOnClickListener {
                discoveryManager.sendDiscoveryPing()
                Toast.makeText(this@ResonanceActivity, "Scanning for receivers on Wi-Fi...", Toast.LENGTH_SHORT).show()
            }
        }
        discHeaderRow.addView(btnRescan)

        val btnIosLink = Button(this).apply {
            text = "📱 iOS / Web"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = NeobrutalistTheme.button(this@ResonanceActivity, bgColor = NeobrutalistTheme.COLOR_SECONDARY, shadowOffsetDp = 2f)
            setPadding(
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 10f),
                0,
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 10f),
                0
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 36f)
            ).apply {
                marginStart = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 6f)
            }
            setOnClickListener {
                val clients = captureService?.getConnectedWebClients() ?: 0
                IosConnectDialogHelper.showDialog(this@ResonanceActivity, port = 8080, connectedWebClients = clients)
            }
        }
        discHeaderRow.addView(btnIosLink)

        discoveryCard.addView(discHeaderRow)

        val discDesc = TextView(this).apply {
            text = "Receivers on the same Wi-Fi are automatically discovered via mDNS & UDP beacon."
            textSize = 11f
            setTextColor(NeobrutalistTheme.COLOR_MUTED)
            setPadding(0, 0, 0, 10)
        }
        discoveryCard.addView(discDesc)

        senderDevicesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 12)
        }
        discoveryCard.addView(senderDevicesContainer)

        // Fallback Manual IP Entry
        val manualRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        manualIpInput = EditText(this).apply {
            hint = "Manual IP (e.g. 192.168.1.105)"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
            setHintTextColor(Color.parseColor("#9E9E9E"))
            background = NeobrutalistTheme.input(this@ResonanceActivity)
            setPadding(
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 12f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 10f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 12f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 10f)
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 8f)
            }
        }
        manualRow.addView(manualIpInput)

        btnAddManualIp = Button(this).apply {
            text = "+ Add"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = NeobrutalistTheme.button(this@ResonanceActivity, bgColor = NeobrutalistTheme.COLOR_SECONDARY, shadowOffsetDp = 2f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 40f)
            )
            setOnClickListener {
                val ip = manualIpInput.text.toString().trim()
                if (discoveryManager.addManualDevice(ip)) {
                    manualIpInput.text.clear()
                    Toast.makeText(this@ResonanceActivity, "Added receiver $ip", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@ResonanceActivity, "Invalid IP address format", Toast.LENGTH_SHORT).show()
                }
            }
        }
        manualRow.addView(btnAddManualIp)
        discoveryCard.addView(manualRow)
        container.addView(discoveryCard)

        // --- Card: Streaming Diagnostics ---
        val diagCard = createCardLayout(fillColor = NeobrutalistTheme.COLOR_SURFACE)
        val diagTitle = createCardTitle("TRANSMISSION DIAGNOSTICS")
        diagCard.addView(diagTitle)

        statFramesText = createStatRow(diagCard, "Frames Captured", "0")
        statSamplesText = createStatRow(diagCard, "Samples Captured", "0")
        statDataText = createStatRow(diagCard, "Audio Data Processed", "0.00 MB")
        statDurationText = createStatRow(diagCard, "Streaming Duration", "00:00")
        statPacketsSentText = createStatRow(diagCard, "Packets Transmitted", "0")
        statDataSentText = createStatRow(diagCard, "Network Data Sent", "0.00 MB")
        statNetworkErrorsText = createStatRow(diagCard, "Network Send Errors", "0")
        container.addView(diagCard)

        // --- Action Buttons (Start / Stop) ---
        val actionsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 16)
        }

        btnStartSender = Button(this).apply {
            text = "START AUDIO SHARING"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(Color.WHITE)
            background = NeobrutalistTheme.button(this@ResonanceActivity, bgColor = NeobrutalistTheme.COLOR_PRIMARY, shadowOffsetDp = 4f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 52f)
            ).apply {
                bottomMargin = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 10f)
            }
            setOnClickListener { checkSenderPermissionsAndStart() }
        }
        actionsLayout.addView(btnStartSender)

        btnStopSender = Button(this).apply {
            text = "STOP STREAMING"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(Color.WHITE)
            background = NeobrutalistTheme.button(this@ResonanceActivity, bgColor = NeobrutalistTheme.COLOR_SECONDARY, shadowOffsetDp = 4f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 52f)
            )
            setOnClickListener { stopSenderStreaming() }
        }
        actionsLayout.addView(btnStopSender)
        container.addView(actionsLayout)
    }

    // =========================================================================
    // RECEIVER SECTION BUILDER
    // =========================================================================
    private fun buildReceiverSection(container: LinearLayout) {
        // --- Card: Device Identity & Wi-Fi IP ---
        val idCard = createCardLayout(fillColor = NeobrutalistTheme.COLOR_SURFACE)
        val idTitle = createCardTitle("THIS RECEIVER IDENTITY")
        idCard.addView(idTitle)

        val localDeviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        val devNameText = TextView(this).apply {
            text = localDeviceName
            textSize = 18f
            typeface = Typeface.SERIF
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
            setPadding(0, 0, 0, 2)
        }
        idCard.addView(devNameText)

        val ipSubText = TextView(this).apply {
            val ip = getLocalIpAddress() ?: "Not connected to Wi-Fi"
            text = "IP: $ip  •  Playout Port: 50005"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(NeobrutalistTheme.COLOR_MUTED)
            setPadding(0, 0, 0, 4)
        }
        idCard.addView(ipSubText)

        val beaconStatus = TextView(this).apply {
            text = "AUTO-ADVERTISING ON BEACON PORT 50006 & MDNS"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            setTextColor(NeobrutalistTheme.COLOR_SUCCESS)
        }
        idCard.addView(beaconStatus)
        container.addView(idCard)

        // --- Card: Hero Playout Monitor Card ---
        val heroCard = createCardLayout(fillColor = NeobrutalistTheme.COLOR_SECONDARY)
        val heroHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }

        val heroTitle = TextView(this).apply {
            text = "RECEIVER PLAYOUT MONITOR"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        heroHeaderRow.addView(heroTitle)

        receiverStatusBadge = TextView(this).apply {
            text = "STOPPED"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
            background = NeobrutalistTheme.pill(this@ResonanceActivity, fillColor = NeobrutalistTheme.COLOR_SURFACE, shadowOffsetDp = 2f)
            setPadding(
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 10f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 4f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 10f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 4f)
            )
        }
        heroHeaderRow.addView(receiverStatusBadge)
        heroCard.addView(heroHeaderRow)

        receiverStatusDetailText = TextView(this).apply {
            text = "Ready to listen for incoming UDP audio stream."
            textSize = 13f
            typeface = Typeface.DEFAULT
            setTextColor(Color.parseColor("#E0E0E0"))
            setPadding(0, 0, 0, 12)
        }
        heroCard.addView(receiverStatusDetailText)

        receiverAudioLevelText = TextView(this).apply {
            text = "Output Level: -100 dBFS (0%)"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 6)
        }
        heroCard.addView(receiverAudioLevelText)

        receiverAudioLevelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            setPadding(0, 0, 0, 8)
        }
        heroCard.addView(receiverAudioLevelBar)
        container.addView(heroCard)

        // --- Card: Playout Delay & Fine Tuning ---
        val delayCard = createCardLayout(fillColor = NeobrutalistTheme.COLOR_SURFACE)
        val delayCardTitle = createCardTitle("AUDIO SYNC OFFSET & FINE TUNING")
        delayCard.addView(delayCardTitle)

        delayLabel = TextView(this).apply {
            text = "Sync Offset: 0 ms"
            textSize = 20f
            typeface = Typeface.SERIF
            setTextColor(NeobrutalistTheme.COLOR_PRIMARY)
            setPadding(0, 0, 0, 2)
        }
        delayCard.addView(delayLabel)

        delaySubLabel = TextView(this).apply {
            text = "Balanced baseline cushion (80 ms)"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            setTextColor(NeobrutalistTheme.COLOR_MUTED)
            setPadding(0, 0, 0, 12)
        }
        delayCard.addView(delaySubLabel)

        // Continuous 1 ms slider (-100 ms to +500 ms, total 600)
        delaySeekBar = SeekBar(this).apply {
            max = 600
            progress = receiverDelayMs + 100
            setPadding(0, 12, 0, 16)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val offset = progress - 100
                    receiverDelayMs = offset
                    playbackService?.setOffsetMs(offset)
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putInt(KEY_DELAY_MS, offset)
                        .apply()
                    updateDelayLabels(offset)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        delayCard.addView(delaySeekBar)

        // Button Row 1 (Quick Jumps)
        val buttonRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 6)
            weightSum = 5f
        }

        fun createStepButton(label: String, delta: Int): Button {
            return Button(this).apply {
                text = label
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
                background = NeobrutalistTheme.button(this@ResonanceActivity, bgColor = NeobrutalistTheme.COLOR_SURFACE, shadowOffsetDp = 2f)
                layoutParams = LinearLayout.LayoutParams(0, NeobrutalistTheme.dpToPx(this@ResonanceActivity, 40f), 1f).apply {
                    marginEnd = 4
                    marginStart = 4
                }
                setOnClickListener {
                    delaySeekBar.progress = (delaySeekBar.progress + delta).coerceIn(0, 600)
                }
            }
        }

        buttonRow1.addView(createStepButton("-20ms", -20))
        buttonRow1.addView(createStepButton("-5ms", -5))
        btnResetDelay = Button(this).apply {
            text = "Reset 0"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(NeobrutalistTheme.COLOR_BORDER)
            background = NeobrutalistTheme.button(this@ResonanceActivity, bgColor = NeobrutalistTheme.COLOR_ACCENT, shadowOffsetDp = 2f)
            layoutParams = LinearLayout.LayoutParams(0, NeobrutalistTheme.dpToPx(this@ResonanceActivity, 40f), 1f).apply {
                marginEnd = 4
                marginStart = 4
            }
            setOnClickListener {
                delaySeekBar.progress = 100
            }
        }
        buttonRow1.addView(btnResetDelay)
        buttonRow1.addView(createStepButton("+5ms", +5))
        buttonRow1.addView(createStepButton("+20ms", +20))
        delayCard.addView(buttonRow1)

        // Button Row 2 (Micro-nudges 1ms)
        val buttonRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 10)
            weightSum = 2f
        }

        val btnMinus1 = Button(this).apply {
            text = "◀  -1 ms Nudge"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            setTextColor(Color.WHITE)
            background = NeobrutalistTheme.button(this@ResonanceActivity, bgColor = NeobrutalistTheme.COLOR_SECONDARY, shadowOffsetDp = 2f)
            layoutParams = LinearLayout.LayoutParams(0, NeobrutalistTheme.dpToPx(this@ResonanceActivity, 42f), 1f).apply {
                marginEnd = 6
            }
            setOnClickListener {
                delaySeekBar.progress = (delaySeekBar.progress - 1).coerceIn(0, 600)
            }
        }
        buttonRow2.addView(btnMinus1)

        val btnPlus1 = Button(this).apply {
            text = "+1 ms Nudge  ▶"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            setTextColor(Color.WHITE)
            background = NeobrutalistTheme.button(this@ResonanceActivity, bgColor = NeobrutalistTheme.COLOR_SECONDARY, shadowOffsetDp = 2f)
            layoutParams = LinearLayout.LayoutParams(0, NeobrutalistTheme.dpToPx(this@ResonanceActivity, 42f), 1f).apply {
                marginStart = 6
            }
            setOnClickListener {
                delaySeekBar.progress = (delaySeekBar.progress + 1).coerceIn(0, 600)
            }
        }
        buttonRow2.addView(btnPlus1)
        delayCard.addView(buttonRow2)
        container.addView(delayCard)

        // --- Card: Playout Diagnostics & Sink ---
        val diagCard = createCardLayout(fillColor = NeobrutalistTheme.COLOR_SURFACE)
        val diagTitle = createCardTitle("RECEIVER PLAYOUT DIAGNOSTICS")
        diagCard.addView(diagTitle)

        receiverPacketsText = createStatRow(diagCard, "Packets Received", "0")
        receiverBytesText = createStatRow(diagCard, "Data Received", "0.00 MB")
        receiverBufferDepthText = createStatRow(diagCard, "Jitter Cushion Depth", "0 ms")
        receiverDroppedPacketsText = createStatRow(diagCard, "Packets Dropped / Trimmed", "0")
        receiverAudioSinkText = createStatRow(diagCard, "Active Audio Sink", detectAudioOutputSink())
        container.addView(diagCard)

        // --- Action Buttons (Start / Stop) ---
        val actionsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 16)
        }

        btnStartReceiver = Button(this).apply {
            text = "START RECEIVER (LISTEN)"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(Color.WHITE)
            background = NeobrutalistTheme.button(this@ResonanceActivity, bgColor = NeobrutalistTheme.COLOR_PRIMARY, shadowOffsetDp = 4f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 52f)
            ).apply {
                bottomMargin = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 10f)
            }
            setOnClickListener { checkReceiverPermissionsAndStart() }
        }
        actionsLayout.addView(btnStartReceiver)

        btnStopReceiver = Button(this).apply {
            text = "STOP RECEIVER"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(Color.WHITE)
            background = NeobrutalistTheme.button(this@ResonanceActivity, bgColor = NeobrutalistTheme.COLOR_SECONDARY, shadowOffsetDp = 4f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 52f)
            )
            setOnClickListener { stopReceiverPlayout() }
        }
        actionsLayout.addView(btnStopReceiver)
        container.addView(actionsLayout)
    }

    // =========================================================================
    // MODE SWITCHING & MUTUAL EXCLUSION
    // =========================================================================
    private fun switchMode(newMode: AppMode) {
        if (currentMode == newMode) return

        // 1. Mutually exclusive teardown of opposite mode
        if (currentMode == AppMode.SENDER) {
            val dismissIntent = Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_DISMISS
            }
            startService(dismissIntent)
            stopSenderStreaming()
            discoveryManager.stop()
        } else {
            val dismissIntent = Intent(this, PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_DISMISS
            }
            startService(dismissIntent)
            stopReceiverPlayout()
        }

        currentMode = newMode

        // 2. Persist mode selection
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CURRENT_MODE, newMode.name)
            .apply()

        applyCurrentModeUi(initialSetup = false)
    }

    private fun applyCurrentModeUi(initialSetup: Boolean) {
        val selectedBg = NeobrutalistTheme.pill(this, fillColor = NeobrutalistTheme.COLOR_PRIMARY, shadowOffsetDp = 2f)
        val unselectedBg = null

        if (currentMode == AppMode.SENDER) {
            tabSender.setTextColor(Color.WHITE)
            tabSender.background = selectedBg

            tabReceiver.setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
            tabReceiver.background = unselectedBg

            senderContainer.visibility = View.VISIBLE
            receiverContainer.visibility = View.GONE

            // Start discovery for sender
            discoveryManager.setListener { devices ->
                runOnUiThread { updateSenderDevicesList(devices) }
            }
            discoveryManager.start()

            // Bind to CaptureService if running
            val intent = Intent(this, CaptureService::class.java)
            bindService(intent, captureConnection, Context.BIND_AUTO_CREATE)
        } else {
            tabReceiver.setTextColor(Color.WHITE)
            tabReceiver.background = selectedBg

            tabSender.setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
            tabSender.background = unselectedBg

            receiverContainer.visibility = View.VISIBLE
            senderContainer.visibility = View.GONE

            updateDelayLabels(receiverDelayMs)
            receiverAudioSinkText.text = detectAudioOutputSink()

            // Bind to PlaybackService if running
            val intent = Intent(this, PlaybackService::class.java)
            bindService(intent, playbackConnection, Context.BIND_AUTO_CREATE)
        }
    }

    // =========================================================================
    // SENDER ACTIONS & UI UPDATES
    // =========================================================================
    private fun checkSenderPermissionsAndStart() {
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), permissionRequestCodeSender)
        } else {
            requestScreenCaptureConsent()
        }
    }

    private fun requestScreenCaptureConsent() {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        if (projectionManager == null) {
            Toast.makeText(this, "MediaProjection not available on this device", Toast.LENGTH_LONG).show()
            return
        }
        val consentIntent = projectionManager.createScreenCaptureIntent()
        startActivityForResult(consentIntent, captureRequestCode)
    }

    private fun stopSenderStreaming() {
        captureService?.stopByUser()
        if (isCaptureBound) {
            try { unbindService(captureConnection) } catch (_: Exception) {}
            isCaptureBound = false
        }
        captureService = null
        val stopIntent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_STOP
        }
        startService(stopIntent)
    }

    private fun updateSenderDevicesList(devices: List<RemoteReceiver>) {
        // 1. Update the 5 Circular Slots
        val selectedDevices = devices.filter { it.isSelected }.take(5)
        slotsCountBadge.text = "${selectedDevices.size} / 5 ACTIVE"

        for (i in 0 until 5) {
            val holder = circularSlotViews.getOrNull(i) ?: continue
            val dev = selectedDevices.getOrNull(i)

            if (dev != null) {
                holder.circleView.text = dev.initials
                holder.circleView.setTextColor(Color.WHITE)
                holder.circleView.background = NeobrutalistTheme.circle(
                    this,
                    fillColor = NeobrutalistTheme.COLOR_PRIMARY,
                    shadowOffsetDp = 2f
                )
                holder.labelView.text = dev.name
                holder.labelView.setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
                holder.statusDot.background = NeobrutalistTheme.circle(
                    this,
                    fillColor = NeobrutalistTheme.COLOR_SUCCESS,
                    shadowOffsetDp = 0f,
                    strokeWidthDp = 1f
                )
            } else {
                holder.circleView.text = "+"
                holder.circleView.setTextColor(NeobrutalistTheme.COLOR_MUTED)
                holder.circleView.background = NeobrutalistTheme.circle(
                    this,
                    fillColor = NeobrutalistTheme.COLOR_BACKGROUND,
                    shadowOffsetDp = 2f
                )
                holder.labelView.text = "Slot ${i + 1}"
                holder.labelView.setTextColor(NeobrutalistTheme.COLOR_MUTED)
                holder.statusDot.background = NeobrutalistTheme.circle(
                    this,
                    fillColor = Color.parseColor("#BDBDBD"),
                    shadowOffsetDp = 0f,
                    strokeWidthDp = 1f
                )
            }
        }

        // Sync with active capture service
        captureService?.setTargetHosts(selectedDevices.map { it.ip })

        // 2. Render List of Available Receivers
        senderDevicesContainer.removeAllViews()

        if (devices.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "Scanning Wi-Fi... Open Resonance in Receiver Mode on other devices or enter IP below."
                textSize = 12f
                setTextColor(NeobrutalistTheme.COLOR_MUTED)
                setPadding(0, 8, 0, 8)
            }
            senderDevicesContainer.addView(emptyText)
            return
        }

        for (dev in devices) {
            val itemCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = NeobrutalistTheme.card(
                    this@ResonanceActivity,
                    fillColor = if (dev.isSelected) Color.parseColor("#FFF8F0") else Color.WHITE,
                    cornerRadiusDp = 12f,
                    shadowOffsetDp = 2f
                )
                setPadding(
                    NeobrutalistTheme.dpToPx(this@ResonanceActivity, 12f),
                    NeobrutalistTheme.dpToPx(this@ResonanceActivity, 10f),
                    NeobrutalistTheme.dpToPx(this@ResonanceActivity, 12f),
                    NeobrutalistTheme.dpToPx(this@ResonanceActivity, 10f)
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 8f)
                }
            }

            // Initials badge
            val badge = TextView(this).apply {
                text = dev.initials
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (dev.isSelected) Color.WHITE else NeobrutalistTheme.COLOR_BORDER)
                gravity = Gravity.CENTER
                background = NeobrutalistTheme.circle(
                    this@ResonanceActivity,
                    fillColor = if (dev.isSelected) NeobrutalistTheme.COLOR_PRIMARY else NeobrutalistTheme.COLOR_BACKGROUND,
                    shadowOffsetDp = 1f
                )
                val size = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 36f)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 10f)
                }
            }
            itemCard.addView(badge)

            // Info column
            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val nameText = TextView(this).apply {
                text = dev.name
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
            }
            nameRow.addView(nameText)

            if (dev.isManual) {
                val manualTag = TextView(this).apply {
                    text = "MANUAL"
                    textSize = 8f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    background = NeobrutalistTheme.pill(this@ResonanceActivity, fillColor = NeobrutalistTheme.COLOR_SECONDARY, shadowOffsetDp = 1f)
                    setPadding(8, 2, 8, 2)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = 8
                    }
                }
                nameRow.addView(manualTag)
            }
            infoCol.addView(nameRow)

            val ipText = TextView(this).apply {
                text = "IP: ${dev.ip}  •  Port: ${dev.port}"
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(NeobrutalistTheme.COLOR_MUTED)
            }
            infoCol.addView(ipText)
            itemCard.addView(infoCol)

            // Selection toggle button
            val btnToggle = Button(this).apply {
                text = if (dev.isSelected) "✓ ACTIVE" else "+ ADD"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (dev.isSelected) Color.WHITE else NeobrutalistTheme.COLOR_BORDER)
                background = NeobrutalistTheme.button(
                    this@ResonanceActivity,
                    bgColor = if (dev.isSelected) NeobrutalistTheme.COLOR_SUCCESS else NeobrutalistTheme.COLOR_SURFACE,
                    shadowOffsetDp = 2f
                )
                setPadding(
                    NeobrutalistTheme.dpToPx(this@ResonanceActivity, 10f),
                    0,
                    NeobrutalistTheme.dpToPx(this@ResonanceActivity, 10f),
                    0
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    NeobrutalistTheme.dpToPx(this@ResonanceActivity, 36f)
                )
                setOnClickListener {
                    discoveryManager.toggleDevice(dev.ip)
                }
            }
            itemCard.addView(btnToggle)

            if (dev.isManual) {
                val btnDelete = Button(this).apply {
                    text = "✕"
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    background = NeobrutalistTheme.button(this@ResonanceActivity, bgColor = NeobrutalistTheme.COLOR_ERROR, shadowOffsetDp = 2f)
                    layoutParams = LinearLayout.LayoutParams(
                        NeobrutalistTheme.dpToPx(this@ResonanceActivity, 36f),
                        NeobrutalistTheme.dpToPx(this@ResonanceActivity, 36f)
                    ).apply {
                        marginStart = 6
                    }
                    setOnClickListener {
                        discoveryManager.removeManualDevice(dev.ip)
                    }
                }
                itemCard.addView(btnDelete)
            }

            senderDevicesContainer.addView(itemCard)
        }
    }

    private fun updateSenderUi(snapshot: CaptureDiagnostics) {
        senderStatusBadge.text = snapshot.state.displayName.uppercase()
        senderStatusDetailText.text = snapshot.statusDetail

        senderAudioLevelText.text = "Level: ${String.format("%.1f", snapshot.currentRmsDbfs)} dBFS (${snapshot.peakPercent}%)"
        senderAudioLevelBar.progress = snapshot.peakPercent

        if (snapshot.isSilent) {
            senderSilenceIndicator.text = "SILENCE (NO MEDIA DETECTED)"
            senderSilenceIndicator.setTextColor(Color.parseColor("#FFA726"))
        } else {
            senderSilenceIndicator.text = "NON-SILENT MEDIA ACTIVE"
            senderSilenceIndicator.setTextColor(NeobrutalistTheme.COLOR_SUCCESS)
        }

        statFramesText.text = "${snapshot.totalFrames}"
        statSamplesText.text = "${snapshot.totalSamples}"
        statDataText.text = snapshot.totalMegabytesFormatted
        statDurationText.text = snapshot.activeDurationFormatted
        statPacketsSentText.text = "${snapshot.packetsSent}"
        statDataSentText.text = snapshot.totalMegabytesSentFormatted
        statNetworkErrorsText.text = "${snapshot.networkErrors}"
    }

    // =========================================================================
    // RECEIVER ACTIONS & UI UPDATES
    // =========================================================================
    private fun checkReceiverPermissionsAndStart() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), permissionRequestCodeReceiver)
        } else {
            startReceiverPlayout()
        }
    }

    private fun startReceiverPlayout() {
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, playbackConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopReceiverPlayout() {
        playbackService?.stopPlayback(removeNotification = false)
        if (isPlaybackBound) {
            try { unbindService(playbackConnection) } catch (_: Exception) {}
            isPlaybackBound = false
        }
        playbackService = null
        val stopIntent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_STOP
        }
        startService(stopIntent)
    }

    private fun updateDelayLabels(offset: Int) {
        val sign = if (offset > 0) "+" else ""
        delayLabel.text = "Sync Offset: $sign$offset ms"
        val totalCushion = 80 + offset
        delaySubLabel.text = "Playout cushion: ${totalCushion} ms ($sign$offset ms dynamic sync delta)"
    }

    private fun updateReceiverUi(snapshot: PlaybackDiagnostics) {
        receiverStatusBadge.text = snapshot.trackState.uppercase()
        receiverStatusDetailText.text = snapshot.statusMessage

        receiverAudioLevelText.text = "Output Level: ${String.format("%.1f", snapshot.rmsDbfs)} dBFS (${snapshot.peakPercent}%)"
        receiverAudioLevelBar.progress = snapshot.peakPercent

        receiverPacketsText.text = "${snapshot.totalPackets}"
        receiverBytesText.text = String.format("%.2f MB", snapshot.totalBytes.toDouble() / (1024.0 * 1024.0))
        receiverBufferDepthText.text = "${snapshot.bufferDepthMs} ms (${snapshot.queuedFrames} frames queued)"
        receiverDroppedPacketsText.text = "${snapshot.droppedPackets} (Underruns: ${snapshot.underrunCount})"
        receiverAudioSinkText.text = detectAudioOutputSink()
    }

    private fun detectAudioOutputSink(): String {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "Unknown"
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (dev in devices) {
            when (dev.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                    val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try { dev.address } catch (_: Exception) { "Bluetooth" }
                    } else "Bluetooth"
                    return "🎧 Bluetooth ($name)"
                }
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> return "🎧 Wired Headphones"
                AudioDeviceInfo.TYPE_USB_HEADSET -> return "🎧 USB Headset"
            }
        }
        return "📱 Built-in Speaker"
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addresses = intf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    // =========================================================================
    // PERMISSIONS & ACTIVITY RESULTS
    // =========================================================================
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCodeSender) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                requestScreenCaptureConsent()
            } else {
                Toast.makeText(this, "Permissions required for audio capture", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == permissionRequestCodeReceiver) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                startReceiverPlayout()
            } else {
                Toast.makeText(this, "Notification permission required for background playback", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequestCode) {
            if (resultCode == RESULT_OK && data != null) {
                val selectedIps = ArrayList(discoveryManager.getSelectedIps())
                val serviceIntent = Intent(this, CaptureService::class.java).apply {
                    action = CaptureService.ACTION_START
                    putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                    putStringArrayListExtra(CaptureService.EXTRA_HOSTS, selectedIps)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                bindService(serviceIntent, captureConnection, Context.BIND_AUTO_CREATE)
            } else {
                Toast.makeText(this, "Screen capture consent was cancelled.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // =========================================================================
    // UI HELPERS (CREAM NEOBRUTALIST STYLING)
    // =========================================================================
    private fun createCardLayout(fillColor: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NeobrutalistTheme.card(this@ResonanceActivity, fillColor = fillColor, shadowOffsetDp = 4f)
            setPadding(
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 16f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 14f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 16f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 14f)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 14f)
            }
        }
    }

    private fun createCardTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
            setPadding(0, 0, 0, 8)
        }
    }

    private fun createStatRow(card: LinearLayout, label: String, initialValue: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
        }
        val labelView = TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(NeobrutalistTheme.COLOR_MUTED)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(labelView)

        val valView = TextView(this).apply {
            text = initialValue
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
        }
        row.addView(valView)
        card.addView(row)
        return valView
    }

    // =========================================================================
    // BOTTOM TAGLINE ("SAME SOUND. SAME SECOND. Together forever. ♥")
    // Subtle, minimal, center-aligned text layout
    // =========================================================================
    private fun buildBottomTaglineBanner(parent: LinearLayout) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 20f)
                bottomMargin = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 16f)
            }
            setPadding(
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 16f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 8f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 16f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 8f)
            )
            isClickable = true
            isLongClickable = true
            contentDescription = "Together forever. Tap to share APK"
            setOnClickListener {
                shareInstalledApk()
            }
            setOnLongClickListener {
                shareInstalledApk()
                true
            }
        }

        // 1. Subtle Eyebrow (Letter-spaced, muted)
        val eyebrow = TextView(this).apply {
            text = "SAME SOUND. SAME SECOND."
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.14f
            setTextColor(NeobrutalistTheme.COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, NeobrutalistTheme.dpToPx(this@ResonanceActivity, 4f))
        }
        container.addView(eyebrow)

        // 2. Subtle Editorial Headline with Heart Glyph ("Together forever. ♥")
        val headline = TextView(this).apply {
            val ssb = SpannableStringBuilder()
            val span1 = SpannableString("Together ")
            span1.setSpan(StyleSpan(Typeface.BOLD), 0, span1.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.append(span1)

            val span2 = SpannableString("forever. ")
            span2.setSpan(StyleSpan(Typeface.ITALIC), 0, span2.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.append(span2)

            val heartSpan = SpannableString("♥")
            heartSpan.setSpan(ForegroundColorSpan(Color.parseColor("#E85D8A")), 0, heartSpan.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            heartSpan.setSpan(StyleSpan(Typeface.BOLD), 0, heartSpan.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.append(heartSpan)

            text = ssb
            textSize = 14f
            typeface = Typeface.SERIF
            setTextColor(Color.parseColor("#4A4A4A"))
            gravity = Gravity.CENTER
        }
        container.addView(headline)

        parent.addView(container)
    }

    // =========================================================================
    // SIDE BAR BUILDER (DRAWER)
    // =========================================================================
    private fun buildSideBar(root: FrameLayout) {
        val sideBarWidth = minOf(resources.displayMetrics.widthPixels * 85 / 100, NeobrutalistTheme.dpToPx(this, 340f))

        sideBarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(NeobrutalistTheme.COLOR_BACKGROUND)
                setStroke(NeobrutalistTheme.dpToPx(this@ResonanceActivity, 2.5f), NeobrutalistTheme.COLOR_BORDER)
                cornerRadii = floatArrayOf(
                    0f, 0f,
                    NeobrutalistTheme.dpToPx(this@ResonanceActivity, 18f).toFloat(), NeobrutalistTheme.dpToPx(this@ResonanceActivity, 18f).toFloat(),
                    NeobrutalistTheme.dpToPx(this@ResonanceActivity, 18f).toFloat(), NeobrutalistTheme.dpToPx(this@ResonanceActivity, 18f).toFloat(),
                    0f, 0f
                )
            }
            elevation = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 16f).toFloat()
            layoutParams = FrameLayout.LayoutParams(sideBarWidth, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START
            }
            visibility = View.GONE
            translationX = -sideBarWidth.toFloat()
            isClickable = true
        }

        val sideBarScroll = ScrollView(this).apply {
            isFillViewport = true
        }
        sideBarContainer.addView(
            sideBarScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        )

        val sideBarContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padX = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 16f)
            val padTop = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 52f)
            val padBottom = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 28f)
            setPadding(padX, padTop, padX, padBottom)
        }
        sideBarScroll.addView(sideBarContent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sideBarContainer.setOnApplyWindowInsetsListener { _, insets ->
                val statusBarInsets = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
                val topPad = maxOf(statusBarInsets.top, NeobrutalistTheme.dpToPx(this, 36f)) + NeobrutalistTheme.dpToPx(this, 16f)
                val navBarInsets = insets.getInsets(WindowInsets.Type.navigationBars())
                val bottomPad = navBarInsets.bottom + NeobrutalistTheme.dpToPx(this, 24f)
                val padX = NeobrutalistTheme.dpToPx(this, 16f)
                sideBarContent.setPadding(padX, topPad, padX, bottomPad)
                insets
            }
        }

        // --- Side Bar Header ---
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, NeobrutalistTheme.dpToPx(this@ResonanceActivity, 16f))
        }
        sideBarContent.addView(headerRow)

        val logoSize = NeobrutalistTheme.dpToPx(this, 44f)
        val logoView = ImageView(this).apply {
            setImageResource(R.drawable.app_logo)
            layoutParams = LinearLayout.LayoutParams(logoSize, logoSize).apply {
                marginEnd = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 12f)
            }
            background = NeobrutalistTheme.card(
                this@ResonanceActivity,
                fillColor = Color.WHITE,
                cornerRadiusDp = 10f,
                shadowOffsetDp = 2f,
                strokeWidthDp = 1.5f
            )
            setPadding(
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 2f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 2f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 2f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 2f)
            )
        }
        headerRow.addView(logoView)

        val (verCode, verName) = AppUpdateManager.getCurrentVersion(this)

        val headerTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(headerTextCol)

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerTextCol.addView(titleRow)

        val titleView = TextView(this).apply {
            text = NeobrutalistTheme.createEditorialTitle("Reso", "nance")
            textSize = 21f
            typeface = Typeface.SERIF
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
        }
        titleRow.addView(titleView)

        val versionPill = TextView(this).apply {
            text = "v$verName"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EAE4D9"))
                setStroke(NeobrutalistTheme.dpToPx(this@ResonanceActivity, 1f), NeobrutalistTheme.COLOR_BORDER)
                cornerRadius = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 6f).toFloat()
            }
            setPadding(
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 6f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 2f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 6f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 2f)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 8f)
            }
        }
        titleRow.addView(versionPill)

        val subtitleView = TextView(this).apply {
            text = "LOW-LATENCY AUDIO RELAY"
            textSize = 8.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.10f
            setTextColor(NeobrutalistTheme.COLOR_MUTED)
            setPadding(0, NeobrutalistTheme.dpToPx(this@ResonanceActivity, 1f), 0, 0)
        }
        headerTextCol.addView(subtitleView)

        // Close Button (Neobrutalist [ ✕ ])
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
            val btnSize = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 34f)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            background = NeobrutalistTheme.card(
                this@ResonanceActivity,
                fillColor = Color.WHITE,
                cornerRadiusDp = 8f,
                shadowOffsetDp = 2f,
                strokeWidthDp = 1.5f
            )
            setOnClickListener { closeSideBar() }
        }
        headerRow.addView(closeBtn)

        // Divider
        val divider = View(this).apply {
            setBackgroundColor(NeobrutalistTheme.COLOR_BORDER)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 1.5f)
            ).apply {
                bottomMargin = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 16f)
            }
        }
        sideBarContent.addView(divider)

        // Menu Section Eyebrow
        val menuSectionHeader = TextView(this).apply {
            text = "ACTIONS & LINKS"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.10f
            setTextColor(NeobrutalistTheme.COLOR_MUTED)
            setPadding(
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 2f),
                0,
                0,
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 10f)
            )
        }
        sideBarContent.addView(menuSectionHeader)

        // --- Individual Menu Item 1: Share Working APK ---
        val itemShare = buildSideBarMenuItem(
            iconText = "📤",
            iconBgColor = Color.parseColor("#FFF2EB"),
            title = "Share Working APK",
            subtitle = "Send installed APK to nearby devices",
            trailingText = "›",
            onClick = {
                closeSideBar()
                shareInstalledApk()
            }
        )
        sideBarContent.addView(itemShare)

        // --- Individual Menu Item 2: Connect iOS / Web ---
        val itemIos = buildSideBarMenuItem(
            iconText = "📱",
            iconBgColor = Color.parseColor("#FEF3C7"),
            title = "Connect iOS / Web",
            subtitle = "Scan QR to stream to iPhone or Safari",
            trailingText = "›",
            onClick = {
                closeSideBar()
                val clients = captureService?.getConnectedWebClients() ?: 0
                IosConnectDialogHelper.showDialog(this@ResonanceActivity, port = 8080, connectedWebClients = clients)
            }
        )
        sideBarContent.addView(itemIos)

        // --- Individual Menu Item 2: Check for Updates ---
        val itemUpdates = buildSideBarMenuItem(
            iconText = "🔄",
            iconBgColor = Color.parseColor("#EDF7ED"),
            title = "Check for Updates",
            subtitle = "Current: v$verName (tap to check)",
            trailingText = "›",
            onClick = {
                checkAppUpdatesManual()
            },
            onLongClick = {
                UpdateDialogHelper.showUpdateDialog(
                    this@ResonanceActivity,
                    ReleaseInfo(
                        tagName = "v1.2.0",
                        versionName = "1.2.0",
                        versionCode = 3,
                        name = "Resonance v1.2.0 — Zero-Install iOS & Web Audio Link",
                        releaseNotes = "• Zero-install iOS Safari & Web receiver capability\n• Embedded local WebRelayServer on port 8080 (offline/hotspot ready)\n• High-contrast QR code pairing with native iOS Camera\n• Cream Neobrutalist Web client with delay sync & OLED Cinema mode\n• Microphone broadcast from iOS to Android",
                        htmlUrl = UpdateConfig.getWebReleasesUrl(),
                        apkDownloadUrl = null
                    ),
                    currentVersionName = verName
                )
                true
            }
        )
        sideBarContent.addView(itemUpdates)

        // --- Individual Menu Item 3: GitHub Repository ---
        val itemRepo = buildSideBarMenuItem(
            iconText = "🌐",
            iconBgColor = Color.parseColor("#F5F0E8"),
            title = "GitHub Repository",
            subtitle = "${UpdateConfig.GITHUB_OWNER}/${UpdateConfig.GITHUB_REPO}",
            trailingText = "↗",
            onClick = {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UpdateConfig.getRepoWebUrl())).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this@ResonanceActivity, "Could not open browser", Toast.LENGTH_SHORT).show()
                }
            }
        )
        sideBarContent.addView(itemRepo)

        // --- Side Bar Footer ---
        val footerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, NeobrutalistTheme.dpToPx(this@ResonanceActivity, 28f), 0, 0)
        }

        val footerBuild = TextView(this).apply {
            text = "Resonance v$verName • Build $verCode"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(NeobrutalistTheme.COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, NeobrutalistTheme.dpToPx(this@ResonanceActivity, 3f))
        }
        footerLayout.addView(footerBuild)

        val footerEyebrow = TextView(this).apply {
            text = "SAME SOUND. SAME SECOND."
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setTextColor(NeobrutalistTheme.COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, NeobrutalistTheme.dpToPx(this@ResonanceActivity, 3f))
        }
        footerLayout.addView(footerEyebrow)

        val footerLove = TextView(this).apply {
            val ssb = SpannableStringBuilder()
            val span1 = SpannableString("Together ")
            span1.setSpan(StyleSpan(Typeface.BOLD), 0, span1.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.append(span1)

            val span2 = SpannableString("forever. ")
            span2.setSpan(StyleSpan(Typeface.ITALIC), 0, span2.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.append(span2)

            val heartSpan = SpannableString("♥")
            heartSpan.setSpan(ForegroundColorSpan(Color.parseColor("#E85D8A")), 0, heartSpan.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            heartSpan.setSpan(StyleSpan(Typeface.BOLD), 0, heartSpan.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.append(heartSpan)

            text = ssb
            textSize = 12f
            typeface = Typeface.SERIF
            setTextColor(Color.parseColor("#4A4A4A"))
            gravity = Gravity.CENTER
        }
        footerLayout.addView(footerLove)

        sideBarContent.addView(footerLayout)

        root.addView(sideBarContainer)
    }

    private fun buildSideBarMenuItem(
        iconText: String,
        iconBgColor: Int,
        title: String,
        subtitle: String,
        trailingText: String = "›",
        onClick: () -> Unit,
        onLongClick: (() -> Boolean)? = null
    ): LinearLayout {
        val itemLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = NeobrutalistTheme.card(
                this@ResonanceActivity,
                fillColor = Color.WHITE,
                cornerRadiusDp = 10f,
                shadowOffsetDp = 2f,
                strokeWidthDp = 1.5f
            )
            setPadding(
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 12f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 11f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 14f),
                NeobrutalistTheme.dpToPx(this@ResonanceActivity, 11f)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 10f)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            if (onLongClick != null) {
                setOnLongClickListener { onLongClick() }
            }
        }

        val iconBadge = TextView(this).apply {
            text = iconText
            textSize = 15f
            gravity = Gravity.CENTER
            val badgeSize = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 34f)
            layoutParams = LinearLayout.LayoutParams(badgeSize, badgeSize).apply {
                marginEnd = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 12f)
            }
            background = GradientDrawable().apply {
                setColor(iconBgColor)
                setStroke(NeobrutalistTheme.dpToPx(this@ResonanceActivity, 1.2f), NeobrutalistTheme.COLOR_BORDER)
                cornerRadius = NeobrutalistTheme.dpToPx(this@ResonanceActivity, 7f).toFloat()
            }
        }
        itemLayout.addView(iconBadge)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        itemLayout.addView(textCol)

        val titleView = TextView(this).apply {
            this.text = title
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
        }
        textCol.addView(titleView)

        val subView = TextView(this).apply {
            this.text = subtitle
            textSize = 11f
            setTextColor(NeobrutalistTheme.COLOR_MUTED)
            setPadding(0, NeobrutalistTheme.dpToPx(this@ResonanceActivity, 1f), 0, 0)
        }
        textCol.addView(subView)

        val trailingView = TextView(this).apply {
            this.text = trailingText
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(NeobrutalistTheme.COLOR_MUTED)
            setPadding(NeobrutalistTheme.dpToPx(this@ResonanceActivity, 6f), 0, 0, 0)
        }
        itemLayout.addView(trailingView)

        return itemLayout
    }

    private fun checkAppUpdatesOnStartup() {
        AppUpdateManager.checkForUpdates(this, isManual = false) { result ->
            if (result is UpdateResult.UpdateAvailable) {
                UpdateDialogHelper.showUpdateDialog(this, result.release, result.currentVersionName)
            }
        }
    }

    private fun checkAppUpdatesManual() {
        Toast.makeText(this, "Checking GitHub for updates...", Toast.LENGTH_SHORT).show()
        AppUpdateManager.checkForUpdates(this, isManual = true) { result ->
            when (result) {
                is UpdateResult.UpdateAvailable -> {
                    UpdateDialogHelper.showUpdateDialog(this, result.release, result.currentVersionName)
                }
                is UpdateResult.UpToDate -> {
                    Toast.makeText(
                        this,
                        "You're using the latest version (v${result.currentVersionName}).",
                        Toast.LENGTH_LONG
                    ).show()
                }
                is UpdateResult.CheckFailed -> {
                    val msg = when {
                        result.isOffline -> "Cannot check for updates: No internet connection."
                        result.isRateLimit -> "GitHub API rate limit reached. Please try again later."
                        else -> "Update check failed: ${result.errorMessage}"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        if (isCaptureBound) {
            try { unbindService(captureConnection) } catch (_: Exception) {}
            isCaptureBound = false
        }
        if (isPlaybackBound) {
            try { unbindService(playbackConnection) } catch (_: Exception) {}
            isPlaybackBound = false
        }
        discoveryManager.stop()
        super.onDestroy()
    }
}
