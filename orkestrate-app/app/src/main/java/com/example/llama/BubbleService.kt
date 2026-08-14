package com.example.llama

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Draws a floating bubble over other apps. Tapping it opens [ChatActivity].
 * This is the "always-here" circle in the product vision.
 */
class BubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialX = 0f
    private var initialY = 0f
    private var isDragging = false
    private var moved = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_HIDE -> { hideBubble(); return START_STICKY }
            ACTION_SHOW -> { showBubble(); return START_STICKY }
            else -> {
                if (bubbleView == null) addBubble()
                return START_STICKY
            }
        }
    }

    private fun showBubble() {
        if (bubbleView == null) addBubble()
        else bubbleView?.let { windowManager.updateViewLayout(it, params) }
    }

    private fun hideBubble() {
        bubbleView?.let {
            windowManager.removeView(it)
            bubbleView = null
            params = null
        }
    }

    private fun addBubble() {
        if (!SettingsCompat.canDrawOverlays(this)) return

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.bubble_overlay, null)
        bubbleView = view

        val size = 56.dp
        params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (resources.displayMetrics.heightPixels - size * 2)
        }

        // draggable
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params!!.x.toFloat()
                    initialY = params!!.y.toFloat()
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params!!.x = (initialX + dx).toInt()
                    params!!.y = (initialY + dy).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        openChat()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(view, params)
    }

    private fun openChat() {
        // The PWA is the single UI surface now — the bubble opens it.
        val i = Intent(this, PwaActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(i)
    }

    private fun buildNotification(): Notification {
        val channelId = CHANNEL_ID
        return Notification.Builder(this, channelId)
            .setContentTitle("zygote")
            .setContentText("on-device agent is running")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "zygote agent",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let { windowManager.removeView(it) }
        bubbleView = null
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "BubbleService"
        private const val CHANNEL_ID = "zygote_bubble"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.example.llama.STOP_BUBBLE"
        const val ACTION_HIDE = "com.example.llama.HIDE_BUBBLE"
        const val ACTION_SHOW = "com.example.llama.SHOW_BUBBLE"

        fun start(context: Context) {
            val i = Intent(context, BubbleService::class.java)
            context.startForegroundService(i)
        }

        fun setVisibility(context: Context, visible: Boolean) {
            val i = Intent(context, BubbleService::class.java).apply {
                action = if (visible) ACTION_SHOW else ACTION_HIDE
            }
            context.startService(i)
        }
    }
}
