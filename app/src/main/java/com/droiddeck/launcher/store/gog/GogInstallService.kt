package com.droiddeck.launcher.store.gog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.droiddeck.launcher.MainActivity
import com.droiddeck.launcher.R

/**
 * Keeps the process in the foreground while a GOG game downloads: games run to tens of gigabytes,
 * and without a foreground service Android may kill the app (and the download's proot with it) the
 * moment the user switches away. A killed or stopped download resumes when installed again.
 */
class GogInstallService : Service() {
    /** One download: [kind] is "install" or "update"; [base] a [GogManager.Base] id; [dir] the game's folder for an update. */
    class Job(val kind: String, val id: String, val title: String, val base: String, val dir: String?)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            GogState.cancel()
            return START_NOT_STICKY
        }
        val job = intent?.let(::jobFrom)
        startForeground(NOTIFICATION_ID, notification(job?.title ?: "GOG", "Starting…", -1))
        if (job == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        Thread({
            val manager = getSystemService(NotificationManager::class.java)
            var shown = Int.MIN_VALUE
            GogState.run(applicationContext, job) { stage, percent ->
                // Every percent at most once: a notification per line would flood the system.
                if (percent != shown) {
                    shown = percent
                    manager?.notify(NOTIFICATION_ID, notification(job.title, stage, percent))
                }
            }
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf(startId)
        }, "gog-${job.kind}").start()
        return START_NOT_STICKY
    }

    private fun notification(title: String, stage: String, percent: Int): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "GOG downloads",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while a GOG game downloads"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        })
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1,
            Intent(this, GogInstallService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(title)
            .setContentText(stage)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", cancel).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "gog-download"
        private const val NOTIFICATION_ID = 4
        private const val ACTION_CANCEL = "com.droiddeck.launcher.gog.CANCEL"

        fun start(context: Context, job: Job) {
            val intent = Intent(context, GogInstallService::class.java)
                .putExtra("kind", job.kind).putExtra("id", job.id).putExtra("title", job.title)
                .putExtra("base", job.base).putExtra("dir", job.dir)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        private fun jobFrom(intent: Intent): Job? {
            val kind = intent.getStringExtra("kind") ?: return null
            val id = intent.getStringExtra("id") ?: return null
            return Job(kind, id, intent.getStringExtra("title") ?: id, intent.getStringExtra("base") ?: "internal", intent.getStringExtra("dir"))
        }
    }
}
