package com.alnoor.autobot

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/** Brief Siri-like floating orb. The existing chat UI remains untouched. */
class AssistantOverlayService : Service() {
    companion object {
        const val ACTION_SHOW = "com.alnoor.autobot.OVERLAY_SHOW"
        const val ACTION_HIDE = "com.alnoor.autobot.OVERLAY_HIDE"
        const val EXTRA_TEXT = "overlay_text"
        const val EXTRA_DURATION = "overlay_duration"
        fun show(ctx: Context, text: String = "Listening…", durationMs: Long = 6500L) {
            if (!Jarvis.overlayOn(ctx)) return
            if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(ctx)) return
            try {
                val i = Intent(ctx, AssistantOverlayService::class.java).apply {
                    action = ACTION_SHOW; putExtra(EXTRA_TEXT, text); putExtra(EXTRA_DURATION, durationMs)
                }
                ctx.startService(i)
            } catch (_: Throwable) { }
        }
        fun hide(ctx: Context) { try { ctx.startService(Intent(ctx, AssistantOverlayService::class.java).setAction(ACTION_HIDE)) } catch (_: Throwable) { } }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var label: TextView? = null
    private val hideTask = Runnable { removeOverlay(); stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) { removeOverlay(); stopSelf(); return START_NOT_STICKY }
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Listening…"
        val duration = intent?.getLongExtra(EXTRA_DURATION, 6500L) ?: 6500L
        handler.removeCallbacks(hideTask)
        if (root == null) createOverlay()
        label?.text = text
        root?.animate()?.cancel()
        root?.alpha = 0f
        root?.animate()?.alpha(1f)?.setDuration(180)?.start()
        handler.postDelayed(hideTask, duration.coerceIn(1500L, 20000L))
        return START_NOT_STICKY
    }

    private fun createOverlay() {
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) { stopSelf(); return }
        try {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val box = FrameLayout(this)
            val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(127, 76, 255), Color.rgb(42, 180, 255)))
            bg.shape = GradientDrawable.OVAL
            box.background = bg
            box.elevation = dp(10).toFloat()
            val t = TextView(this).apply {
                text = "◉"; textSize = 27f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                contentDescription = "${Jarvis.assistantName(this@AssistantOverlayService)} voice assistant. Tap to open chat."
            }
            box.addView(t, FrameLayout.LayoutParams(-1, -1))
            box.setOnClickListener {
                try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) } catch (_: Throwable) { }
                removeOverlay(); stopSelf()
            }
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(dp(62), dp(62), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; x = dp(14); y = 0 }
            wm?.addView(box, params)
            root = box; label = t
        } catch (_: Throwable) { root = null; label = null }
    }
    private fun removeOverlay() {
        handler.removeCallbacks(hideTask)
        try { root?.let { wm?.removeView(it) } } catch (_: Throwable) { }
        root = null; label = null
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onDestroy() { removeOverlay(); super.onDestroy() }
}
