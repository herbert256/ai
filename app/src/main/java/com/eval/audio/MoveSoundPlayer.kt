package com.eval.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Plays chess move sounds using SoundPool for low-latency audio.
 * Supports different sounds for regular moves, captures, checks, and castling.
 */
class MoveSoundPlayer(context: Context) {
    private val soundPool: SoundPool
    private var moveSound: Int = 0
    private var captureSound: Int = 0
    private var checkSound: Int = 0
    private var castleSound: Int = 0
    private var isLoaded = false

    init {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(audioAttributes)
            .build()

        soundPool.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) {
                isLoaded = true
            }
        }

        // Load sounds from raw resources (WAV format for broad compatibility)
        try {
            moveSound = soundPool.load(context, com.eval.R.raw.move, 1)
            captureSound = soundPool.load(context, com.eval.R.raw.capture, 1)
            checkSound = soundPool.load(context, com.eval.R.raw.check, 1)
            castleSound = soundPool.load(context, com.eval.R.raw.castle, 1)
            isLoaded = true
        } catch (e: Exception) {
            android.util.Log.e("MoveSoundPlayer", "Error loading sounds: ${e.message}")
            isLoaded = false
        }
    }

    /**
     * Play the appropriate sound for a move.
     * @param isCapture True if the move captures a piece
     * @param isCheck True if the move gives check
     * @param isCastle True if the move is castling
     */
    fun playMove(isCapture: Boolean = false, isCheck: Boolean = false, isCastle: Boolean = false) {
        if (!isLoaded) return

        val soundId = when {
            isCheck && checkSound != 0 -> checkSound
            isCapture && captureSound != 0 -> captureSound
            isCastle && castleSound != 0 -> castleSound
            else -> moveSound
        }

        if (soundId != 0) {
            soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f)
        }
    }

    /**
     * Play a simple move sound (used for navigation).
     */
    fun playMoveSound() {
        if (!isLoaded || moveSound == 0) return
        soundPool.play(moveSound, 0.8f, 0.8f, 1, 0, 1.0f)
    }

    /**
     * Release resources when no longer needed.
     */
    fun release() {
        soundPool.release()
    }
}
