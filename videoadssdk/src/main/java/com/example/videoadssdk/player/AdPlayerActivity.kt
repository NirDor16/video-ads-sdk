package com.example.videoadssdk.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.videoadssdk.R

class AdPlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ad_player)

        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)

        val playerView = findViewById<PlayerView>(R.id.playerView)
        val btnClose = findViewById<ImageButton>(R.id.btnClose)

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
            val mediaItem = MediaItem.fromUri(videoUrl)
            exo.setMediaItem(mediaItem)
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }

    companion object {
        private const val EXTRA_VIDEO_URL = "extra_video_url"

        fun createIntent(context: Context, videoUrl: String): Intent {
            return Intent(context, AdPlayerActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_URL, videoUrl)
            }
        }

    }
}
