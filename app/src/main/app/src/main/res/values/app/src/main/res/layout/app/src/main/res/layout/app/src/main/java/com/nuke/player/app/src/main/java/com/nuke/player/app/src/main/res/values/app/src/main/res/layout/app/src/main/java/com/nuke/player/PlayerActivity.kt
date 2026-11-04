package com.nuke.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

class PlayerActivity : AppCompatActivity() {

    private lateinit var lib: LibVLC
    private lateinit var mp: MediaPlayer
    private lateinit var surface: SurfaceView
    private lateinit var controls: View
    private lateinit var toastTv: TextView
    private lateinit var seek: SeekBar
    private lateinit var curTv: TextView
    private lateinit var durTv: TextView
    private lateinit var playBtn: TextView
    private lateinit var badge: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var pfd: ParcelFileDescriptor? = null
    private var bgOn = false
    private var userSeeking = false
    private val speeds = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f)
    private var speedIdx = 0
    private val fits = listOf(
        MediaPlayer.ScaleType.SURFACE_BEST_FIT to "Fit",
        MediaPlayer.ScaleType.SURFACE_FILL to "Stretch",
        MediaPlayer.ScaleType.SURFACE_FIT_SCREEN to "Crop"
    )
    private var fitIdx = 0

    private val hideRunnable = Runnable { controls.visibility = View.GONE }
    private val ticker = object : Runnable {
        override fun run() {
            val len = mp.length
            if (len > 0 && !userSeeking) {
                seek.progress = (mp.time * 1000 / len).toInt()
                curTv.text = Videos.fmtTime(mp.time)
                durTv.text = Videos.fmtTime(len)
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(R.layout.activity_player)

        surface = findViewById(R.id.surface)
        controls = findViewById(R.id.controls)
        toastTv = findViewById(R.id.toast)
        seek = findViewById(R.id.seek)
        curTv = findViewById(R.id.cur)
        durTv = findViewById(R.id.dur)
        playBtn = findViewById(R.id.btnPlay)
        badge = findViewById(R.id.badge)

        lib = LibVLC(this, arrayListOf("--audio-time-stretch"))
        mp = MediaPlayer(lib)
        mp.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT

        mp.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> { playBtn.text = "⏸"; updateBadge() }
                MediaPlayer.Event.Paused -> { playBtn.text = "▶"; showControls() }
                MediaPlayer.Event.EndReached -> next()
                MediaPlayer.Event.EncounteredError -> toast("Can't play this file")
            }
        }

        surface.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            mp.vlcVout.setWindowSize(r - l, b - t)
        }

        // buttons
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        playBtn.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.btnRw).setOnClickListener { skip(-10_000) }
        findViewById<View>(R.id.btnFf).setOnClickListener { skip(10_000) }
        findViewById<View>(R.id.btnNext).setOnClickListener { next() }
        findViewById<View>(R.id.btnPip).setOnClickListener { enterPopup() }
        findViewById<View>(R.id.btnBg).setOnClickListener { toggleBackground() }
        findViewById<TextView>(R.id.btnSpeed).setOnClickListener {
            speedIdx = (speedIdx + 1) % speeds.size
            mp.rate = speeds[speedIdx]
            (it as TextView).text = "⚡\n${speeds[speedIdx]}×"
            toast("${speeds[speedIdx]}×")
        }
        findViewById<TextView>(R.id.btnFit).setOnClickListener {
            fitIdx = (fitIdx + 1) % fits.size
            mp.videoScale = fits[fitIdx].first
            (it as TextView).text = "⤢\n${fits[fitIdx].second}"
            toast(fits[fitIdx].second)
        }

        // fast seek bar
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser && mp.length > 0) {
                    val t = mp.length * p / 1000
                    mp.time = t
                    curTv.text = Videos.fmtTime(t)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(s: SeekBar) { userSeeking = false; showControls() }
        })

        // gestures: double-tap left/right = +/-10s, tap = show/hide controls
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val x = e.x / surface.width
                when {
                    x < 0.4f -> skip(-10_000)
                    x > 0.6f -> skip(10_000)
                    else -> togglePlay()
                }
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controls.visibility == View.VISIBLE) controls.visibility = View.GONE
                else showControls()
                return true
            }
        })
        findViewById<View>(R.id.root).setOnTouchListener { _, ev -> gd.onTouchEvent(ev); true }

        load(Queue.index)
        handler.post(ticker)
        showControls()
    }

    private fun load(i: Int) {
        if (Queue.items.isEmpty()) { finish(); return }
        Queue.index = i
        val v = Queue.items[i]
        findViewById<TextView>(R.id.name).text = v.name
        badge.visibility = View.GONE
        try { pfd?.close() } catch (_: Exception) {}
        pfd = contentResolver.openFileDescriptor(v.uri, "r")
        val media = Media(lib, pfd!!.fileDescriptor)
        media.setHWDecoderEnabled(true, false)   // hardware first, software fallback
        mp.media = media
        media.release()
        mp.play()
        mp.rate = speeds[speedIdx]
    }

    private fun updateBadge() {
        val t = mp.currentVideoTrack ?: return
        val m = maxOf(t.width, t.height)
        val h = minOf(t.width, t.height)
        badge.text = when {
            m >= 7680 -> "8K"
            m >= 3840 -> "4K"
            m >= 2560 -> "2K"
            h >= 1080 -> "1080p"
            h >= 720 -> "720p"
            else -> "${h}p"
        }
        badge.visibility = View.VISIBLE
    }

    private fun togglePlay() { if (mp.isPlaying) mp.pause() else mp.play() }

    private fun skip(ms: Long) {
        val len = mp.length
        if (len <= 0) return
        mp.time = (mp.time + ms).coerceIn(0, len)
        toast(if (ms > 0) "+10s" else "-10s")
    }

    private fun next() {
        if (Queue.index < Queue.items.size - 1) load(Queue.index + 1)
        else toast("Last video")
    }

    private fun enterPopup() {
        if (Build.VERSION.SDK_INT >= 26) {
            val p = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            enterPictureInPictureMode(p)
        } else toast("Popup needs Android 8+")
    }

    private fun toggleBackground() {
        bgOn = !bgOn
        findViewById<TextView>(R.id.btnBg).setTextColor(if (bgOn) 0xFFFF4D1A.toInt() else 0xFFFFFFFF.toInt())
        val i = Intent(this, PlaybackService::class.java)
        if (bgOn) ContextCompat.startForegroundService(this, i) else stopService(i)
        toast(if (bgOn) "Background play ON" else "Background play OFF")
    }

    private fun showControls() {
        controls.visibility = View.VISIBLE
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 3500)
    }

    private fun toast(msg: String) {
        toastTv.text = msg
        toastTv.visibility = View.VISIBLE
        handler.removeCallbacks(hideToast)
        handler.postDelayed(hideToast, 800)
    }
    private val hideToast = Runnable { toastTv.visibility = View.GONE }

    override fun onPictureInPictureModeChanged(inPip: Boolean, cfg: Configuration) {
        super.onPictureInPictureModeChanged(inPip, cfg)
        controls.visibility = if (inPip) View.GONE else View.VISIBLE
    }

    override fun onStart() {
        super.onStart()
        val vo = mp.vlcVout
        if (!vo.areViewsAttached()) {
            vo.setVideoView(surface)
            vo.attachViews()
            vo.setWindowSize(surface.width, surface.height)
        }
    }

    override fun onStop() {
        super.onStop()
        val inPip = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode
        if (!bgOn && !inPip) mp.pause()
        mp.vlcVout.detachViews()   // audio keeps playing if background is ON
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        stopService(Intent(this, PlaybackService::class.java))
        mp.stop()
        mp.vlcVout.detachViews()
        mp.release()
        lib.release()
        try { pfd?.close() } catch (_: Exception) {}
    }
}
