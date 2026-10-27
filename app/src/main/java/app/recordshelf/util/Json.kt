package app.recordshelf.util

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A string value, or "" when missing or JSON null (Android's optString would give "null"). */
fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

/** An array that some APIs send as a bare object when it has one element. */
fun JSONObject.arrayOrOne(key: String): JSONArray = optJSONArray(key) ?: optJSONObject(key)?.let { JSONArray().put(it) } ?: JSONArray()

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }.filter { it.isNotEmpty() && it != "null" }

fun List<String>.toJsonArray(): JSONArray = JSONArray().also { a -> forEach { a.put(it) } }

/**
 * A JSON document on disk, replaced atomically so a crash mid-write never leaves half a file.
 * Several JsonFiles for the same path take turns (the lock is per path, not per object).
 */
class JsonFile(val file: File) {
    companion object {
        /** While a backup is put in place: the files nothing in memory may write over before the app restarts. */
        @Volatile var frozen: (File) -> Boolean = { false }

        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }

    private val lock = locks.getOrPut(file.path) { Any() }

    fun read(): String? = synchronized(lock) {
        try {
            if (file.exists()) file.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    fun write(text: String) {
        synchronized(lock) { writeLocked(text) }
    }

    private fun writeLocked(text: String) {
        if (frozen(file)) return
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        // On the disk before it replaces the old file, so a power cut can't leave an empty one.
        java.io.FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun delete() {
        synchronized(lock) { file.delete() }
    }
}
