package app.olauncher.helper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.core.content.ContextCompat
import app.olauncher.R
import app.olauncher.data.MoodStore
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Speaking a day's note rather than typing it. What's said is recorded to a file only for as long as it
 * takes to write it out: the phone's own speech recognition turns it into words, [Transcript] tidies
 * them (and [AiTidy] too, with a key), they go on the end of the day's note, and the recording is
 * deleted. A recording that couldn't be written out is kept until it is ([pending]), or thrown away.
 */
object VoiceNotes {

    const val RATE = 16_000
    const val MAX_MS = 30 * 60_000L

    fun canRecord(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Whether recordings can be written out as text here (Android 13 on, with a speech recogniser) */
    fun canTranscribe(context: Context) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        (SpeechRecognizer.isOnDeviceRecognitionAvailable(context) || SpeechRecognizer.isRecognitionAvailable(context))

    @Volatile private var moved = false

    /** Where recordings wait to be written out; set up for dictation the first time it's needed */
    fun folder(context: Context): File {
        val dir = File(context.filesDir, "voice").apply { mkdirs() }
        if (!moved) synchronized(this) {
            if (!moved) {
                moveToDictation(context, dir)
                moved = true
            }
        }
        return dir
    }

    /**
     * Voice notes used to be kept. Ones never written out (noted as failed, or on a day with no note,
     * as the old voice notes list judged it) stay, as [pending]. Ones already written out (compressed,
     * or WAVs not compressed yet) are put aside, untouched, in voice/old. Half-copied files go. Once,
     * before any new recording is made.
     */
    private fun moveToDictation(context: Context, dir: File) {
        val prefs = context.applicationContext.getSharedPreferences("voice_notes", Context.MODE_PRIVATE)
        if (prefs.getBoolean(DICTATION, false)) return
        val failed = prefs.getStringSet("failed", emptySet()).orEmpty()
        val days = MoodStore(context).load().days
        val old = File(dir, OLD)
        dir.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
            val noNote = days[file.name.substringBefore(' ')]?.note.isNullOrBlank()
            when {
                file.name.endsWith(".part") -> file.delete()
                file.name.endsWith(".wav") && (file.name in failed || noNote) -> Unit
                file.name.endsWith(".wav") || file.name.endsWith(".m4a") -> {
                    old.mkdirs()
                    file.renameTo(File(old, file.name))
                }
            }
        }
        prefs.edit().clear().putBoolean(DICTATION, true).apply()
    }

    /** Where voice notes kept before dictation are put aside */
    private const val OLD = "old"

    private const val DICTATION = "dictation"

    /** The day a recording is for, from its name ("2026-11-17 1763350000000.wav") */
    fun dateOf(file: File): LocalDate? = runCatching { LocalDate.parse(file.name.substringBefore(' ')) }.getOrNull()

    /** When a recording was started, from its name */
    fun startedAt(file: File): Long = file.name.substringAfter(' ').substringBefore('.').toLongOrNull() ?: file.lastModified()

    /** How long a recording is, from its size */
    fun wavMs(file: File) = maxOf(0L, file.length() - 44) * 1000 / (RATE * 2)

    /** Recordings being written out right now */
    private val working = mutableSetOf<String>()

    /** Recordings that couldn't be written out, oldest first; not those being written out now */
    fun pending(context: Context): List<File> = folder(context)
        .listFiles { f -> f.name.endsWith(".wav") && f.name !in working && dateOf(f) != null }.orEmpty().sortedBy { it.name }

    /** Throws away a recording that couldn't be written out */
    fun delete(file: File) {
        file.delete()
        pendingListeners.toList().forEach { it() }
    }

    /**
     * Records to a new file for [date] until [stop]ped; [cancel] throws it away. [paused] turns the mic
     * off for a while without ending the recording; only the time spent recording counts to [MAX_MS].
     */
    class Recorder(context: Context, date: LocalDate) {
        val file = File(folder(context), "$date ${System.currentTimeMillis()}.wav")
        @Volatile private var running = true
        private var thread: Thread? = null

        /** Paused: the mic is off and nothing is written until it's unpaused */
        @Volatile var paused = false

        /** Bytes of sound written so far */
        @Volatile private var written = 0L

        /** How loud the last moment was, 0 to 1 (0 while paused) */
        @Volatile var level = 0f
            private set

        /** How long has been recorded, pauses left out */
        val recordedMs get() = written * 1000 / (RATE * 2)

        /** Whether it stopped by itself, having reached [MAX_MS] */
        val full get() = recordedMs >= MAX_MS

        init {
            synchronized(working) { working += file.name }
        }

