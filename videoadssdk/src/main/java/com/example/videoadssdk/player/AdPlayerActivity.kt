package com.example.videoadssdk.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.videoadssdk.R
import kotlinx.coroutines.*

class AdPlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var uiJob: Job? = null

    // prevent spam clicks on the ad
    private var lastClickAtMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ad_player)

        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
        val targetUrl = intent.getStringExtra(EXTRA_TARGET_URL)
        val xDelaySeconds = intent.getIntExtra(EXTRA_X_DELAY_SECONDS, 5)

        val playerView = findViewById<PlayerView>(R.id.playerView)
        val btnClose = findViewById<ImageButton>(R.id.btnClose)

        // hide controls (clean ad screen)
        playerView.useController = false

        // ensure X is above the player
        btnClose.bringToFront()

        // X hidden first
        btnClose.visibility = View.INVISIBLE
        btnClose.isEnabled = false

        // show X after delay
        uiJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            delay(xDelaySeconds.coerceAtLeast(5) * 1000L)
            btnClose.visibility = View.VISIBLE
            btnClose.isEnabled = true
        }

        // X closes the ad
        btnClose.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        // ❌ IMPORTANT: do NOT add onTouchListener that returns true (it breaks the click)

        if (videoUrl.isNullOrBlank()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        // Clicking the ad opens target url (if exists) AND closes the ad screen
        playerView.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastClickAtMs < 600) return@setOnClickListener // anti double-tap
            lastClickAtMs = now

            val url = targetUrl?.trim().orEmpty()
            if (url.isNotEmpty()) {
                openUrlAndClose(url)
            }
        }

        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(videoUrl))
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    private fun openUrlAndClose(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            // ignore
        } finally {
            // close the ad immediately so returning goes back to the app screen
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        uiJob?.cancel()
        uiJob = null
        player?.release()
        player = null
    }

    companion object {
        private const val EXTRA_VIDEO_URL = "extra_video_url"
        private const val EXTRA_TARGET_URL = "extra_target_url"
        private const val EXTRA_X_DELAY_SECONDS = "extra_x_delay_seconds"

        fun createIntent(
            context: Context,
            videoUrl: String,
            targetUrl: String?,
            xDelaySeconds: Int
        ): Intent {
            return Intent(context, AdPlayerActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_URL, videoUrl)
                putExtra(EXTRA_TARGET_URL, targetUrl)
                putExtra(EXTRA_X_DELAY_SECONDS, xDelaySeconds)
            }
        }
    }
}
