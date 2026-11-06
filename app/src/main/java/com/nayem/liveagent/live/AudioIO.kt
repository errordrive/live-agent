package com.nayem.liveagent.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.math.sqrt

/**
 * RMS amplitude 0..1 of a little-endian PCM16 chunk (0 = silence, ~1 = full scale).
 * Pure function — no Android APIs, unit-testable on the JVM.
 */
fun rmsAmplitude(pcm: ByteArray): Float {
    if (pcm.size < 2) return 0f
    var sum = 0.0
    val samples = pcm.size / 2
    for (i in 0 until samples) {
        val lo = pcm[i * 2].toInt() and 0xFF
        val hi = pcm[i * 2 + 1].toInt()
        val sample = (hi shl 8) or lo
        val v = sample / 32768f
        sum += (v * v).toDouble()
    }
    return sqrt(sum / samples).toFloat().coerceIn(0f, 1f)
}

/** Microphone capture: 16 kHz mono PCM16, 100 ms chunks. User-initiated only. */
class MicCapture(private val context: Context, private val onPcm: (ByteArray) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var recorder: AudioRecord? = null

    /** Called on the capture thread with the RMS amplitude (0..1) of each chunk. */
    var onAmplitude: ((Float) -> Unit)? = null

    fun start() {
        if (running) return
        // Explicit permission check (also satisfies lint's MissingPermission).
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val minBuf = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(6400)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
        } catch (_: Exception) { return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
        recorder = rec
        running = true
        try {
            rec.startRecording()
        } catch (_: Exception) {
            // startRecording can throw (e.g. mic in use by another app) —
            // never crash the call; the transcript still works.
            running = false
            try { rec.release() } catch (_: Exception) {}
            recorder = null
            return
        }
        thread = Thread({
            val buf = ByteArray(3200)
            while (running) {
                try {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        val chunk = buf.copyOf(n)
                        onPcm(chunk)
                        try { onAmplitude?.invoke(rmsAmplitude(chunk)) } catch (_: Exception) {}
                    }
                } catch (_: Exception) { break }
            }
        }, "live-agent-mic").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (_: Exception) {}
        thread = null
        // Stop/release can throw if the recorder died mid-call — never crash.
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
    }
}

/** Playback of the model's 24 kHz mono PCM16 audio. */
class AudioPlayer {
    @Volatile private var track: AudioTrack? = null

    /** Called with the RMS amplitude (0..1) of each chunk written to the track. */
    var onAmplitude: ((Float) -> Unit)? = null

    @Volatile private var lastAmplitude: Float = 0f

    /** Last computed playback amplitude (0..1); 0 before any audio has played. */
    fun currentAmplitude(): Float = lastAmplitude

    @Synchronized
    fun playPcm(bytes: ByteArray) {
        try {
            var t = track
            if (t == null || t.state != AudioTrack.STATE_INITIALIZED) {
                val minBuf = AudioTrack.getMinBufferSize(
                    24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(9600)
                t = AudioTrack.Builder()
                    .setAudioAttributes(
                        // LOUDSPEAKER (v1.2.0): USAGE_MEDIA routes to the
                        // loudspeaker by default. The old USAGE_VOICE_COMMUNICATION
                        // routed call audio to the earpiece like a phone call.
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(24000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                if (t.state != AudioTrack.STATE_INITIALIZED) {
                    try { t.release() } catch (_: Exception) {}
                    return
                }
                track = t
            }
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
            t.write(bytes, 0, bytes.size)
            val amp = rmsAmplitude(bytes)
            lastAmplitude = amp
            try { onAmplitude?.invoke(amp) } catch (_: Exception) {}
        } catch (_: Exception) { /* playback is best-effort; the transcript still shows */ }
    }

    @Synchronized
    fun stop() {
        try { track?.stop(); track?.flush() } catch (_: Exception) {}
    }

    @Synchronized
    fun clear() {
        try { track?.release() } catch (_: Exception) {}
        track = null
    }
}
