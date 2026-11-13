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
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDate
import java.util.Locale

/**
 * Voice notes for a day's journal: recorded as WAV files on the phone (in voice/, named by day and
 * time), and written out as text by the phone's own speech recognition, from the file, so the note can
 * be searched. The recordings stay on the phone; only the text goes into backups.
 */
object VoiceNotes {

    const val RATE = 16_000
    const val MAX_MS = 3 * 60_000L

    fun canRecord(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Whether recordings can be written out as text here (Android 13 on, with a speech recogniser) */
    fun canTranscribe(context: Context) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        (SpeechRecognizer.isOnDeviceRecognitionAvailable(context) || SpeechRecognizer.isRecognitionAvailable(context))

    private fun folder(context: Context) = File(context.filesDir, "voice").apply { mkdirs() }

    /** [date]'s recordings, oldest first */
    fun files(context: Context, date: LocalDate): List<File> =
        folder(context).listFiles { f -> f.name.startsWith("$date ") && f.name.endsWith(".wav") }.orEmpty().sortedBy { it.name }

    /** Every day with a recording */
    fun dates(context: Context): Set<LocalDate> = folder(context).listFiles().orEmpty()
        .mapNotNull { runCatching { LocalDate.parse(it.name.substringBefore(' ')) }.getOrNull() }.toSet()

    /** How long a recording is, from its size */
    fun lengthMs(file: File) = maxOf(0L, file.length() - 44) * 1000 / (RATE * 2)

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
            return file.takeIf { lengthMs(it) >= 500 } ?: run { file.delete(); null }
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return done(null)
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
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish() }, lengthMs(file) / 2 + 60_000L)
    }

    fun delete(file: File) = file.delete()
}
