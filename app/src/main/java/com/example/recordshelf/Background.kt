package com.example.recordshelf

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import com.example.recordshelf.scrobble.Spin
import com.example.recordshelf.scrobble.SpinItem

/**
 * Wakes the app at the moments the spinning record needs attention (a track starts or
 * starts to count), and handles the notification buttons and reboots. The work itself is
 * in [Core]; the alarm only says "look at the clock now", so a late alarm loses nothing.
 */
class SpinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Core.onSpinAction(context, intent.action.orEmpty(), intent.getLongExtra(EXTRA_RELEASE, 0), intent.getStringExtra(EXTRA_SIDE).orEmpty()) {
            pending.finish()
        }
    }

    companion object {
        const val TICK = "com.example.recordshelf.TICK"
        const val STOP = "com.example.recordshelf.STOP"
        const val FLIP = "com.example.recordshelf.FLIP"
        const val PLAY_SIDE = "com.example.recordshelf.PLAY_SIDE"
        private const val EXTRA_RELEASE = "release"
        private const val EXTRA_SIDE = "side"

        fun intent(ctx: Context, action: String, release: Long = 0, side: String = ""): PendingIntent {
            val i = Intent(ctx, SpinReceiver::class.java).setAction(action).putExtra(EXTRA_RELEASE, release).putExtra(EXTRA_SIDE, side)
            return PendingIntent.getBroadcast(ctx, action.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** Next wake-up at [atMs]; null cancels it. */
        fun schedule(ctx: Context, atMs: Long?) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = intent(ctx, TICK)
            if (atMs == null) {
                am.cancel(pi)
                return
            }
            val t = atMs + 500
            try {
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            }
        }
    }
}

/** Sends waiting scrobbles once the phone is online again; survives reboots. */
class FlushJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Core.flushFromJob(this) { retry -> jobFinished(params, retry) }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val ID = 7301

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(ID) != null) return
            js.schedule(
                JobInfo.Builder(ID, ComponentName(ctx, FlushJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(30_000)
                    .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setPersisted(true)
                    .build(),
            )
        }
    }
}

/**
 * Keeps the app alive while the first history import runs (it can take several minutes
 * for a long Last.fm history), with its progress in a notification. The import itself
 * runs in [Core] and resumes where it stopped if the app is closed anyway.
 */
class ImportService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification(this, 0, 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        running = true
        // The import may have ended before the service got here; then there is nothing to guard.
        if (!Core.isImporting()) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val ID = 3
        private const val CHANNEL = "import"

        /** True once startForeground ran; stopping before that would crash the app. */
        @Volatile
        private var running = false

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, ImportService::class.java))
            } catch (_: Exception) {
                // Not allowed from the background: the import still runs, just without the guard.
            }
        }

        fun stop(ctx: Context) {
            if (running) ctx.stopService(Intent(ctx, ImportService::class.java))
        }

        fun progress(ctx: Context, done: Int, total: Int) {
            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            ctx.getSystemService(NotificationManager::class.java).notify(ID, notification(ctx, done, total))
        }

        private fun notification(ctx: Context, done: Int, total: Int): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "History import", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_record)
                .setContentTitle("Importing your Last.fm history")
                .setContentText(if (total > 0) "%,d of %,d scrobbles".format(done, total) else "Starting…")
                .setProgress(maxOf(total, 0), minOf(done, maxOf(total, 0)), total <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
        }
    }
}

/** The "now spinning" notification with a countdown for the current track, and the "side is over" nudge. */
object SpinNotifier {
    private const val CHANNEL = "spin"
    private const val ID_PLAYING = 1
    private const val ID_SIDE_DONE = 2

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun allowed(ctx: Context) =
        Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channel(ctx: Context) {
        val c = NotificationChannel(CHANNEL, "Now spinning", NotificationManager.IMPORTANCE_LOW)
        c.description = "The record you are scrobbling"
        c.setShowBadge(false)
        nm(ctx).createNotificationChannel(c)
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(MainActivity.EXTRA_OPEN_SPIN, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun sideName(side: String) = if (side.length <= 2) "side $side" else side

    private fun action(title: String, pi: PendingIntent) = Notification.Action.Builder(null as Icon?, title, pi).build()

    fun show(ctx: Context, s: Spin, now: Long) {
        if (!allowed(ctx)) return
        channel(ctx)
        nm(ctx).cancel(ID_SIDE_DONE)
        val live = s.items.filter { it.state != SpinItem.SKIPPED }
        val cur = s.currentIndex(now)
        val item = if (cur >= 0) s.items[cur] else live.firstOrNull { it.startMs > now }
        val pos = item?.let { live.indexOf(it) + 1 } ?: 0
        val sub = listOfNotNull(
            item?.track?.side?.takeIf { it.isNotEmpty() }?.let { sideName(it).replaceFirstChar { c -> c.uppercase() } },
            if (pos > 0) "$pos of ${live.size}" else null,
        ).joinToString(" · ")
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(item?.track?.title ?: s.album)
            .setContentText("${s.artist} — ${s.album}")
            .setSubText(sub)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(ctx))
        if (cur >= 0 && item != null) {
            b.setWhen(item.endMs).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            b.setShowWhen(false)
        }
        b.addAction(action("Stop", SpinReceiver.intent(ctx, SpinReceiver.STOP)))
        s.nextSide()?.let { b.addAction(action("Flip to ${sideName(it)}", SpinReceiver.intent(ctx, SpinReceiver.FLIP))) }
        nm(ctx).notify(ID_PLAYING, b.build())
    }

    /** The session ended by itself. If the record has another side, offer to scrobble it. */
    fun finished(ctx: Context, s: Spin) {
        nm(ctx).cancel(ID_PLAYING)
        val next = s.nextSide() ?: return
        if (!allowed(ctx)) return
        channel(ctx)
        val last = s.items.lastOrNull { it.state != SpinItem.SKIPPED }?.track?.side.orEmpty()
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(if (last.isNotEmpty()) "${sideName(last).replaceFirstChar { it.uppercase() }} is over" else "${s.album} is over")
            .setContentText("Turn ${s.album} over and tap Play ${sideName(next)}")
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setTimeoutAfter(45 * 60_000L)
            .addAction(action("Play ${sideName(next)}", SpinReceiver.intent(ctx, SpinReceiver.PLAY_SIDE, s.releaseId, next)))
            .build()
        nm(ctx).notify(ID_SIDE_DONE, n)
    }

    fun cancel(ctx: Context) {
        nm(ctx).cancel(ID_PLAYING)
        nm(ctx).cancel(ID_SIDE_DONE)
    }
}
