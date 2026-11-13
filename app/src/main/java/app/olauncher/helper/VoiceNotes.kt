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

/**
 * Voice notes for a day's journal: recorded as WAV files on the phone (in voice/, named by day and
 * time), and written out as text by the phone's own speech recognition, from the file, so the note can
 * be searched. Once written out, a recording is squeezed into a small AAC file (.m4a). The backup
 * folder keeps a copy of each in its own voice folder ([BackupManager][app.olauncher.data.BackupManager]).
 */
object VoiceNotes {

    const val RATE = 16_000
    const val MAX_MS = 3 * 60_000L
    private const val BIT_RATE = 24_000

    fun canRecord(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Whether recordings can be written out as text here (Android 13 on, with a speech recogniser) */
    fun canTranscribe(context: Context) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        (SpeechRecognizer.isOnDeviceRecognitionAvailable(context) || SpeechRecognizer.isRecognitionAvailable(context))

    fun folder(context: Context) = File(context.filesDir, "voice").apply { mkdirs() }

    /** Whether [name] is a finished recording (not one being written) */
    fun isRecording(name: String) = name.endsWith(".wav") || name.endsWith(".m4a")

    /** Every finished recording */
    fun all(context: Context): List<File> = folder(context).listFiles { f -> isRecording(f.name) }.orEmpty().toList()

    /** [date]'s recordings, oldest first */
    fun files(context: Context, date: LocalDate): List<File> =
        folder(context).listFiles { f -> f.name.startsWith("$date ") && isRecording(f.name) }.orEmpty().sortedBy { it.name }

    /** Every day with a recording */
    fun dates(context: Context): Set<LocalDate> = all(context)
        .mapNotNull { runCatching { LocalDate.parse(it.name.substringBefore(' ')) }.getOrNull() }.toSet()

    /** How long a recording is: a WAV's from its size, a compressed one's as noted when it was made */
    private fun wavMs(file: File) = maxOf(0L, file.length() - 44) * 1000 / (RATE * 2)

    fun lengthMs(context: Context, file: File): Long {
        if (file.name.endsWith(".wav")) return wavMs(file)
        val prefs = failedPrefs(context)
        val known = prefs.getLong("len:" + file.name, -1L)
        if (known >= 0) return known
        return measure(file).also { prefs.edit().putLong("len:" + file.name, it).apply() }
    }

    /** A compressed file's length, read from the file (slow-ish, so done once and noted) */
    private fun measure(file: File): Long = runCatching {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            extractor.getTrackFormat(0).getLong(android.media.MediaFormat.KEY_DURATION) / 1000
        } finally {
            extractor.release()
        }
    }.getOrDefault(0L)

    /** Notes [file]'s length if it isn't yet; for a recording brought back from the backup. Off the main thread. */
    fun noteLength(context: Context, file: File) {
        if (file.name.endsWith(".m4a") && !failedPrefs(context).contains("len:" + file.name))
            failedPrefs(context).edit().putLong("len:" + file.name, measure(file)).apply()
    }

    // --- Compressing ---

    private val compressLock = Any()

    /** One at a time, off the main thread */
    private val work = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Compresses [wav] soon, then has the backup pick it up */
    fun compressLater(context: Context, wav: File) {
        val appContext = context.applicationContext
        work.execute {
            compress(appContext, wav)
            app.olauncher.data.BackupManager.requestSync(appContext)
        }
    }

    /**
     * Squeezes recordings that are done with: written out, or from before today and not waiting to be
     * written out again. Off the main thread.
     */
    fun tidy(context: Context) {
        val today = LocalDate.now().toString()
        all(context).forEach { file ->
            if (file.name.endsWith(".wav") && !failed(context, file) && file.name.substringBefore(' ') < today) compress(context, file)
        }
    }

