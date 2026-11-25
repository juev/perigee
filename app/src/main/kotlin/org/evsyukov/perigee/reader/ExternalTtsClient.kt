package org.evsyukov.perigee.reader

import android.util.Base64
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONException
import org.json.JSONObject

/**
 * What a probe learned about the speech server, kept so probing happens once per address.
 * [field] is the JSON member carrying base64 audio; empty for [kind] "raw".
 */
internal data class Detected(
    val base: String,
    val path: String,
    val model: String,
    val voice: String,
    val kind: String,
    val field: String,
    val voices: List<String> = emptyList(),
) {
    fun toJson(): String = JSONObject()
        .put("base", base).put("path", path).put("model", model)
        .put("voice", voice).put("kind", kind).put("field", field)
        .put("voices", org.json.JSONArray(voices))
        .toString()

    companion object {
        fun fromJson(raw: String): Detected? = try {
            val json = JSONObject(raw)
            val list = json.optJSONArray("voices")
            Detected(
                base = json.getString("base"),
                path = json.getString("path"),
                model = json.optString("model", ExternalTtsClient.DEFAULT_MODEL),
                voice = json.optString("voice", ExternalTtsClient.DEFAULT_VOICE),
                kind = json.optString("kind", "raw"),
                field = json.optString("field", ""),
                voices = List(list?.length() ?: 0) { i -> list?.optString(i, "").orEmpty() }.filter { it.isNotBlank() },
            )
        } catch (_: JSONException) {
            null
        }
    }
}

/**
 * Speaks with an OpenAI-format server at an address the reader typed. Nothing but the address
 * (and an optional key) is configured: the endpoint path, the model, the voice and the shape of
 * the answer are learned by probing, and what a probe found is kept in the reader's preferences.
 * Called on a background dispatcher.
 */
