package com.example.recordshelf.util

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

/** A JSON document on disk, replaced atomically so a crash mid-write never leaves half a file. */
class JsonFile(val file: File) {
    companion object {
        /** Set while a backup is put in place: nothing in memory may write over the restored files before the app restarts. */
        @Volatile var frozen = false
    }

    @Synchronized
    fun read(): String? = try {
        if (file.exists()) file.readText() else null
    } catch (_: Exception) {
        null
    }

    @Synchronized
    fun write(text: String) {
        if (frozen) return
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized
    fun delete() {
        file.delete()
    }
}