    /**
     * [wav] as AAC in an .m4a beside it, replacing it; null if it couldn't (the WAV is kept then).
     * Off the main thread.
     */
    fun compress(context: Context, wav: File): File? = synchronized(compressLock) {
        if (!wav.exists() || !wav.name.endsWith(".wav")) return null
        val out = File(wav.parentFile, wav.name.removeSuffix(".wav") + ".m4a")
        val part = File(wav.parentFile, out.name + ".part")
        var codec: android.media.MediaCodec? = null
        var muxer: android.media.MediaMuxer? = null
        try {
            val format = android.media.MediaFormat.createAudioFormat(android.media.MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
                setInteger(android.media.MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(android.media.MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            codec = android.media.MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = android.media.MediaMuxer(part.path, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            var muxing = false
            val info = android.media.MediaCodec.BufferInfo()
            wav.inputStream().buffered().use { input ->
                input.skip(44)
                var read = 0L
                var inputDone = false
                val chunk = ByteArray(8192)
                while (true) {
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            buffer.clear()
                            val n = input.read(chunk, 0, minOf(chunk.size, buffer.remaining()))
                            val at = read * 1_000_000 / (RATE * 2)
                            if (n <= 0) {
                                codec.queueInputBuffer(index, 0, 0, at, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buffer.put(chunk, 0, n)
                                codec.queueInputBuffer(index, 0, n, at, 0)
                                read += n
                            }
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        index == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxing = true
                        }
                        index >= 0 -> {
                            val data = codec.getOutputBuffer(index)!!
                            if (info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && muxing) {
                                data.position(info.offset)
                                data.limit(info.offset + info.size)
                                muxer.writeSampleData(track, data, info)
                            }
                            codec.releaseOutputBuffer(index, false)
                            if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            }
            val length = maxOf(0L, wav.length() - 44) * 1000 / (RATE * 2)
            codec.stop()
            muxer.stop()
            muxer.release()
            muxer = null
            if (!part.renameTo(out)) throw IllegalStateException("Couldn't keep the compressed file")
            failedPrefs(context).edit().putLong("len:" + out.name, length).apply()
            // The WAV's copy in the backup goes too, if it had one
            forgetInBackup(context, wav.name)
            wav.delete()
            out
        } catch (e: Exception) {
            e.printStackTrace()
            part.delete()
            null
        } finally {
            runCatching { codec?.release() }
            runCatching { muxer?.release() }
        }
    }

    // --- Backing up ---

    /** The recordings the backup folder should have: compressed ones, and WAVs that are staying as they are */
    fun toBackUp(context: Context): List<File> = all(context).filter { it.name.endsWith(".m4a") || failed(context, it) }

    /** Recordings deleted from the phone (or replaced by a compressed one), to delete from the backup too */
    fun deletedFromBackup(context: Context): Set<String> = failedPrefs(context).getStringSet("gone", emptySet()).orEmpty()

    private fun forgetInBackup(context: Context, name: String) {
        val prefs = failedPrefs(context)
        prefs.edit().putStringSet("gone", deletedFromBackup(context) + name).apply()
    }

    /** [names] are gone from the backup folder now */
    fun goneFromBackup(context: Context, names: Set<String>) {
        if (names.isEmpty()) return
        failedPrefs(context).edit().putStringSet("gone", deletedFromBackup(context) - names).apply()
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

        @SuppressLint("MissingPermission")
        fun start(): Boolean {
            val size = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE / 5)
            val record = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size) }
                .getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return false
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
                                level = peak(buffer, read)
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

        /** The loudest sample in [buffer], 0 to 1, eased so quiet speech still shows */
        private fun peak(buffer: ByteArray, read: Int): Float {
            var max = 0
            var i = 0
            while (i + 1 < read) {
                val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                max = maxOf(max, kotlin.math.abs(sample))
                i += 2
            }
            return kotlin.math.sqrt(max / 32768f).coerceIn(0f, 1f)
        }

        /** Stops and waits for the file to be finished */
        fun stop(): File? {
            running = false
            thread?.join(3000)
            return file.takeIf { wavMs(it) >= 500 } ?: run { file.delete(); null }
        }

        fun cancel() {
            running = false
            thread?.join(3000)
            file.delete()
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

    /**
     * Writes [file] out as text with the phone's speech recognition, fed from the file, and hands it to
     * [done] (null if it couldn't). Call on the main thread.
     */
    fun transcribe(context: Context, file: File, done: (String?) -> Unit) {
        // Only a WAV's sound can be fed in as it is; compressed ones have been written out already
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !file.name.endsWith(".wav")) return done(null)
        val recognizer = runCatching {
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull() ?: return done(null)
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return done(null).also { recognizer.destroy() }
        val parts = mutableListOf<String>()
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            runCatching { pipe[0].close() }
            recognizer.destroy()
            done(parts.joinToString(" ").trim().replaceFirstChar { it.uppercase() }.takeIf { it.isNotEmpty() })
        }
        fun take(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { parts += it }
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onSegmentResults(segmentResults: Bundle) = take(segmentResults)
            override fun onEndOfSegmentedSession() = finish()
            override fun onResults(results: Bundle?) {
                take(results)
                finish()
            }
            override fun onError(error: Int) = finish()
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
        }
        runCatching { recognizer.startListening(intent) }.onFailure { return finish() }
        // The file's sound, after its header, into the pipe at about the speed it was spoken
        Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    file.inputStream().use { input ->
                        input.skip(44)
                        val chunk = ByteArray(RATE / 5)
                        while (true) {
                            val read = input.read(chunk)
                            if (read <= 0) break
                            out.write(chunk, 0, read)
                            Thread.sleep(50)
                        }
                        // A moment of quiet so the last words are heard out
                        out.write(ByteArray(RATE))
                    }
                }
            }
        }.start()
        // In case the recogniser never says it's done
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish() }, wavMs(file) / 2 + 60_000L)
    }

