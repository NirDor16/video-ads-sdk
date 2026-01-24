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

/**
 * Activity responsible for displaying a full-screen video advertisement.
 * Handles video playback, delayed close button (X), and ad click behavior.
 */
class AdPlayerActivity : AppCompatActivity() {

    // ExoPlayer instance for video playback
    private var player: ExoPlayer? = null

    // Coroutine job used to control delayed UI actions (showing the X button)
    private var uiJob: Job? = null

    // Timestamp of the last click to prevent rapid double clicks on the ad
    private var lastClickAtMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ad_player)

        // Retrieve data passed from the SDK via Intent extras
        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
        val targetUrl = intent.getStringExtra(EXTRA_TARGET_URL)
        val xDelaySeconds = intent.getIntExtra(EXTRA_X_DELAY_SECONDS, 5)

        // UI references
        val playerView = findViewById<PlayerView>(R.id.playerView)
        val btnClose = findViewById<ImageButton>(R.id.btnClose)

        // Disable default media controls for a clean ad experience
        playerView.useController = false

        // Ensure the close (X) button is rendered above the video
        btnClose.bringToFront()

        // Initially hide the close button and disable it
        btnClose.visibility = View.INVISIBLE
        btnClose.isEnabled = false

        // Show the close button only after a configurable delay
        uiJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            delay(xDelaySeconds.coerceAtLeast(5) * 1000L)
            btnClose.visibility = View.VISIBLE
            btnClose.isEnabled = true
        }

        // Close button simply closes the ad screen
        btnClose.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        // IMPORTANT: Do not attach an onTouchListener that returns true,
        // as it would consume the click and break the onClick behavior

        // Safety check: if no video URL was provided, close immediately
        if (videoUrl.isNullOrBlank()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        // Clicking anywhere on the video opens the target URL (if provided)
        // and closes the ad screen
        playerView.setOnClickListener {
            val now = SystemClock.elapsedRealtime()

            // Prevent rapid double-taps that could open the browser twice
            if (now - lastClickAtMs < 600) return@setOnClickListener
            lastClickAtMs = now

            val url = targetUrl?.trim().orEmpty()
            if (url.isNotEmpty()) {
                openUrlAndClose(url)
            }
        }

        // Initialize and start ExoPlayer playback
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(videoUrl))
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    /**
     * Opens the ad target URL in an external browser
     * and immediately closes the ad activity.
     */
    private fun openUrlAndClose(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            // Ignore failures (invalid URL, no browser, etc.)
        } finally {
            // Close the ad so returning from the browser goes back to the app
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    override fun onStop() {
        super.onStop()

        // Cancel delayed UI actions to avoid updating a destroyed screen
        uiJob?.cancel()
        uiJob = null

        // Release ExoPlayer resources to prevent leaks and background playback
        player?.release()
        player = null
    }

    companion object {
        // Intent extra keys
        private const val EXTRA_VIDEO_URL = "extra_video_url"
        private const val EXTRA_TARGET_URL = "extra_target_url"
        private const val EXTRA_X_DELAY_SECONDS = "extra_x_delay_seconds"

        /**
         * Factory method for creating an Intent to launch AdPlayerActivity.
         * Centralizes all required extras in one place.
         */
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
