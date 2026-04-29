package ee.river.android

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder

class RiverForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(42, notification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "River", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Keeps River available from the notification shade."
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        val askIntent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_ASK
            putExtra(MainActivity.EXTRA_AUTO_START, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 100, askIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("River is available")
            .setContentText("Tap Ask River to capture a thought.")
            .setSmallIcon(R.drawable.ic_river)
            .setContentIntent(pending)
            .addAction(R.drawable.ic_river, "Ask River", pending)
            .setOngoing(true)
            .build()
    }

    companion object { const val CHANNEL_ID = "river_status" }
}
