package com.mqbcoding.stats

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.AudioManager
import androidx.core.content.ContextCompat.getSystemService



class AudioPlayer (val context: Context) {

    private var mediaPlayer: MediaPlayer? = null

    fun stop() {
        if (mediaPlayer != null) {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    /**
     * @param volume from 0f to 1f
     */
    fun play(rid: Int, volume:Float?=null) {
        try {
            stop()

            mediaPlayer = MediaPlayer.create(context, rid)

            mediaPlayer?.isLooping = false
            if (volume != null)
                mediaPlayer?.setVolume(volume, volume)

            mediaPlayer?.setOnCompletionListener { stop() }

            mediaPlayer?.start()
        } catch (e:Exception){
            e.printStackTrace()
        }
    }

}