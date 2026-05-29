package com.samsung.android.scan3d.serv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.samsung.android.scan3d.CameraActivity
import com.samsung.android.scan3d.R
import com.samsung.android.scan3d.fragments.CameraFragment
import com.samsung.android.scan3d.http.HttpService
class Cam : Service() {
    var engine: CamEngine? = null
    var http: HttpService? = null
    val CHANNEL_ID = "REMOTE_CAM"


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("CAM", "onStartCommand " + intent?.action)

        if (intent == null) return START_STICKY

        when (intent.action) {
            "start" -> {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_ID,
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                channel.description = "RemoteCam run"
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.createNotificationChannel(channel)

                // Create a notification for the foreground service
                val notificationIntent = Intent(this, CameraActivity::class.java)
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    System.currentTimeMillis().toInt(),
                    notificationIntent,
                    FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT
                )

                val intentKill = Intent("KILL")
                val pendingIntentKill = PendingIntent.getBroadcast(
                    this,
                    System.currentTimeMillis().toInt(),
                    intentKill,
                    FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT
                )


                var builer =
                    NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("RemoteCam (active)")
                        .setContentText("Click to open").setOngoing(true)
                        .setSmallIcon(R.drawable.ic_linked_camera).addAction(R.drawable.ic_close, "Kill",pendingIntentKill)
                        .setContentIntent(pendingIntent)



                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    //      builer?.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
                val notification: Notification = builer.build()
                startForeground(123, notification) // Start the foreground service

                if (http == null) {
                    http = HttpService()
                    http?.main()
                }
            }

            "onPause" -> {
                engine?.insidePause = true
                if (engine?.isShowingPreview == true) {
                    engine?.initializeCameraAsync()
                }
            }

            "onResume" -> {
                engine?.insidePause = false;
            }

            "start_camera_engine" -> {
                if (engine == null) {
                    engine = CamEngine(this)
                    engine?.http = http
                    engine?.initializeCameraAsync()
                }
            }

            "new_view_state" -> {
                val old = engine?.viewState!!
                val new : CameraFragment.Companion.ViewState = intent.extras?.getParcelable("data")!!
                Log.i("CAM", "new_view_state: " + new)
                engine?.viewState =  new
                if (old != new) {
                    engine?.initializeCameraAsync()
                }
            }

            "new_preview_surface" -> {
                val surface: Surface? = intent.extras?.getParcelable("surface")
                val oldSurface = engine?.previewSurface
                if (surface == oldSurface) {
                    Log.i("CAM", "Same surface, skipping re-init")
                } else {
                    Log.i("CAM", "New preview surface received")
                    engine?.previewSurface = surface
                    engine?.initializeCameraAsync()
                }
            }

            "update_zoom" -> {
                val zoomLevel = intent.getFloatExtra("zoomLevel", 1.0f)
                engine?.viewState?.zoomLevel = zoomLevel
                engine?.updateZoomLevel()
            }

            "surface_destroyed" -> {
                Log.i("CAM", "Surface destroyed, stopping preview session")
                engine?.previewSurface = null
                engine?.stopPreviewSession()
            }

            else -> {
               kill()
            }

        }

        return START_STICKY
    }

    fun kill(){
        engine?.destroy()
        engine = null
        http?.engine?.stop(500,500)
        http = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("CAM", "OnDestroy")
        kill()
    }

    companion object {
        sealed class ToCam()
        class Start() : ToCam()
        class NewSurface(surface: Surface) : ToCam()

    }
}