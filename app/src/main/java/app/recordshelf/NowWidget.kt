package app.recordshelf

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import app.recordshelf.net.Http
import java.util.concurrent.Executors

/**
 * The home-screen widget: what plays now (the record on the turntable, else the phone's song),
 * else the last song played, with its cover and today's and this week's plays. Tapping it opens
 * the app, on the record or Now playing page while something plays.
 */
class NowWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Asked by the launcher (placed, resized, or every half hour): drawn even if nothing changed.
        drawnAt = 0
        update(context)
    }

    companion object {
        /** Covers are drawn this big (px) with rounded corners; the widget scales them down. */
        private const val COVER_PX = 300

        private val main = Handler(Looper.getMainLooper())
        private val exec = Executors.newSingleThreadExecutor()
        @Volatile private var scheduled = false
        @Volatile private var coverFor = ""
        @Volatile private var cover: Bitmap? = null

        /** What the widgets show now, and when it was drawn: the same again isn't sent to the launcher for a while. */
        @Volatile private var drawn = ""
        @Volatile private var drawnAt = 0L
        private const val REDRAW_MS = 10 * 60_000L

        /** Draws the widgets again soon: changes that come in a burst draw them once. */
        fun update(context: Context) {
            if (scheduled) return
            scheduled = true
            val ctx = context.applicationContext
            main.postDelayed({
                scheduled = false
                exec.execute {
                    try {
                        draw(ctx)
                    } catch (_: Exception) {
                        // A widget that can't be drawn now keeps what it showed.
                    }
                }
            }, 800)
        }

        private fun draw(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, NowWidget::class.java))
            if (ids.isEmpty()) return
            Core.ensure(ctx)
            val w = Core.widgetJson()
            val now = System.currentTimeMillis()
            if (w.toString() == drawn && now - drawnAt < REDRAW_MS) return
            drawn = w.toString()
            drawnAt = now
            val v = RemoteViews(ctx.packageName, R.layout.widget_now)
            v.setTextViewText(R.id.w_label, w.optString("label"))
            v.setTextViewText(R.id.w_title, w.optString("title"))
            v.setTextViewText(R.id.w_artist, w.optString("artist"))
            v.setViewVisibility(R.id.w_artist, if (w.optString("artist").isEmpty()) View.GONE else View.VISIBLE)
            v.setTextViewText(R.id.w_line, w.optString("line"))
            val bmp = coverOf(w.optString("cover"))
            if (bmp != null) v.setImageViewBitmap(R.id.w_cover, bmp) else v.setImageViewResource(R.id.w_cover, R.drawable.widget_record)
            val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            when (w.optString("open")) {
                "spin" -> open.putExtra(MainActivity.EXTRA_OPEN_SPIN, true)
                "now" -> open.putExtra(MainActivity.EXTRA_OPEN_NOW, true)
            }
            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 7, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            mgr.updateAppWidget(ids, v)
        }

        /** The cover at [url], square with rounded corners; the last one is kept. Null without one or offline. */
        private fun coverOf(url: String): Bitmap? {
            if (url.isEmpty()) return null
            if (url == coverFor) return cover
            val bytes = Http.getBytes(url, 15_000) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= COVER_PX) sample *= 2
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val out = Bitmap.createBitmap(COVER_PX, COVER_PX, Bitmap.Config.ARGB_8888)
            val side = minOf(src.width, src.height).toFloat()
            val scale = COVER_PX / side
            val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(-(src.width - side) / 2 * scale, -(src.height - side) / 2 * scale)
                })
            }
            val r = COVER_PX * 0.12f
            Canvas(out).drawRoundRect(RectF(0f, 0f, COVER_PX.toFloat(), COVER_PX.toFloat()), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
            src.recycle()
            coverFor = url
            cover = out
            return out
        }
    }
}
