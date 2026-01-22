package com.example.point25

import android.media.MediaPlayer
import android.os.Bundle
import android.os.CountDownTimer
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.example.point25.databinding.ActivityMainBinding
import com.example.videoadssdk.core.AdsSdk
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.launch


class MainActivity : AppCompatActivity() {
    companion object {
        private const val DEMO_CHANNEL_ID = "demo_channel"
    }
    private lateinit var binding: ActivityMainBinding

    private lateinit var firebaseAnalytics: FirebaseAnalytics

    private var team1Score = 0
    private var team2Score = 0

    private var timerLengthMillis: Long = 5 * 60 * 1000L
    private var remainingMillis: Long = timerLengthMillis
    private var countDownTimer: CountDownTimer? = null
    private var isTimerRunning = false

    // For analytics: track who is leading to emit "lead_changed" only when it changes
    private var lastLeader: String = "TIE" // "TEAM_1" / "TEAM_2" / "TIE"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyFullScreen()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)


        // Firebase Analytics
        firebaseAnalytics = FirebaseAnalytics.getInstance(this)

        // --- Init SDK (ONLY required call in app code) ---
        AdsSdk.init(
            context = applicationContext,
            baseUrl = "https://video-ads-sdk.onrender.com/",
            appId = "demo_app"
        )

        // Example 1: Click-based ads (show ad every 5 clicks)
        /*
        lifecycleScope.launch {
            AdsSdk.setPreferences(
                categories = listOf("TV", "CAR", "GAME"),
                triggerType = "CLICKS",
                clicksCount = 5,
                xDelaySeconds = 5
            )
        }
        */

        // Example 2: Interval-based ads (show ad every 30 seconds)
        lifecycleScope.launch {
            AdsSdk.setPreferences(
                categories = listOf("TV", "CAR", "GAME"),
                triggerType = "INTERVAL",
                intervalSeconds = 10,
                xDelaySeconds = 5
            )
        }


        // --- UI init ---
        updateScores()
        updateTimerText()
        setPlayPauseIcon(false)

        binding.layoutTeam1.setOnClickListener {
            team1Score++
            updateScores()
            maybeLogLeaderChanged()

            // AdsSdk.registerClick()
            // AdsSdk.maybeShowAd { this }
        }

        binding.layoutTeam2.setOnClickListener {
            team2Score++


            updateScores()
            maybeLogLeaderChanged()

            // AdsSdk.registerClick()
            // AdsSdk.maybeShowAd { this }
        }

        binding.btnMinusTeam1.setOnClickListener {
            if (team1Score > 0) {
                team1Score--
                updateScores()
                maybeLogLeaderChanged()
            }
        }

        binding.btnMinusTeam2.setOnClickListener {
            if (team2Score > 0) {
                team2Score--
                updateScores()
                maybeLogLeaderChanged()
            }
        }

        binding.btnPlayPause.setOnClickListener {
            if (isTimerRunning) pauseTimer() else startTimer()
        }

        binding.tvTimer.setOnClickListener {
            showTimePickerDialog()
        }

        binding.btnResetGame.setOnClickListener {
            resetGame()
        }
    }

    override fun onResume() {
        super.onResume()
        applyFullScreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullScreen()
    }

    private fun applyFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        supportActionBar?.hide()

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun maybeLogLeaderChanged() {
        val newLeader = when {
            team1Score > team2Score -> "TEAM_1"
            team2Score > team1Score -> "TEAM_2"
            else -> "TIE"
        }

        if (newLeader != lastLeader) {
            val params = Bundle().apply {
                putString("from", lastLeader)
                putString("to", newLeader)
                putLong("team1_score", team1Score.toLong())
                putLong("team2_score", team2Score.toLong())
                putLong("diff", kotlin.math.abs(team1Score - team2Score).toLong())
            }
            firebaseAnalytics.logEvent("lead_changed", params)
            lastLeader = newLeader
        }
    }

    private fun logTimerCustomSet(oldMinutes: Long, newMinutes: Long) {
        val params = Bundle().apply {
            putLong("old_minutes", oldMinutes)
            putLong("new_minutes", newMinutes)
        }
        firebaseAnalytics.logEvent("timer_custom_set", params)
    }

    // ---------- Helpers ----------
    private fun updateScores() {
        binding.tvTeam1Score.text = team1Score.toString()
        binding.tvTeam2Score.text = team2Score.toString()
    }

    private fun updateTimerText() {
        val minutes = (remainingMillis / 1000) / 60
        val seconds = (remainingMillis / 1000) % 60
        binding.tvTimer.text = String.format("%02d:%02d", minutes, seconds)
    }

    private fun setPlayPauseIcon(isPlaying: Boolean) {
        binding.btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun startTimer() {
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(remainingMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                remainingMillis = millisUntilFinished
                updateTimerText()
            }

            override fun onFinish() {
                isTimerRunning = false
                setPlayPauseIcon(false)
                playEndSound()
            }
        }.start()

        isTimerRunning = true
        setPlayPauseIcon(true)
    }

    private fun pauseTimer() {
        countDownTimer?.cancel()
        isTimerRunning = false
        setPlayPauseIcon(false)
    }

    private fun resetTimerOnly() {
        countDownTimer?.cancel()
        isTimerRunning = false
        remainingMillis = timerLengthMillis
        updateTimerText()
        setPlayPauseIcon(false)
    }

    private fun resetGame() {
        team1Score = 0
        team2Score = 0
        updateScores()
        resetTimerOnly()

        // reset leader tracking because game restarted
        lastLeader = "TIE"
    }

    private fun playEndSound() {
        val mp = MediaPlayer.create(this, R.raw.sports)
        mp.start()

        object : CountDownTimer(4000, 4000) {
            override fun onTick(millisUntilFinished: Long) {}
            override fun onFinish() {
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        }.start()
    }

    private fun showTimePickerDialog() {
        val input = EditText(this).apply {
            hint = "מספר דקות"
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        AlertDialog.Builder(this)
            .setTitle("הגדרת זמן משחק")
            .setMessage("הכנס מספר דקות למשחק")
            .setView(input)
            .setPositiveButton("אישור") { _, _ ->
                val minutesStr = input.text.toString()
                if (minutesStr.isNotBlank()) {
                    val newMinutes = minutesStr.toLong()

                    val oldMinutes = (timerLengthMillis / 1000) / 60
                    logTimerCustomSet(oldMinutes = oldMinutes, newMinutes = newMinutes)

                    timerLengthMillis = newMinutes * 60 * 1000L
                    remainingMillis = timerLengthMillis
                    resetTimerOnly()
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }


}
