package app.recordshelf

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.webkit.WebResourceResponse
import androidx.core.content.ContextCompat
import app.recordshelf.history.Moments
import app.recordshelf.history.Photo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The photos on this phone, for the story: the ones taken in a period, and each one shrunk for
 * the page. They never leave the phone; the page gets them at an address only this app answers
 * (see MainActivity), and only while the user has photos switched on.
 */
object Photos {
    const val HOST = "appassets.androidplatform.net"
    private const val PATH = "/photo/"
    private const val LIMIT = 5000
    private const val SIZE = 1080

    /** Photos smaller than this (on their short side) are icons, stickers and the like. */
    private const val SMALL = 480

    /** At most this many folders to choose from in Settings, the fullest. */
    private const val FOLDERS = 24

    /** A folder with photos, from the storage's top ("DCIM/Camera"): how many it has and whether it's the camera's. */
    data class Folder(val key: String, val count: Int, val camera: Boolean)

    private val cache = object : LruCache<Long, ByteArray>(12 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: ByteArray) = value.size
    }

    fun url(id: Long) = "https://$HOST$PATH$id"

    private fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** What the app may read: "all" photos, "some" (only the ones the user chose, from Android 14) or "none". */
    fun access(ctx: Context): String = when {
        Build.VERSION.SDK_INT >= 33 && has(ctx, Manifest.permission.READ_MEDIA_IMAGES) -> "all"
        Build.VERSION.SDK_INT >= 34 && has(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> "some"
        Build.VERSION.SDK_INT < 33 && has(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) -> "all"
        else -> "none"
    }

    /** The permissions to ask for; from Android 14 the user may also choose only some photos. */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /**
     * Photos taken from [fromSec] until [toSec] in [folders], or if null by the phone's camera: no screenshots,
     * nothing saved from other apps.
     */
    fun taken(ctx: Context, fromSec: Long, toSec: Long, folders: Set<String>? = null): List<Photo> {
        if (access(ctx) == "none") return emptyList()
        val whole = Build.VERSION.SDK_INT < 29
        val where = if (!whole) MediaStore.Images.Media.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.Images.Media.DATA
        val proj = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT, where, MediaStore.Images.Media.ORIENTATION,
        )
        val out = ArrayList<Photo>()
        try {
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?",
                arrayOf((fromSec * 1000).toString(), (toSec * 1000).toString()), "${MediaStore.Images.Media.DATE_TAKEN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < LIMIT) {
                    val folder = Moments.folderOf(c.getString(4).orEmpty(), whole)
                    if (if (folders == null) !Moments.byCamera(folder) else folder !in folders) continue
                    var w = c.getInt(2)
                    var h = c.getInt(3)
                    if (c.getInt(5) % 180 == 90) w = h.also { h = w }
                    if (w > 0 && h > 0 && minOf(w, h) < SMALL) continue
                    out += Photo(c.getLong(0), c.getLong(1) / 1000, w, h)
                }
            }
        } catch (_: Exception) {
            // Taken away meanwhile, or a phone whose media store won't answer: no photos.
        }
        return out
    }

    /** The folders with photos on this phone, the fullest first, to choose from in Settings. */
    fun folders(ctx: Context): List<Folder> {
        if (access(ctx) == "none") return emptyList()
        val whole = Build.VERSION.SDK_INT < 29
        val col = if (!whole) MediaStore.Images.Media.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.Images.Media.DATA
        val counts = HashMap<String, Int>()
        try {
            ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(col), null, null, null)?.use { c ->
                while (c.moveToNext()) counts.merge(Moments.folderOf(c.getString(0).orEmpty(), whole), 1, Int::plus)
            }
        } catch (_: Exception) {
            // As in taken: no folders to show.
        }
        return counts.entries.filter { it.key.isNotEmpty() }.sortedByDescending { it.value }.take(FOLDERS)
            .map { Folder(it.key, it.value, Moments.byCamera(it.key)) }
    }

    /** The page's request for a photo, answered with it shrunk to a JPEG; null for anything else. */
    fun answer(ctx: Context, url: android.net.Uri): WebResourceResponse? {
        if (url.host != HOST || url.path?.startsWith(PATH) != true) return null
        val id = url.lastPathSegment?.toLongOrNull()
        val bytes = if (id != null && Core.prefs.storyPhotos) jpeg(ctx, id) else null
        return if (bytes == null) WebResourceResponse("image/jpeg", null, 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        else WebResourceResponse("image/jpeg", null, ByteArrayInputStream(bytes))
    }

    private fun jpeg(ctx: Context, id: Long): ByteArray? {
        cache.get(id)?.let { return it }
        if (access(ctx) == "none") return null
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val bmp = try {
            if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(uri, Size(SIZE, SIZE), null) else decode(ctx, id)
        } catch (_: Exception) {
            null
        } ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return out.toByteArray().also { cache.put(id, it) }
    }

    /** Before Android 10: the photo itself, read at about [SIZE] and turned upright. */
    private fun decode(ctx: Context, id: Long): Bitmap? {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val r = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val bmp = r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val turn = r.query(uri, arrayOf(MediaStore.Images.Media.ORIENTATION), null, null, null)?.use { c -> if (c.moveToFirst()) c.getInt(0) else 0 } ?: 0
        if (turn == 0) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(turn.toFloat()) }, true).also { if (it !== bmp) bmp.recycle() }
    }
}
