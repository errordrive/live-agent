package com.nayem.liveagent.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.nayem.liveagent.MainActivity

/**
 * Floating bubble shown over other apps while a call or agent task is running.
 *
 * Optional feature: if the "Display over other apps" permission is not granted
 * this service never adds a window and the app works exactly as before.
 * The UI worker (LiveScreen / TaskEngine) calls start()/updateState()/stop();
 * this service only owns the overlay window itself.
 *
 * Plain Android Views only — no Compose, no video, no ads.
 */
class FloatingBubbleService : Service() {

    companion object {
        private const val TAG = "FloatingBubble"
        private const val EXTRA_STATE = "initial_state"

        /** Latest agent state text shown in the bubble ("Listening", "Speaking", ...). */
        @Volatile var currentState: String = "Listening"
            private set

        /** Wall-clock start of the current call/task, for the elapsed ticker. */
        @Volatile var startedAtMs: Long = 0L
            private set

        @Volatile private var instance: FloatingBubbleService? = null

        private val mainHandler = Handler(Looper.getMainLooper())

        /**
         * Show the bubble. Does nothing when the overlay permission is not
         * granted or when the user disabled the overlay in settings.
         */
        fun start(context: Context, initialState: String) {
            if (!OverlayState.isOverlayAllowed(context)) {
                Log.i(TAG, "start: overlay permission not granted, skipping bubble")
                return
            }
            if (!OverlayState.overlayEnabled) {
                Log.i(TAG, "start: overlay disabled by user, skipping bubble")
                return
            }
            currentState = initialState
            if (!isShowing()) startedAtMs = 0L
            if (startedAtMs == 0L) startedAtMs = System.currentTimeMillis()
            try {
                val intent = Intent(context, FloatingBubbleService::class.java).apply {
                    putExtra(EXTRA_STATE, initialState)
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "start: failed to start service", e)
            }
        }

        /** Hide the bubble and stop the service. Safe to call when not showing. */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, FloatingBubbleService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "stop: failed to stop service", e)
            }
        }

        /**
         * Update the state text shown in the bubble without restarting the
         * service. Also re-checks the overlay permission: if the user revoked
         * it while the bubble was showing, the bubble is removed.
         * Safe to call from any thread.
         */
        fun updateState(context: Context, state: String) {
            currentState = state
            if (startedAtMs == 0L) startedAtMs = System.currentTimeMillis()
            mainHandler.post {
                val svc = instance ?: return@post
                try {
                    if (!Settings.canDrawOverlays(context)) {
                        Log.i(TAG, "updateState: overlay permission revoked, removing bubble")
                        svc.removeBubble()
                        svc.stopSelf()
                        return@post
                    }
                    svc.refreshViews()
                } catch (e: Exception) {
                    Log.w(TAG, "updateState: failed to refresh bubble", e)
                }
            }
        }

        /** True while the bubble window is currently added to WindowManager. */
        fun isShowing(): Boolean = instance?.bubbleAdded == true
    }

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var bubbleView: TextView? = null
    private var panelView: LinearLayout? = null
    private var stateText: TextView? = null
    private var elapsedText: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var bubbleAdded = false
    private var expanded = true

    private val ticker = object : Runnable {
        override fun run() {
            try {
                updateElapsed()
            } catch (e: Exception) {
                Log.w(TAG, "ticker failed", e)
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = intent?.getStringExtra(EXTRA_STATE) ?: currentState
        currentState = state
        if (startedAtMs == 0L) startedAtMs = System.currentTimeMillis()
        try {
            if (!Settings.canDrawOverlays(this)) {
                Log.i(TAG, "onStartCommand: overlay permission not granted")
                stopSelf()
                return START_NOT_STICKY
            }
            if (!bubbleAdded) {
                addBubble()
            }
            refreshViews()
            mainHandler.removeCallbacks(ticker)
            mainHandler.post(ticker)
        } catch (e: Exception) {
            Log.w(TAG, "onStartCommand: bubble setup failed", e)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            mainHandler.removeCallbacks(ticker)
        } catch (e: Exception) {
            Log.w(TAG, "onDestroy: ticker cleanup failed", e)
        }
        removeBubble()
        instance = null
        startedAtMs = 0L
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- Window construction (plain Views built in code) ----

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun addBubble() {
        val wm = try {
            getSystemService(Context.WINDOW_SERVICE) as WindowManager
        } catch (e: Exception) {
            Log.w(TAG, "addBubble: no WindowManager", e)
            return
        }
        windowManager = wm

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Round bubble (64dp) — drag handle + collapse/expand toggle.
        val bubble = TextView(this).apply {
            text = "🌙"
            textSize = 26f
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF1F2937.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }
        val bubbleSize = dp(64)
        bubble.layoutParams = LinearLayout.LayoutParams(bubbleSize, bubbleSize)

        // Expandable panel (~260dp wide) with state, elapsed time and Stop.
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF01F2937.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        panel.layoutParams = LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.WRAP_CONTENT)

        val state = TextView(this).apply {
            text = currentState
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
        }
        val elapsed = TextView(this).apply {
            text = "00:00"
            textSize = 12f
            setTextColor(0xFF9CA3AF.toInt())
        }
        val chevron = TextView(this).apply {
            text = "⌃ collapse"
            textSize = 12f
            setTextColor(0xFF9CA3AF.toInt())
            setPadding(0, dp(6), 0, 0)
        }
        val stopButton = Button(this).apply {
            text = "Stop"
            textSize = 13f
        }
        panel.addView(state)
        panel.addView(elapsed)
        panel.addView(chevron)
        panel.addView(stopButton)

        root.addView(bubble)
        root.addView(panel)

        // Tap on the bubble toggles collapse/expand; tap on the panel body
        // returns to the app; chevron also toggles; Stop hides the bubble.
        val tapSlop = dp(8)
        var downX = 0f
        var downY = 0f
        var moved = false
        root.setOnTouchListener { _, event ->
            try {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (kotlin.math.abs(dx) > tapSlop || kotlin.math.abs(dy) > tapSlop) {
                            moved = true
                        }
                        if (moved) {
                            val p = params
                            if (p != null) {
                                p.x += dx.toInt()
                                p.y += dy.toInt()
                                downX = event.rawX
                                downY = event.rawY
                                try {
                                    windowManager?.updateViewLayout(root, p)
                                } catch (e: Exception) {
                                    Log.w(TAG, "drag: updateViewLayout failed", e)
                                }
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            val y = event.y
                            val bubbleBottom = bubble.bottom.toFloat()
                            if (y <= bubbleBottom) {
                                toggleExpanded()
                            } else {
                                returnToApp()
                            }
                        }
                        true
                    }
                    else -> false
                }
            } catch (e: Exception) {
                Log.w(TAG, "touch handling failed", e)
                false
            }
        }
        // The touch listener above handles taps; keep the Stop button clickable
        // on top of it.
        stopButton.setOnClickListener {
            try {
                stopSelf()
            } catch (e: Exception) {
                Log.w(TAG, "Stop button failed", e)
            }
        }
        chevron.setOnClickListener { toggleExpanded() }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }
        params = layoutParams
        rootView = root
        bubbleView = bubble
        panelView = panel
        stateText = state
        elapsedText = elapsed

        try {
            wm.addView(root, layoutParams)
            bubbleAdded = true
        } catch (e: Exception) {
            Log.w(TAG, "addBubble: WindowManager.addView failed", e)
            bubbleAdded = false
        }
    }

    private fun removeBubble() {
        try {
            val wm = windowManager
            val view = rootView
            if (wm != null && view != null && bubbleAdded) {
                wm.removeView(view)
            }
        } catch (e: Exception) {
            // Removing a view that was never added (or already removed) must
            // never crash the service.
            Log.w(TAG, "removeBubble failed", e)
        } finally {
            bubbleAdded = false
            rootView = null
            bubbleView = null
            panelView = null
            stateText = null
            elapsedText = null
        }
    }

    private fun refreshViews() {
        stateText?.text = currentState
        updateElapsed()
        panelView?.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    private fun updateElapsed() {
        val view = elapsedText ?: return
        val start = startedAtMs
        if (start == 0L) {
            view.text = "00:00"
            return
        }
        val seconds = ((System.currentTimeMillis() - start) / 1000L).coerceAtLeast(0L)
        val minutes = seconds / 60
        val secs = seconds % 60
        view.text = String.format("%02d:%02d", minutes, secs)
    }

    private fun toggleExpanded() {
        expanded = !expanded
        panelView?.visibility = if (expanded) View.VISIBLE else View.GONE
        try {
            val p = params
            val root = rootView
            if (p != null && root != null) {
                windowManager?.updateViewLayout(root, p)
            }
        } catch (e: Exception) {
            Log.w(TAG, "toggleExpanded: updateViewLayout failed", e)
        }
    }

    private fun returnToApp() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "returnToApp failed", e)
        }
    }
}
