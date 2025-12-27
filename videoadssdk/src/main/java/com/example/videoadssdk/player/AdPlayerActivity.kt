package com.example.videoadssdk.player

import android.app.Activity
import android.content.Context
import android.content.Intent
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
        val xDelaySeconds = intent.getIntExtra(EXTRA_X_DELAY_SECONDS, 5)

        val playerView = findViewById<PlayerView>(R.id.playerView)
        val btnClose = findViewById<ImageButton>(R.id.btnClose)

        // X hidden first
        btnClose.visibility = View.INVISIBLE
        btnClose.isEnabled = false

        // show X after delay
        uiJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            delay(xDelaySeconds.coerceAtLeast(0) * 1000L)
            btnClose.visibility = View.VISIBLE
            btnClose.isEnabled = true
        }

        btnClose.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        if (videoUrl.isNullOrBlank()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(videoUrl))
            exo.prepare()
            exo.playWhenReady = true
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
        private const val EXTRA_X_DELAY_SECONDS = "extra_x_delay_seconds"

        fun createIntent(context: Context, videoUrl: String, xDelaySeconds: Int): Intent {
            return Intent(context, AdPlayerActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_URL, videoUrl)
                putExtra(EXTRA_X_DELAY_SECONDS, xDelaySeconds)
            }
        }
    }
}