    fun delete(context: Context, file: File) {
        file.delete()
        setFailed(context, file, false)
        failedPrefs(context).edit().remove("len:" + file.name).apply()
        forgetInBackup(context, file.name)
        app.olauncher.data.BackupManager.requestSync(context)
    }

    // --- Writing a recording into its day's note ---

    private fun failedPrefs(context: Context) = context.applicationContext.getSharedPreferences("voice_notes", Context.MODE_PRIVATE)

    /** Recordings whose words couldn't be written out */
    fun failed(context: Context, file: File) = file.name in failedPrefs(context).getStringSet("failed", emptySet()).orEmpty()

    private fun setFailed(context: Context, file: File, failed: Boolean) {
        val prefs = failedPrefs(context)
        val set = prefs.getStringSet("failed", emptySet()).orEmpty().toMutableSet()
        if (if (failed) set.add(file.name) else set.remove(file.name)) {
            prefs.edit().putStringSet("failed", set).apply()
            // A WAV that couldn't be written out stays as it is, so it's backed up as it is
            if (failed) app.olauncher.data.BackupManager.requestSync(context)
        }
    }

    /** Told when a recording's words are added to a day's note, so a page showing that day's note can show them */
    private val listeners = mutableListOf<(LocalDate, String) -> Unit>()

    fun addListener(listener: (LocalDate, String) -> Unit) { listeners += listener }
    fun removeListener(listener: (LocalDate, String) -> Unit) { listeners -= listener }

    /**
     * Writes [file] out as text and adds it to the end of [date]'s note, whatever page is open by then;
     * [done] gets the words (null if it couldn't). Call on the main thread.
     */
    fun writeOut(context: Context, date: LocalDate, file: File, done: ((String?) -> Unit)? = null) {
        val app = context.applicationContext
        if (!canTranscribe(app)) {
            setFailed(app, file, true)
            Toast.makeText(app, R.string.voice_no_text, Toast.LENGTH_LONG).show()
            return done?.invoke(null) ?: Unit
        }
        Toast.makeText(app, R.string.voice_writing, Toast.LENGTH_SHORT).show()
        transcribe(app, file) { text ->
            if (text == null) {
                setFailed(app, file, true)
                Toast.makeText(app, R.string.voice_no_text, Toast.LENGTH_LONG).show()
                return@transcribe done?.invoke(null) ?: Unit
            }
            setFailed(app, file, false)
            val store = MoodStore(app)
            val now = store.load().days[date.toString()]?.note.orEmpty().trim()
            store.setNote(date, if (now.isEmpty()) text else now + "\n" + text)
            listeners.toList().forEach { it(date, text) }
            done?.invoke(text)
            compressLater(app, file)
        }
    }
}
