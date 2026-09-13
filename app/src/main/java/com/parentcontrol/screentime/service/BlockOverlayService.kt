package com.parentcontrol.screentime.service

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.CountDownTimer
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class BlockOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var countDownTimer: CountDownTimer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_BLOCK
        val graceSeconds = intent?.getIntExtra(EXTRA_GRACE_SECONDS, 120) ?: 120
        val appLabel = intent?.getStringExtra(EXTRA_APP_LABEL) ?: "이 앱"

        when (mode) {
            MODE_GRACE -> showGraceOverlay(appLabel, graceSeconds)
            MODE_BLOCK -> showBlockOverlay(appLabel)
            DISMISS -> removeOverlay()
        }
        return START_NOT_STICKY
    }

    private fun showGraceOverlay(appLabel: String, graceSeconds: Int) {
        removeOverlay()
        val messageView = buildMessageView(
            title = "곧 종료돼요",
            body = "$appLabel 사용 시간이 끝나가요.\n남은 시간 안에 하던 것을 정리해 주세요."
        )
        addOverlay(messageView, touchable = false)

        countDownTimer = object : CountDownTimer(graceSeconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = (millisUntilFinished / 1000).toInt()
                messageView.findViewWithTag<TextView>("countdown")?.text = "$secondsLeft 초 남음"
            }

            override fun onFinish() {
                showBlockOverlay(appLabel)
            }
        }.start()
    }

    private fun showBlockOverlay(appLabel: String) {
        countDownTimer?.cancel()
        removeOverlay()
        val messageView = buildMessageView(
            title = "오늘 사용 시간이 끝났어요",
            body = "$appLabel 의 오늘 사용 가능 시간을 모두 사용했어요.\n내일 다시 사용할 수 있어요."
        )
        addOverlay(messageView, touchable = true)
    }

    private fun buildMessageView(title: String, body: String): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E6000000"))
            setPadding(64, 64, 64, 64)
        }
        val titleView = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
        }
        val bodyView = TextView(this).apply {
            text = body
            setTextColor(Color.LTGRAY)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }
        val countdownView = TextView(this).apply {
            tag = "countdown"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }
        container.addView(titleView)
        container.addView(bodyView)
        container.addView(countdownView)
        return container
    }

    private fun addOverlay(view: LinearLayout, touchable: Boolean) {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val flags = if (touchable) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            if (touchable) WindowManager.LayoutParams.MATCH_PARENT
            else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = if (touchable) Gravity.CENTER else Gravity.TOP

        overlayView = view
        windowManager?.addView(view, params)
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        overlayView = null
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        removeOverlay()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_GRACE_SECONDS = "grace_seconds"
        const val EXTRA_APP_LABEL = "app_label"
        const val MODE_GRACE = "grace"
        const val MODE_BLOCK = "block"
        const val DISMISS = "dismiss"
    }
}
