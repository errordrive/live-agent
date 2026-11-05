package com.nayem.liveagent.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder

/** Microphone capture: 16 kHz mono PCM16, 100 ms chunks. User-initiated only. */
class MicCapture(private val context: Context, private val onPcm: (ByteArray) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var recorder: AudioRecord? = null

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
        rec.startRecording()
        thread = Thread({
            val buf = ByteArray(3200)
            while (running) {
                try {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) onPcm(buf.copyOf(n))
                } catch (_: Exception) { break }
            }
        }, "live-agent-mic").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (_: Exception) {}
        thread = null
        try { recorder?.stop(); recorder?.release() } catch (_: Exception) {}
        recorder = null
    }
}

/** Playback of the model's 24 kHz mono PCM16 audio. */
class AudioPlayer {
    @Volatile private var track: AudioTrack? = null

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
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
                track = t
            }
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
            t.write(bytes, 0, bytes.size)
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
