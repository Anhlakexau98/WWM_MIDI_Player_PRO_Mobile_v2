package com.wwm.midiplayer.pro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*

class MidiOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingControlView: View? = null
    private val keyViews = mutableListOf<View>()
    private var isShowingKeys = false
    private var isPlaying = false
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var playbackJob: Job? = null

    private var btnPlayPauseRef: Button? = null
    private var btnShowHideKeysRef: Button? = null
    private var btnModeSwitchRef: Button? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        showFloatingControlBar()
    }

    private fun startForegroundNotification() {
        val channelId = "wwm_midi_xiaomi15_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "WWM MIDI Auto-Tap Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("WWM MIDI PRO đang chạy")
                .setContentText("Bấm Phát trên thanh nổi để tự động ấn phím đàn trong game")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        } else {
            Notification()
        }
        startForeground(1001, notification)
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun showFloatingControlBar() {
        if (floatingControlView != null) return

        val barParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 40
        }

        val barBg = GradientDrawable().apply {
            setColor(Color.parseColor("#E6141418"))
            cornerRadius = 24f
            setStroke(2, Color.parseColor("#10B981"))
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = barBg
            setPadding(14, 8, 14, 8)
            gravity = Gravity.CENTER_VERTICAL
        }

        val dragHandle = TextView(this).apply {
            text = " ✥ "
            textSize = 16f
            setTextColor(Color.parseColor("#10B981"))
            setPadding(10, 6, 12, 6)
        }

        dragHandle.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = barParams.x
                        initialY = barParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        barParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        barParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(panel, barParams)
                        return true
                    }
                }
                return false
            }
        })

        val btnPlayPause = Button(this).apply {
            text = "▶ Phát"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#10B981"))
            setPadding(20, 6, 20, 6)
            setOnClickListener { toggleAutoTapPlayback() }
        }
        btnPlayPauseRef = btnPlayPause

        val btnModeSwitch = Button(this).apply {
            text = modeLabel(TouchPointManager.currentPresetMode)
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3B82F6"))
            setPadding(18, 6, 18, 6)
            setOnClickListener {
                TouchPointManager.currentPresetMode = (TouchPointManager.currentPresetMode + 1) % 3
                TouchPointManager.rebuildActiveSchedule()
                text = modeLabel(TouchPointManager.currentPresetMode)
                if (isShowingKeys) {
                    hideKeyNodes()
                    showKeyNodes()
                }
            }
        }
        btnModeSwitchRef = btnModeSwitch

        val btnShowHideKeys = Button(this).apply {
            text = "🎯 Hiện phím"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#27272A"))
            setPadding(18, 6, 18, 6)
            setOnClickListener {
                if (isShowingKeys) {
                    hideKeyNodes()
                    Toast.makeText(this@MidiOverlayService, "Đã lưu & ẩn phím!", Toast.LENGTH_SHORT).show()
                } else {
                    if (isPlaying) stopAutoTapPlayback()
                    showKeyNodes()
                }
            }
        }
        btnShowHideKeysRef = btnShowHideKeys

        val btnClose = Button(this).apply {
            text = "❌"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#EF4444"))
            setPadding(14, 6, 14, 6)
            setOnClickListener {
                stopAutoTapPlayback()
                hideKeyNodes()
                stopSelf()
            }
        }

        panel.addView(dragHandle)
        panel.addView(btnPlayPause)
        panel.addView(btnModeSwitch)
        panel.addView(btnShowHideKeys)
        panel.addView(btnClose)

        floatingControlView = panel
        windowManager.addView(panel, barParams)
    }

    private fun modeLabel(mode: Int): String = when (mode) {
        1 -> "36P ĐạiSư #"
        2 -> "36P Lưới"
        else -> "21 Phím"
    }

    private fun toggleAutoTapPlayback() {
        if (isPlaying) stopAutoTapPlayback() else startAutoTapPlayback()
    }

    /**
     * Đồng hồ thời gian thực (SystemClock.elapsedRealtime) đảm bảo từng hợp âm và nốt nhạc
     * trong file MIDI được nhấn đúng nhịp 100%, và tự động dừng khi hết bài (hoặc lặp lại nếu bật Loop).
     */
    private fun startAutoTapPlayback() {
        val tapService = MidiAutoTapService.instance
        if (tapService == null) {
            Toast.makeText(
                this,
                "⚠️ Chưa bật Dịch vụ Trợ năng! Hãy bật WWM MIDI PRO trong Cài đặt Trợ năng.",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return
        }

        if (isShowingKeys) {
            hideKeyNodes()
        }

        // Luôn tính toán lại bản phối thông minh theo đúng chế độ 21 phím / 36 phím hiện tại
        TouchPointManager.rebuildActiveSchedule()
        val schedule = TouchPointManager.currentSongSchedule
        if (schedule.isEmpty()) {
            Toast.makeText(this, "Không có nốt nhạc hợp lệ trong bài!", Toast.LENGTH_SHORT).show()
            return
        }

        isPlaying = true
        btnPlayPauseRef?.apply {
            text = "⏹ Dừng"
            setBackgroundColor(Color.parseColor("#EF4444"))
        }

        val metrics = resources.displayMetrics
        val screenW = maxOf(metrics.widthPixels, metrics.heightPixels)
        val screenH = minOf(metrics.widthPixels, metrics.heightPixels)
        val mode = TouchPointManager.currentPresetMode
        val keyCoords = TouchPointManager.loadXiaomi15Layout(this, mode, screenW, screenH)

        Toast.makeText(
            this,
            "Đang gảy: ${TouchPointManager.currentSongTitle} (Dịch tông thông minh: ${TouchPointManager.lastAutoTransposeSemitones} bán cung)",
            Toast.LENGTH_SHORT
        ).show()

        playbackJob?.cancel()
        playbackJob = serviceScope.launch(Dispatchers.Default) {
            do {
                val startWallClock = SystemClock.elapsedRealtime()
                for (i in schedule.indices) {
                    if (!isActive || !isPlaying) break
                    val chord = schedule[i]
                    val speed = TouchPointManager.playbackSpeedMultiplier.coerceIn(0.1f, 3.0f)
                    val targetElapsed = (chord.timeMs / speed).toLong()
                    val nowElapsed = SystemClock.elapsedRealtime() - startWallClock
                    val waitMs = targetElapsed - nowElapsed
                    if (waitMs > 2L) {
                        delay(waitMs)
                    }
                    if (!isActive || !isPlaying) break

                    val points = chord.keyIndices.mapNotNull { kIdx ->
                        keyCoords.getOrNull(kIdx)?.let { Pair(it.x, it.y) }
                    }
                    if (points.isNotEmpty()) {
                        MidiAutoTapService.instance?.performChordTap120Hz(points, chord.durationMs)
                    }
                }
                delay(400L)
            } while (isActive && isPlaying && TouchPointManager.loopPlayback)

            withContext(Dispatchers.Main) {
                stopAutoTapPlayback()
            }
        }
    }

    private fun stopAutoTapPlayback() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null
        btnPlayPauseRef?.apply {
            text = "▶ Phát"
            setBackgroundColor(Color.parseColor("#10B981"))
        }
    }

    private fun showKeyNodes() {
        hideKeyNodes()
        isShowingKeys = true
        btnShowHideKeysRef?.apply {
            text = "💾 Lưu & Ẩn"
            setBackgroundColor(Color.parseColor("#10B981"))
        }

        val metrics = resources.displayMetrics
        val w = maxOf(metrics.widthPixels, metrics.heightPixels)
        val h = minOf(metrics.widthPixels, metrics.heightPixels)
        val mode = TouchPointManager.currentPresetMode
        val keys = TouchPointManager.loadXiaomi15Layout(this, mode, w, h)

        keys.forEach { keyCoord ->
            val sizePx = if (keyCoord.isSharp) 74 else 88
            val half = sizePx / 2

            val nodeParams = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (keyCoord.x - half).toInt()
                y = (keyCoord.y - half).toInt()
            }

            val circleBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    if (keyCoord.isSharp) Color.parseColor("#CC6366F1")
                    else Color.parseColor("#CC10B981")
                )
                setStroke(3, Color.WHITE)
            }

            val nodeView = TextView(this).apply {
                text = keyCoord.label
                textSize = if (keyCoord.isSharp) 9f else 11f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = circleBg

                setOnTouchListener(object : View.OnTouchListener {
                    private var startX = 0
                    private var startY = 0
                    private var touchX = 0f
                    private var touchY = 0f

                    override fun onTouch(v: View, event: MotionEvent): Boolean {
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                startX = nodeParams.x
                                startY = nodeParams.y
                                touchX = event.rawX
                                touchY = event.rawY
                                return true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                nodeParams.x = startX + (event.rawX - touchX).toInt()
                                nodeParams.y = startY + (event.rawY - touchY).toInt()
                                keyCoord.x = (nodeParams.x + half).toFloat()
                                keyCoord.y = (nodeParams.y + half).toFloat()
                                windowManager.updateViewLayout(v, nodeParams)
                                return true
                            }
                            MotionEvent.ACTION_UP -> {
                                TouchPointManager.saveXiaomi15Layout(this@MidiOverlayService, mode, keys)
                                return true
                            }
                        }
                        return false
                    }
                })
            }

            windowManager.addView(nodeView, nodeParams)
            keyViews.add(nodeView)
        }

        floatingControlView?.let { bar ->
            val lp = bar.layoutParams as WindowManager.LayoutParams
            windowManager.removeView(bar)
            windowManager.addView(bar, lp)
        }
    }

    private fun hideKeyNodes() {
        keyViews.forEach { v ->
            try { windowManager.removeView(v) } catch (_: Exception) {}
        }
        keyViews.clear()
        isShowingKeys = false
        btnShowHideKeysRef?.apply {
            text = "🎯 Hiện phím"
            setBackgroundColor(Color.parseColor("#27272A"))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoTapPlayback()
        hideKeyNodes()
        serviceScope.cancel()
        floatingControlView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            floatingControlView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
