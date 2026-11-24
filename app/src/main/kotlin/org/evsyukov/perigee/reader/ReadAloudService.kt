package org.evsyukov.perigee.reader

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.BreakIterator
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.evsyukov.perigee.MainActivity
import org.evsyukov.perigee.R
import org.evsyukov.perigee.data.ReaderPreferences
import org.evsyukov.perigee.engine.ReadAloudSpan

internal enum class SpeechPhase { Starting, Playing, Paused, Ended, Failed, NoVoice }
internal enum class SpeechCommand { Play, Pause, Previous, Next, Stop, Rate }
/** Logical bounds of the current speech highlight, with no book text in UI state. */
internal data class SpeechChunk(val start: String, val end: String)
internal data class SpeechState(
    val phase: SpeechPhase = SpeechPhase.Starting,
    val rate: Float = 1f,
    val chunk: SpeechChunk? = null,
    val note: SpeechNote? = null,
)

/** An in-process session, created only by a visible reader. No book text in intents or notifications. */
internal class SpeechRequest(
    val language: String,
    val start: String,
    val load: suspend (String, Int) -> ReadAloudSpan?,
    val previous: suspend (String) -> String?,
    val moveTo: suspend (String) -> Boolean,
    val mapTiming: (suspend (ReadAloudSpan, Int, Int) -> SpeechChunk?)? = null,
    val timingEnabled: Flow<Boolean> = flowOf(true),
    val externalTts: ExternalTtsConfig? = null,
) {
    val token: String = UUID.randomUUID().toString()
    val state = MutableStateFlow(SpeechState())
    var dispatch: ((SpeechCommand, Float) -> Unit)? = null
    var followPageTurn: ((Deferred<String?>) -> Unit)? = null
    // Main-thread only. Keep the newest target even before the service's START is delivered
    // or if Pause cancels playback while the page is still being rendered.
    var pageTurn: Deferred<String?>? = null

    fun seekAfterPageTurn(target: Deferred<String?>) {
        pageTurn = target
        followPageTurn?.invoke(target)
    }
}

/** Main-thread only. Repeated words in one sentence yield the same UTF-16 slice. */
private class SpokenTiming(private val text: String, locale: Locale) {
    private val boundaries by lazy {
        BreakIterator.getSentenceInstance(locale).also { it.setText(text) }
    }
    val sentence = MutableStateFlow<IntRange?>(null)
    private var latest = -1

    fun offer(start: Int, end: Int) {
        if (start < 0 || end <= start || end > text.length || start < latest) return
        if (text[start].isLowSurrogate() || (end < text.length && text[end].isLowSurrogate())) return
        if (text[start].isWhitespace()) return
        latest = start
        if (sentence.value?.contains(start) == true) return
        var to = boundaries.following(start)
        if (to == BreakIterator.DONE) return
        var from = boundaries.previous()
        if (from == BreakIterator.DONE) return
        while (from < to && text[from].isWhitespace()) from++
        while (from < to && text[to - 1].isWhitespace()) to--
        if (from <= start && start < to) sentence.value = from until to
    }
}