        @SuppressLint("MissingPermission")
        fun start(): Boolean {
            val size = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE / 5)
            val record = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size) }
                .getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            if (record == null) {
                synchronized(working) { working -= file.name }
                return false
            }
            thread = Thread {
                runCatching {
                    RandomAccessFile(file, "rw").use { out ->
                        out.setLength(0)
                        out.write(ByteArray(44))
                        // Small reads, so the level moves smoothly
                        val buffer = ByteArray(RATE / 10)
                        var on = false
                        while (running && !full) {
                            if (paused) {
                                if (on) { record.stop(); on = false }
                                level = 0f
                                Thread.sleep(40)
                                continue
                            }
                            if (!on) { record.startRecording(); on = true }
                            val read = record.read(buffer, 0, buffer.size)
                            if (read > 0) {
                                out.write(buffer, 0, read)
                                written += read
                                level = kotlin.math.sqrt(peakOf(buffer, read) / 32768f).coerceIn(0f, 1f)
                            }
                        }
                        if (on) record.stop()
                        level = 0f
                        writeHeader(out, (out.length() - 44).toInt())
                    }
                }
                record.release()
            }.also { it.start() }
            return true
        }

        /** Stops and waits for the file to be finished; null (and no file) if it was too short. Off the main thread. */
        fun stop(): File? {
            running = false
            thread?.join(3000)
            val kept = file.takeIf { wavMs(it) >= 500 } ?: run { file.delete(); null }
            // Still marked as being worked on if kept: [writeOut] takes it from here
            if (kept == null) synchronized(working) { working -= file.name }
            return kept
        }

        /** Off the main thread */
        fun cancel() {
            running = false
            thread?.join(3000)
            file.delete()
            synchronized(working) { working -= file.name }
        }

        private fun writeHeader(out: RandomAccessFile, dataSize: Int) {
            fun int(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            fun short(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
            out.seek(0)
            out.write("RIFF".toByteArray()); out.write(int(36 + dataSize)); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); out.write(int(16)); out.write(short(1)); out.write(short(1))
            out.write(int(RATE)); out.write(int(RATE * 2)); out.write(short(2)); out.write(short(16))
            out.write("data".toByteArray()); out.write(int(dataSize))
        }
    }

    // --- Writing out ---

    /** How long the recogniser has to finish once the whole recording has been fed in */
    private const val FINISH_MS = 90_000L

    /** Quieter than this (of full scale) counts as silence when shortening pauses */
    private const val QUIET = 900

    /** Pauses are cut down to this long before being written out; enough to still end a sentence there */
    private const val KEPT_PAUSE_BYTES = RATE * 2 * 9 / 10

    /**
     * Writes [file] out with the phone's speech recognition, fed from the file, and hands [done] each
     * stretch of speech it heard: null if it couldn't, or stopped before the end (an error, or taking
     * too long), so nothing is half written out. Long pauses are shortened first, so thinking time
     * doesn't slow it down. [formatted] asks the recogniser to punctuate (Android 13 on). Call on the
     * main thread.
     */
    private fun transcribe(context: Context, file: File, formatted: Boolean, done: (List<String>?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return done(null)
        val recognizer = runCatching {
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull() ?: return done(null)
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return done(null).also { recognizer.destroy() }
        val main = Handler(Looper.getMainLooper())
        val parts = mutableListOf<String>()
        var finished = false
        // Every bit of the file has gone to the recogniser
        val fedAll = java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var giveUp: Runnable
        fun finish(complete: Boolean) {
            if (finished) return
            finished = true
            main.removeCallbacks(giveUp)
            runCatching { pipe[0].close() }
            recognizer.destroy()
            done(parts.takeIf { complete && it.isNotEmpty() })
        }
        giveUp = Runnable { finish(complete = false) }
        fun take(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { parts += it }
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onSegmentResults(segmentResults: Bundle) = take(segmentResults)
            // Ending before the whole file went in (a recogniser that stops at the first pause) is a failure
            override fun onEndOfSegmentedSession() = finish(complete = fedAll.get())
            override fun onResults(results: Bundle?) {
                take(results)
                finish(complete = fedAll.get())
            }
            // Hearing nothing in the last stretch, once it has all been fed in, is the end; anything else is a failure
            override fun onError(error: Int) = finish(complete = fedAll.get() &&
                (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
            // The whole recording, not just up to the first pause
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            // Punctuation and capitals, where the recogniser can
            if (formatted) putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        }
        runCatching { recognizer.startListening(intent) }.onFailure { return finish(complete = false) }
        // The file's sound, after its header, into the pipe at about twice the speed it was spoken (a
        // tenth of a second every 50ms), with long pauses cut short
        Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    file.inputStream().buffered().use { input ->
                        input.skip(44)
                        val chunk = ByteArray(RATE / 5)
                        var quietBytes = 0
                        while (true) {
                            val read = input.read(chunk)
                            if (read <= 0) break
                            // Silence beyond the kept pause is dropped
                            if (peakOf(chunk, read) < QUIET) {
                                quietBytes += read
                                if (quietBytes > KEPT_PAUSE_BYTES) continue
                            } else quietBytes = 0
                            out.write(chunk, 0, read)
                            Thread.sleep(50)
                        }
                        // A moment of quiet so the last words are heard out
                        out.write(ByteArray(RATE))
                        fedAll.set(true)
                        // All of it is in: the recogniser has a while to finish, rather than as long again
                        main.post {
                            if (!finished) {
                                main.removeCallbacks(giveUp)
                                main.postDelayed(giveUp, FINISH_MS)
                            }
                        }
                    }
                }
            }
        }.start()
        // In case the recogniser never says it's done: twice as long as speaking it took, and a couple of minutes
        main.postDelayed(giveUp, wavMs(file) * 2 + 120_000L)
    }

    /** The loudest 16-bit sample in the first [read] bytes of [buffer], 0 to 32768 */
    private fun peakOf(buffer: ByteArray, read: Int): Int {
        var max = 0
        var i = 0
        while (i + 1 < read) {
            val sample = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort().toInt()
            max = maxOf(max, kotlin.math.abs(sample))
            i += 2
        }
        return max
    }

    /** Told when words are added to a day's note, so a page showing that day's note can show them */
    private val listeners = mutableListOf<(LocalDate, String) -> Unit>()

    fun addListener(listener: (LocalDate, String) -> Unit) { listeners += listener }
    fun removeListener(listener: (LocalDate, String) -> Unit) { listeners -= listener }

    /** Told when [pending] changes */
    private val pendingListeners = mutableListOf<() -> Unit>()

    fun addPendingListener(listener: () -> Unit) { pendingListeners += listener }
    fun removePendingListener(listener: () -> Unit) { pendingListeners -= listener }

    private val main = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()

    /** Recordings waiting their turn: one is written out at a time */
    private val queue = ArrayDeque<Triple<LocalDate, File, ((String?) -> Unit)?>>()
    private var busy = false

    /** How many recordings are being written out or waiting to be. Main thread. */
    val writing get() = queue.size + if (busy) 1 else 0

    /**
     * Writes [file] out as tidy text and adds it to the end of [date]'s note, whatever page is open by
     * then, then deletes the recording; [done] gets the words. It gets null if it couldn't be written out
     * (the recording is kept, [pending]) or no words were heard (the recording is deleted). Call on the
     * main thread.
     */
    fun writeOut(context: Context, date: LocalDate, file: File, done: ((String?) -> Unit)? = null) {
        val app = context.applicationContext
        synchronized(working) { working += file.name }
        queue.addLast(Triple(date, file, done))
        pendingListeners.toList().forEach { it() }
        if (!busy) next(app)
    }

    private fun next(app: Context) {
        val (date, file, done) = queue.removeFirstOrNull() ?: run {
            busy = false
            // Nothing being written out now
            pendingListeners.toList().forEach { it() }
            return
        }
        busy = true
        fun failed() {
            synchronized(working) { working -= file.name }
            Toast.makeText(app, R.string.voice_no_text, Toast.LENGTH_LONG).show()
            done?.invoke(null)
            pendingListeners.toList().forEach { it() }
            next(app)
        }
        if (!file.exists()) {
            synchronized(working) { working -= file.name }
            done?.invoke(null)
            return next(app)
        }
        if (!canTranscribe(app)) return failed()
        Toast.makeText(app, R.string.voice_writing, Toast.LENGTH_SHORT).show()
        transcribe(app, file, formatted = true) { first ->
            // A recogniser that can't punctuate may fail outright; it's tried once more as it was
            if (first != null) tidyAndAdd(app, date, file, first, done)
            else transcribe(app, file, formatted = false) { second ->
                if (second != null) tidyAndAdd(app, date, file, second, done) else failed()
            }
        }
    }

    /** [segments] tidied (with Claude too, if it's set up), added to [date]'s note, and the recording deleted */
    private fun tidyAndAdd(app: Context, date: LocalDate, file: File, segments: List<String>, done: ((String?) -> Unit)?) {
        background.execute {
            val local = Transcript.clean(segments)
            val text = if (local.isEmpty()) local
            else AiTidy.tidy(app, local)?.takeIf { Transcript.acceptTidy(local, it) } ?: local
            main.post {
                if (text.isNotEmpty()) {
                    val store = MoodStore(app)
                    val now = store.load().days[date.toString()]?.note.orEmpty().trim()
                    store.setNote(date, if (now.isEmpty()) text else now + "\n\n" + text)
                    listeners.toList().forEach { it(date, text) }
                    JournalSummaries.refreshLater(app, date)
                    Toast.makeText(app, if (date == LocalDate.now()) R.string.voice_added else R.string.voice_added_day, Toast.LENGTH_SHORT).show()
                } else Toast.makeText(app, R.string.voice_nothing_heard, Toast.LENGTH_SHORT).show()
                file.delete()
                synchronized(working) { working -= file.name }
                done?.invoke(text.ifEmpty { null })
                pendingListeners.toList().forEach { it() }
                next(app)
            }
        }
    }
}
