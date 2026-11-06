package com.nayem.liveagent.live

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Android TTS fallback (v1.2.0): speaks a task result out loud only when the
 * Live session is down (no socket / call ended) so the user still hears what
 * the agent did. Never throws; if TTS is unavailable the text is just shown
 * in the transcript instead. Best effort on language: Bangla first, then the
 * device default.
 */
class VoiceFallback(context: Context) {
    @Volatile private var ready = false
    private var tts: TextToSpeech? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    try {
                        val bn = tts?.setLanguage(Locale("bn", "BD"))
                        if (bn == TextToSpeech.LANG_MISSING_DATA ||
                            bn == TextToSpeech.LANG_NOT_SUPPORTED
                        ) {
                            tts?.setLanguage(Locale.getDefault())
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
            tts = null
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        try {
            if (ready) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "live-agent-fallback")
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
    }
}