internal class ExternalTtsClient(
    private val url: String,
    private val key: String,
    cached: Detected?,
    /** The voice the reader picked; blank is "the server's first". Null when no choice is stored. */
    private val chosenVoice: String? = null,
    /** Persists the JSON of a successful probe, or "" to clear it. Suspend: DataStore. */
    private val store: suspend (String) -> Unit,
) {
    var failure: SpeechNote? = null
        private set

    /** The file extension of the last synthesized audio, for the temp file the player reads. */
    var audioExt: String = ".mp3"
        private set

    private var detected: Detected? = cached

    /** The reader's pick while the server still offers it, else the first of the server's list. */
    private fun withChosen(candidate: Detected): Detected {
        val pick = chosenVoice?.takeIf { it.isNotBlank() } ?: return candidate
        return if (candidate.voices.isEmpty() || pick in candidate.voices) candidate.copy(voice = pick) else candidate
    }

    /** Tries the cache first, then the probes. Null when the server will not do; [failure] says why. */
    suspend fun detect(): Detected? {
        failure = null
        val base = normalizeBaseUrl(url) ?: run {
            Log.w(TAG, "probe: the address does not parse")
            failure = SpeechNote.Unreachable
            return null
        }
        detected?.takeIf { it.base == base }?.let { cached ->
            // A cache kept before the picker existed has no voice list in it; learn it now and keep it.
            val refreshed = if (cached.voices.isEmpty()) {
                val voices = probeVoices(base)
                if (voices.isEmpty()) cached
                else cached.copy(voices = voices).also { store(it.toJson()) }
            } else cached
            Log.i(TAG, "probe: cached for this address (${refreshed.kind} at ${refreshed.path})")
            return withChosen(refreshed)
        }
        val model = probeModels(base)
        val voices = probeVoices(base)
        Log.i(TAG, "probe: model $model, ${voices.size} voice(s)")
        for (path in SPEECH_PATHS) {
            val found = probeEndpoint(base, path, model, voices.firstOrNull() ?: DEFAULT_VOICE)
            if (found != null) {
                val resolved = withChosen(found.copy(voice = voices.firstOrNull() ?: DEFAULT_VOICE, voices = voices))
                Log.i(TAG, "probe: found ${resolved.kind} at ${resolved.path}, voice ${resolved.voice}")
                detected = resolved
                store(resolved.toJson())
                return resolved
            }
            Log.w(TAG, "probe: $path -> $failure")
            if (failure != SpeechNote.NotFound) return null
        }
        return null
    }

    /** The server's audio for [text], or null; [failure] says why. A 404 clears the cache and probes once more. */
    suspend fun synthesize(text: String, speed: Float): ByteArray? {
        var current = detected ?: detect() ?: return null
        repeat(2) { attempt ->
            val bytes = post(current, text, speed)
            if (bytes != null) return bytes
            Log.w(TAG, "synthesize: $failure at ${current.path}")
            if (failure != SpeechNote.NotFound || attempt > 0) return null
            detected = null
            store("")
            current = detect() ?: return null
        }
        return null
    }

    private fun post(current: Detected, text: String, speed: Float): ByteArray? {
        val conn = try {
            open(
                "POST", current.base + current.path,
                body = JSONObject()
                    .put("model", current.model)
                    .put("input", text)
                    .put("voice", current.voice)
                    .put("response_format", "mp3")
                    .put("speed", speed.toDouble()),
                readTimeout = 30_000,
            )
        } catch (_: IOException) {
            failure = SpeechNote.Unreachable
            return null
        }
        return try {
            when (val code = conn.responseCode) {
                in 200..299 -> {
                    val type = conn.contentType?.lowercase()?.substringBefore(';')
                    when {
                        type?.startsWith("audio/") == true -> {
                            audioExt = "." + (type.substringAfter("audio/").takeIf { it in AUDIO_EXTS } ?: "mp3")
                            conn.inputStream.use { it.readBytes() }
                        }
                        type == "application/json" || type == "text/json" -> {
                            val body = conn.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
                            val audio = jsonAudio(body) ?: run {
                                failure = SpeechNote.Synthesis
                                return null
                            }
                            audioExt = ".mp3"
                            audio.first
                        }
                        // An answer that is neither audio nor JSON is not a speech endpoint.
                        else -> {
                            failure = SpeechNote.NotFound
                            null
                        }
                    }
                }
                401, 403 -> {
                    Log.w(TAG, "synthesize: HTTP $code, the key was rejected")
                    failure = SpeechNote.Unauthorized
                    null
                }
                404, 405 -> {
                    Log.w(TAG, "synthesize: HTTP $code at ${current.path}")
                    failure = SpeechNote.NotFound
                    null
                }
                else -> {
                    Log.w(TAG, "synthesize: HTTP $code")
                    failure = SpeechNote.Synthesis
                    conn.errorStream?.readBytes() // Read and drop the server's reason.
                    null
                }
            }
        } catch (_: IOException) {
            failure = SpeechNote.Unreachable
            null
        } finally {
            conn.disconnect()
        }
    }

    /** The base64 member the answer carries its audio in, decoded with its name; null when none is there. */
    private fun jsonAudio(body: String): Pair<ByteArray, String>? {
        val json = try { JSONObject(body) } catch (_: JSONException) { return null }
        for (field in JSON_AUDIO_FIELDS) {
            val value = json.optString(field, "")
            if (value.isBlank()) continue
            val bytes = try { Base64.decode(value, Base64.DEFAULT) } catch (_: IllegalArgumentException) { null }
            if (bytes != null && bytes.isNotEmpty()) return bytes to field
        }
        return null
    }

    /** The first model id that sounds like speech, or the OpenAI default; never fatal. */
    private fun probeModels(base: String): String {
        val conn = try { open("GET", "$base/v1/models") } catch (_: IOException) { return DEFAULT_MODEL }
        return try {
            if (conn.responseCode != 200) return DEFAULT_MODEL
            val body = conn.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            val data = JSONObject(body).optJSONArray("data") ?: return DEFAULT_MODEL
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id").orEmpty()
                if (id.contains("tts", ignoreCase = true)) return id
            }
            DEFAULT_MODEL
        } catch (_: IOException) {
            DEFAULT_MODEL
        } catch (_: JSONException) {
            DEFAULT_MODEL
        } finally {
            conn.disconnect()
        }
    }

    /** One synthesis attempt as a probe; a learned endpoint and answer shape come back, else [failure] is set. */
    private suspend fun probeEndpoint(base: String, path: String, model: String, voice: String): Detected? {
        val conn = try {
            open(
                "POST", base + path,
                body = JSONObject()
                    .put("model", model)
                    .put("input", "Perigee")
                    .put("voice", voice)
                    .put("response_format", "mp3")
                    .put("speed", 1.0),
            )
        } catch (_: IOException) {
            failure = SpeechNote.Unreachable
            return null
        }
        return try {
            when (conn.responseCode) {
                in 200..299 -> {
                    val type = conn.contentType?.lowercase()?.substringBefore(';')
                    when {
                        type?.startsWith("audio/") == true -> Detected(base, path, model, DEFAULT_VOICE, "raw", "")
                        type == "application/json" || type == "text/json" -> {
                            val body = conn.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
                            val audio = jsonAudio(body)
                            if (audio == null) {
                                failure = SpeechNote.NotFound
                                null
                            } else {
                                Detected(base, path, model, DEFAULT_VOICE, "json", audio.second)
                            }
                        }
                        else -> {
                            failure = SpeechNote.NotFound
                            null
                        }
                    }
                }
                401, 403 -> {
                    failure = SpeechNote.Unauthorized
                    null
                }
                404, 405 -> {
                    failure = SpeechNote.NotFound
                    null
                }
                else -> {
                    failure = SpeechNote.Synthesis
                    null
                }
            }
        } catch (_: IOException) {
            failure = SpeechNote.Unreachable
            null
        } finally {
            conn.disconnect()
        }
    }

    /** The server's own voice list, asked before any synthesis probe: many servers 404 an unknown voice. */
    private fun probeVoices(base: String): List<String> {
        val conn = try { open("GET", "$base/v1/audio/voices") } catch (_: IOException) { return emptyList() }
        return try {
            if (conn.responseCode != 200) return emptyList()
            val body = conn.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            val json = JSONObject(body)
            val ids = mutableListOf<String>()
            for (name in listOf("voices", "data")) {
                val list = json.optJSONArray(name) ?: continue
                for (i in 0 until list.length()) {
                    val id = list.optString(i, "").ifBlank { list.optJSONObject(i)?.optString("id").orEmpty() }
                    if (id.isNotBlank()) ids += id
                }
                if (ids.isNotEmpty()) return ids
            }
            emptyList()
        } catch (_: IOException) {
            emptyList()
        } catch (_: JSONException) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    private fun open(method: String, url: String, body: JSONObject? = null, readTimeout: Int = 15_000): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = readTimeout
        conn.requestMethod = method
        if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")
        if (body != null) {
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        }
        return conn
    }

    internal companion object {
        const val DEFAULT_MODEL = "tts-1"
        const val DEFAULT_VOICE = "alloy"
        private val SPEECH_PATHS = listOf("/v1/audio/speech", "/audio/speech")
        private val JSON_AUDIO_FIELDS = listOf("audio", "data", "b64_json")
        private val AUDIO_EXTS = setOf("mpeg", "mp3", "wav", "wave", "ogg", "opus", "aac", "flac")

        /** The address as a bare server: a scheme is added, the host lowered, the trailing slash dropped. */
        internal fun normalizeBaseUrl(typed: String): String? {
            var text = typed.trim()
            if (text.isEmpty()) return null
            if (text.contains("://")) {
                if (!text.startsWith("http://", true) && !text.startsWith("https://", true)) return null
            } else {
                text = "https://$text"
            }
            val parsed = try { URL(text) } catch (_: MalformedURLException) { return null }
            if (parsed.protocol != "http" && parsed.protocol != "https") return null
            val host = parsed.host.lowercase()
            if (host.isEmpty()) return null
            val port = if (parsed.port == -1) "" else ":${parsed.port}"
            return "${parsed.protocol}://$host$port"
        }
    }
}
