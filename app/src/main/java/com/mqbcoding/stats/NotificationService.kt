package com.mqbcoding.stats

import android.app.Notification
import android.content.*
import android.service.notification.StatusBarNotification
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.graphics.Bitmap
import android.os.Build
import androidx.preference.Preference
import android.service.notification.NotificationListenerService
import android.text.SpannableString
import androidx.preference.PreferenceManager
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY






class NotificationService : NotificationListenerService() {

    private var connected =false

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected=true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected=false
    }


    var resumeMedia = false

    private val onSharedPreferencesChangedListener: SharedPreferences.OnSharedPreferenceChangeListener =
            SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
                if (key=="resumeMediaOnStart") {
                    resumeMedia = sharedPreferences?.getBoolean("resumeMediaOnStart",resumeMedia) ?: resumeMedia
                }
            }

    override fun onCreate() {

        super.onCreate()

        resumeMedia=PreferenceManager.getDefaultSharedPreferences(this).getBoolean("resumeMediaOnStart",resumeMedia)

        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(onSharedPreferencesChangedListener)
    }

    override fun onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(onSharedPreferencesChangedListener)

        super.onDestroy()
    }


    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            //TODO: If CarApp visible

            if (isMapsNavNotification(sbn)) {

                val pack = sbn.packageName
                val ticker = sbn.notification.tickerText?.toString()
                val extras = sbn.notification.extras
                var title =""
                val tmpTitle = extras.get("android.title")
                try {
                    if (tmpTitle is SpannableString) {
                        //title = tmpTitle.toString()
                    } else if (tmpTitle is CharSequence || tmpTitle is String) {
                        title = tmpTitle.toString()
                    }
                } catch (e:Exception){}
                val text = extras.getCharSequence("android.text")!!.toString()

                val msgrcv = Intent("GoogleNavigationUpdate")
                msgrcv.putExtra("package", pack)
                msgrcv.putExtra("ticker", ticker)
                msgrcv.putExtra("title", title)
                msgrcv.putExtra("text", text)
                //msgrcv.putExtra("icon", icon)

                LocalBroadcastManager.getInstance(this).sendBroadcast(msgrcv)

            } else if (isAndroidAutoNotification(sbn)) {

                if (resumeMedia) {
                    val mAudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    mAudioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KEYCODE_MEDIA_PLAY))
                }

            }
        }catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {

        try {
            if (isMapsNavNotification(sbn)) {

                LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("GoogleNavigationClosed"))

            }
        } catch (e:Exception) {
            e.printStackTrace()
        }
    }

    private fun isMapsNavNotification(sbn: StatusBarNotification):Boolean =
            sbn.packageName == "com.google.android.apps.maps" && sbn.notification?.group == "navigation_status_notification_group"

    private fun isAndroidAutoNotification(sbn: StatusBarNotification):Boolean =
            sbn.packageName == "com.google.android.gms" && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || sbn.notification?.channelId=="car.default_notification_channel")
}