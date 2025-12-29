package com.example.videoadssdk.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ad_player)

        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
        val targetUrl = intent.getStringExtra(EXTRA_TARGET_URL)
        val xDelaySeconds = intent.getIntExtra(EXTRA_X_DELAY_SECONDS, 5)

        val playerView = findViewById<PlayerView>(R.id.playerView)
        val btnClose = findViewById<ImageButton>(R.id.btnClose)

        playerView.useController = false


        // X hidden first
        btnClose.visibility = View.INVISIBLE
        btnClose.isEnabled = false

        // show X after delay (TARGET_URL step: leave as-is for now)
        uiJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            delay(xDelaySeconds.coerceIn(5, 30) * 1000L)

            btnClose.visibility = View.VISIBLE
            btnClose.isEnabled = true
        }

        // X closes the ad (and MUST NOT open target url)
        btnClose.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        // extra safety: don't let the click "fall through"
        btnClose.setOnTouchListener { _, _ -> true }

        if (videoUrl.isNullOrBlank()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        // Clicking the ad opens target url (if exists)
        playerView.setOnClickListener {
            val url = targetUrl?.trim().orEmpty()
            if (url.isNotEmpty()) {
                openUrl(url)
            }
        }

        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(videoUrl))
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            // ignore
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
