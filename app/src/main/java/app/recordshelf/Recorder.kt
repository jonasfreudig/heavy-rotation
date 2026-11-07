package app.recordshelf

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A short recording from the microphone for Identify: mono 16 kHz, as a WAV file in memory. Nothing is saved. */
object Recorder {
    private const val RATE = 16_000

    /**
     * [ms] of sound from the microphone as WAV, or null when it can't be had (another app holds it). Stops early when
     * [stop] says so. Needs the microphone permission, asked for before.
     */
    @SuppressLint("MissingPermission")
    fun wav(ms: Long, stop: () -> Boolean): ByteArray? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        } catch (_: Exception) {
            return null
        }
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) return null
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) return null
            val total = (RATE * ms / 1000).toInt() * 2
            val pcm = ByteArrayOutputStream(total)
            val buf = ByteArray(maxOf(min, 4096))
            while (pcm.size() < total && !stop()) {
                val n = rec.read(buf, 0, minOf(buf.size, total - pcm.size()))
                if (n < 0) return null
                pcm.write(buf, 0, n)
            }
            return wavOf(pcm.toByteArray())
        } finally {
            try {
                rec.stop()
            } catch (_: Exception) {
            }
            rec.release()
        }
    }

    /** 16-bit mono PCM at [RATE] with a WAV header. */
    private fun wavOf(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }
}