/** Owns synthesis, media controls and audio focus while an explicitly started session is alive. */
class ReadAloudService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
        Log.w(TAG, "speech crashed", e)
        if (request != null) finish(SpeechPhase.Failed)
    })
    private val handler = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val wake by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ReadAloud")
            .also { it.setReferenceCounted(false) }
    }
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var focus: AudioFocusRequest? = null
    private var focusOwner: Any? = null
    private var media: MediaSession? = null
    private var request: SpeechRequest? = null
    private var engine: AloudEngine? = null
    private var job: Job? = null
    private var cursor: String? = null
    private var current: ReadAloudSpan? = null
    // Bumped whenever speech is stopped early: a retired round's timing worker must not write a highlight.
    private var speechRound = 0
    private var receiverRegistered = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) command(SpeechCommand.Pause)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val offered = pending ?: request
        if (offered == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == START && intent.getStringExtra(TOKEN) == offered.token) {
            if (request !== offered) {
                clearSession()
                pending = null
                request = offered
                cursor = offered.start
                offered.dispatch = ::command
                offered.followPageTurn = { restartAfterPageTurn(offered) }
                try {
                    createMedia()
                    startForeground(ID, notification())
                    ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                        ContextCompat.RECEIVER_NOT_EXPORTED)
                    receiverRegistered = true
                    play()
                } catch (_: RuntimeException) {
                    finish(SpeechPhase.Failed)
                }
            }
        } else if (intent?.getStringExtra(TOKEN) == request?.token && intent != null) {
            SpeechCommand.entries.firstOrNull { it.name == intent.action }?.let { command(it) }
        }
        return START_NOT_STICKY
    }

    private fun createMedia() {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.read_aloud), NotificationManager.IMPORTANCE_LOW))
        media = MediaSession(this, CHANNEL).apply {
            setSessionActivity(openApp())
            setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, getString(R.string.read_aloud)).build())
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = command(SpeechCommand.Play)
                override fun onPause() = command(SpeechCommand.Pause)
                override fun onSkipToPrevious() = command(SpeechCommand.Previous)
                override fun onSkipToNext() = command(SpeechCommand.Next)
                override fun onStop() = command(SpeechCommand.Stop)
                override fun onCustomAction(action: String, extras: Bundle?) {
                    if (action == SpeechCommand.Stop.name) command(SpeechCommand.Stop)
                }
            }, handler)
            isActive = true
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE)

    private fun notification(): Notification {
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        media?.let { style.setMediaSession(it.sessionToken) }
        val playing = request?.state?.value?.phase != SpeechPhase.Paused
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(getString(R.string.read_aloud)).setContentIntent(openApp())
            .setCategory(Notification.CATEGORY_TRANSPORT).setOnlyAlertOnce(true).setStyle(style)
        fun add(command: SpeechCommand, icon: Int, label: Int) {
            val token = request?.token
            val action = Intent(this, ReadAloudService::class.java).setAction(command.name)
                .setIdentifier(token).putExtra(TOKEN, token)
            val sender = PendingIntent.getService(this, command.ordinal + 1, action, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon),
                getString(label), sender).build())
        }
        add(SpeechCommand.Previous, android.R.drawable.ic_media_previous, R.string.speech_previous)
        if (playing) add(SpeechCommand.Pause, android.R.drawable.ic_media_pause, R.string.speech_pause)
        else add(SpeechCommand.Play, android.R.drawable.ic_media_play, R.string.speech_resume)
        add(SpeechCommand.Next, android.R.drawable.ic_media_next, R.string.speech_next)
        add(SpeechCommand.Stop, android.R.drawable.ic_menu_close_clear_cancel, R.string.speech_stop)
        return builder.build()
    }

    private fun publish(phase: SpeechPhase, rate: Float = request?.state?.value?.rate ?: 1f) {
        request?.state?.value = SpeechState(phase, rate)
        val state = when (phase) {
            SpeechPhase.Starting -> PlaybackState.STATE_BUFFERING
            SpeechPhase.Playing -> PlaybackState.STATE_PLAYING
            SpeechPhase.Paused -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_STOPPED
        }
        val stop = PlaybackState.CustomAction.Builder(SpeechCommand.Stop.name, getString(R.string.speech_stop),
            android.R.drawable.ic_menu_close_clear_cancel).build()
        media?.setPlaybackState(PlaybackState.Builder().setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN,
            if (state == PlaybackState.STATE_PLAYING) rate else 0f)
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .addCustomAction(stop).build())
        updateMediaNotification()
    }

    // Android 13 exempts notifications attached to a media session from POST_NOTIFICATIONS.
    @SuppressLint("NotificationPermission")
    private fun updateMediaNotification() {
        if (media != null) notifications.notify(ID, notification())
    }

    private fun command(command: SpeechCommand, rate: Float = 1f) {
        val run = request ?: return
        val phase = run.state.value.phase
        when (command) {
            SpeechCommand.Play -> if (phase == SpeechPhase.Paused) {
                interrupt()
                publish(SpeechPhase.Starting)
                play()
            }
            SpeechCommand.Pause -> if (phase != SpeechPhase.Paused) {
                interrupt()
                publish(SpeechPhase.Paused)
            }
            SpeechCommand.Previous, SpeechCommand.Next -> {
                interrupt()
                play(command, paused = phase == SpeechPhase.Paused)
            }
            SpeechCommand.Stop -> {
                // The reader stopped by hand: the next run probes the server anew. The write
                // survives the scope's cancellation, or a quick destroy would lose it.
                scope.launch {
                    withContext(NonCancellable) {
                        ReaderPreferences.get(applicationContext).update { it.copy(externalTtsDetected = "") }
                    }
                }
                finish(SpeechPhase.Ended)
            }
            SpeechCommand.Rate -> if (rate.isFinite() && rate in 0.5f..2f) {
                publish(phase, rate)
                if (phase != SpeechPhase.Paused) { interrupt(); play() }
            }
        }
    }

    private fun restartAfterPageTurn(run: SpeechRequest) {
        if (request !== run) return
        val paused = run.state.value.phase == SpeechPhase.Paused
        interrupt(keepFocus = !paused)
        play(paused = paused)
    }

    private fun play(move: SpeechCommand? = null, paused: Boolean = false) {
        val run = request ?: return
        job = scope.launch {
            run.pageTurn?.let { turn ->
                val target = turn.await()
                run.pageTurn = null
                if (target != null) {
                    cursor = target
                    current = null
                }
            }
            val at = cursor ?: return@launch finish(SpeechPhase.Ended)
            val previousSpan = current
            val destination = when (move) {
                SpeechCommand.Previous -> run.previous(previousSpan?.range?.start ?: at) ?: at
                SpeechCommand.Next -> (previousSpan ?: run.load(at, limit))?.next
                    ?: return@launch finish(SpeechPhase.Ended, previousSpan?.range?.end)
                else -> at
            }
            var span = run.load(destination, limit) ?: return@launch finish(SpeechPhase.Ended)
            current = span
            cursor = span.range.start
            if (!run.moveTo(span.range.start)) {
                Log.w(TAG, "the reader refused the move to the speech position")
                return@launch finish(SpeechPhase.Failed)
            }
            if (paused) return@launch publish(SpeechPhase.Paused)
            publish(SpeechPhase.Starting)
            val engine = getEngine(run) ?: return@launch finish(SpeechPhase.Failed).also { Log.w(TAG, "no engine was made for the session") }
            Log.i(TAG, "speaking with ${engine.javaClass.simpleName} for ${run.language}")
            if (!engine.prepare(run.language)) {
                engine.failure?.let { return@launch finish(SpeechPhase.Failed, it) }
                Log.w(TAG, "no installed offline voice for ${run.language}")
                return@launch finish(SpeechPhase.NoVoice)
            }
            if (!takeFocus()) {
                Log.w(TAG, "audio focus was not granted")
                return@launch finish(SpeechPhase.Failed)
            }
            if (!engine.setRate(run.state.value.rate)) {
                Log.w(TAG, "the engine refused the rate ${run.state.value.rate}")
                return@launch finish(SpeechPhase.Failed)
            }
            while (true) {
                wake.acquire(UTTERANCE_TIMEOUT)
                cursor = span.range.start
                current = span
                publish(SpeechPhase.Playing)
                // While this span is being spoken, the next one is found and its first sentence
                // is synthesized: the spans follow one another with no silence between.
                val upcoming = span.next?.let { async { run.load(it, limit) } }
                val prefetch = upcoming?.let { ahead -> async { ahead.await()?.let { engine.prefetch(it.range.text) } } }
                if (!speak(run, engine, span)) return@launch finish(SpeechPhase.Failed)
                val next = span.next ?: return@launch finish(SpeechPhase.Ended, span.range.end)
                cursor = next
                current = null
                span = upcoming?.await() ?: return@launch finish(SpeechPhase.Failed)
                if (!run.moveTo(span.range.start)) return@launch finish(SpeechPhase.Failed)
                // The fetched sentence is wanted by the very next speak; wait for it to arrive.
                prefetch?.join()
            }
        }
    }

    private fun getEngine(run: SpeechRequest): AloudEngine? {
        engine?.let { return it }
        return engineFactory(applicationContext, run)?.also { engine = it }
    }

    private suspend fun speak(run: SpeechRequest, engine: AloudEngine, span: ReadAloudSpan): Boolean = coroutineScope {
        val range = span.range
        if (range.text.isBlank()) return@coroutineScope true
        val round = speechRound
        val map = run.mapTiming
        val timing = map?.let { SpokenTiming(range.text, engine.locale ?: Locale.forLanguageTag(run.language)) }
        val whole = SpeechChunk(range.start, range.end)
        run.state.value = run.state.value.copy(chunk = whole)
        var worker: Job? = null
        try {
            if (timing != null && map != null) worker = launch {
                combine(timing.sentence, run.timingEnabled) { offsets, enabled -> offsets.takeIf { enabled } }
                    .distinctUntilChanged().collectLatest { offsets ->
                        val mapped = if (offsets == null) null else try {
                            map(span, offsets.first, offsets.last + 1)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: RuntimeException) {
                            null // An unavailable highlight must not stop speech.
                        }
                        if (speechRound == round && request === run &&
                            (offsets == null || offsets == timing.sentence.value)
                        ) {
                            run.state.value = run.state.value.copy(chunk = mapped ?: whole)
                        }
                    }
            }
            withTimeoutOrNull(UTTERANCE_TIMEOUT) {
                engine.speak(range.text) { start, end -> timing?.offer(start, end) }
            } == true
        } finally {
            worker?.cancel()
            if (speechRound == round) clearChunk(run)
        }
    }

    private fun clearChunk(run: SpeechRequest) {
        val state = run.state.value
        if (state.chunk != null) run.state.value = state.copy(chunk = null)
    }

    private fun takeFocus(): Boolean {
        if (focus != null) return true
        val owner = request
        val focusToken = Any()
        val candidate = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener({ change ->
                if (request === owner && focusOwner === focusToken && change < 0) command(SpeechCommand.Pause)
            }, handler).build()
        if (audio.requestAudioFocus(candidate) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focus = candidate
        focusOwner = focusToken
        return true
    }

    private fun interrupt(keepFocus: Boolean = false) {
        job?.cancel()
        job = null
        speechRound++
        request?.let(::clearChunk)
        engine?.stop()
        if (!keepFocus) {
            focusOwner = null
            focus?.let { audio.abandonAudioFocusRequest(it) }
            focus = null
        }
        if (wake.isHeld) wake.release()
    }

    private suspend fun finish(phase: SpeechPhase, end: String?) {
        if (end != null) request?.moveTo?.invoke(end)
        finish(phase)
    }

    private fun finish(phase: SpeechPhase, note: SpeechNote? = null) {
        if (phase == SpeechPhase.Failed) Log.w(TAG, if (note == null) "speech failed" else "speech failed: $note")
        request?.state?.value = SpeechState(phase, note = note)
        clearSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun clearSession() {
        interrupt()
        engine?.release()
        engine = null
        media?.release()
        media = null
        if (receiverRegistered) { unregisterReceiver(noisy); receiverRegistered = false }
        request?.dispatch = null
        request?.followPageTurn = null
        request?.pageTurn = null
        request = null
        cursor = null
        current = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) { finish(SpeechPhase.Ended) }
    override fun onDestroy() {
        request?.state?.value = SpeechState(SpeechPhase.Ended)
        clearSession()
        scope.cancel()
        super.onDestroy()
    }

    internal companion object {
        private const val CHANNEL = "read-aloud"
        private const val START = "read-aloud-start"
        private const val TOKEN = "session"
        private const val ID = 155
        private const val UTTERANCE_TIMEOUT = 720_000L
        private val limit get() = minOf(2000, TextToSpeech.getMaxSpeechInputLength())
        // Main-thread only. A stopped or replaced request cannot be resurrected by a queued Intent.
        private var pending: SpeechRequest? = null

        /** Which engine a session speaks with. A seam for tests; the default picks by the session's own config. */
        internal var engineFactory: (Context, SpeechRequest) -> AloudEngine? = { context, run ->
            if (run.externalTts != null) HttpTtsEngine(context, run.externalTts) else AndroidTtsEngine(context)
        }

        fun start(context: Context, run: SpeechRequest) {
            pending = run
            try {
                ContextCompat.startForegroundService(context, Intent(context, ReadAloudService::class.java)
                    .setAction(START).putExtra(TOKEN, run.token))
            } catch (_: RuntimeException) {
                pending = null
                run.state.value = SpeechState(SpeechPhase.Failed)
            }
        }

        fun stop(run: SpeechRequest) {
            if (pending === run) pending = null
            run.dispatch?.invoke(SpeechCommand.Stop, 1f)
        }

        internal fun localVoice(voices: Set<Voice>, default: Voice?, language: String): Voice? {
            val tag = language.trim().replace('_', '-')
            val wanted = Locale.forLanguageTag(tag)
            val installed = voices.filter { !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            val matches = if (wanted.language.isBlank() || wanted.language == "und") installed else installed.filter { it.locale.language == wanted.language }
            return matches.firstOrNull { it == default && (wanted.country.isBlank() || it.locale.country == wanted.country) }
                ?: matches.sortedWith(compareByDescending<Voice> { it.locale == wanted }.thenByDescending { it.quality }.thenBy { it.name }).firstOrNull()
        }
    }
}
