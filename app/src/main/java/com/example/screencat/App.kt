package com.example.screencat

import android.animation.ValueAnimator
import android.app.*
import android.app.Activity
import android.content.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.PixelFormat
import android.net.Uri
import android.os.*
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.*
import kotlin.math.PI
import kotlin.math.sin

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prefs = getSharedPreferences("cat", MODE_PRIVATE)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val limit = EditText(this).apply {
            hint = "Daily limit (minutes)"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("limit_min", 120).toString())
        }
        val start = Button(this).apply { text = "Start the cat 🐱" }
        val stop = Button(this).apply { text = "Stop" }
        box.addView(TextView(this).apply { text = "Screen Cat"; textSize = 26f })
        box.addView(limit); box.addView(start); box.addView(stop)
        setContentView(box)

        start.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {   // needed to float the cat over other apps
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return@setOnClickListener
            }
            prefs.edit().putInt("limit_min", limit.text.toString().toIntOrNull() ?: 120).apply()
            startForegroundService(Intent(this, CatService::class.java))
            Toast.makeText(this, "Cat is on duty!", Toast.LENGTH_SHORT).show()
        }
        stop.setOnClickListener { stopService(Intent(this, CatService::class.java)) }
        if (android.os.Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }
}

class CatService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var wm: WindowManager
    private var catView: View? = null
    private var walker: ValueAnimator? = null
    private var screenOnSince = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_ON) screenOnSince = System.currentTimeMillis()
            else if (i.action == Intent.ACTION_SCREEN_OFF) { bank(); screenOnSince = 0; hideCat() }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            bank(); if (isInteractive()) screenOnSince = System.currentTimeMillis()
            val limitMs = prefs.getInt("limit_min", 120) * 60_000L
            val snoozed = System.currentTimeMillis() < prefs.getLong("snooze_until", 0)
            if (isInteractive() && !snoozed && catView == null && prefs.getLong("used_ms", 0) >= limitMs) showCat()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate() {
        prefs = getSharedPreferences("cat", MODE_PRIVATE)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val ch = NotificationChannel("cat", "Screen Cat", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        startForeground(1, Notification.Builder(this, "cat")
            .setContentTitle("Screen Cat is watching 🐱").setSmallIcon(android.R.drawable.ic_menu_view).build())
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
        if (isInteractive()) screenOnSince = System.currentTimeMillis()
        handler.post(tick)
    }

    private fun isInteractive() = getSystemService(PowerManager::class.java).isInteractive

    /** Adds elapsed screen-on time to today's total; resets at a new day. */
    private fun bank() {
        val today = java.time.LocalDate.now().toString()
        if (prefs.getString("day", "") != today)
            prefs.edit().putString("day", today).putLong("used_ms", 0).apply()
        if (screenOnSince > 0) {
            val add = System.currentTimeMillis() - screenOnSince
            prefs.edit().putLong("used_ms", prefs.getLong("used_ms", 0) + add).apply()
            screenOnSince = System.currentTimeMillis()
        }
    }

    private fun showCat() {
        val mins = prefs.getLong("used_ms", 0) / 60_000
        val v = CatView(this, "Meow! $mins min of screen time 😾")
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.START; y = 120 }
        wm.addView(v, lp); catView = v
        val maxX = resources.displayMetrics.widthPixels - (240 * resources.displayMetrics.density).toInt()
        walker = ValueAnimator.ofInt(0, maxX).apply {          // cat strolls back and forth
            duration = 6000; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { lp.x = it.animatedValue as Int; catView?.let { c -> wm.updateViewLayout(c, lp) } }
            start()
        }
        v.setOnClickListener {                                   // tap = snooze 10 min
            prefs.edit().putLong("snooze_until", System.currentTimeMillis() + 10 * 60_000L).apply()
            hideCat()
        }
    }

    private fun hideCat() {
        walker?.cancel(); catView?.let { runCatching { wm.removeView(it) } }; catView = null
    }

    override fun onDestroy() { handler.removeCallbacks(tick); hideCat(); unregisterReceiver(screenReceiver) }
    override fun onBind(i: Intent?): IBinder? = null
}

/** A cat drawn with Canvas: bobbing, wagging tail, blinking, speech bubble. No image assets needed. */
class CatView(ctx: Context, private val message: String) : View(ctx) {
    private var t = 0f
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val anim = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 1600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((240 * d).toInt(), (200 * d).toInt())

    override fun onDraw(c: Canvas) {
        c.scale(d, d)
        val bob = sin(t) * 4f
        val fur = Color.parseColor("#F4A259")

        // speech bubble
        p.style = Paint.Style.FILL; p.color = Color.WHITE
        c.drawRoundRect(10f, 4f, 230f, 52f, 14f, 14f, p)
        c.drawPath(Path().apply { moveTo(105f, 52f); lineTo(120f, 66f); lineTo(135f, 52f); close() }, p)
        p.color = Color.parseColor("#333333"); p.textSize = 14f; p.textAlign = Paint.Align.CENTER
        c.drawText(message, 120f, 33f, p)

        // tail
        val w = sin(t * 2)
        p.style = Paint.Style.STROKE; p.strokeWidth = 11f; p.strokeCap = Paint.Cap.ROUND; p.color = fur
        c.drawPath(Path().apply {
            moveTo(168f, 170f); quadTo(205f + w * 12, 150f, 205f + w * 20, 112f + bob)
        }, p)

        // body + head
        p.style = Paint.Style.FILL
        c.drawOval(70f, 118f + bob, 170f, 192f, p)
        c.drawCircle(120f, 108f + bob, 36f, p)
        // ears
        for (s in listOf(-1, 1)) {
            c.drawPath(Path().apply {
                moveTo(120f + s * 32f, 86f + bob); lineTo(120f + s * 28f, 52f + bob); lineTo(120f + s * 6f, 76f + bob); close()
            }, p)
        }
        // eyes (blink)
        p.color = Color.BLACK
        if (sin(t * 3) > 0.96f) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f
            c.drawLine(100f, 106f + bob, 112f, 106f + bob, p); c.drawLine(128f, 106f + bob, 140f, 106f + bob, p)
            p.style = Paint.Style.FILL
        } else { c.drawCircle(106f, 106f + bob, 4.5f, p); c.drawCircle(134f, 106f + bob, 4.5f, p) }
        // nose + whiskers
        p.color = Color.parseColor("#E8808A"); c.drawCircle(120f, 117f + bob, 3.5f, p)
        p.color = Color.parseColor("#555555"); p.strokeWidth = 1.2f; p.style = Paint.Style.STROKE
        for (s in listOf(-1, 1)) { c.drawLine(120f + s * 14f, 118f + bob, 120f + s * 40f, 112f + bob, p); c.drawLine(120f + s * 14f, 122f + bob, 120f + s * 40f, 126f + bob, p) }
    }
}
