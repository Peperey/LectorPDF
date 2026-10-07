package com.example.lectorpdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

internal class Utt(val gen: Int, val page: Int, val chunk: Int, val last: Boolean)

class ReaderState(
    private val app: Context,
    private val scope: CoroutineScope,
    private val getKey: () -> String
) {
    var isOpen by mutableStateOf(false)
    var title by mutableStateOf("")
    var pageCount by mutableIntStateOf(0)
    var page by mutableIntStateOf(0)
    var speaking by mutableStateOf(false)
    var status by mutableStateOf("")
    var pageText by mutableStateOf("")
    var speed by mutableFloatStateOf(1f)
    var needKey by mutableStateOf(false)

    private var doc: PDDocument? = null
    private var file: File? = null
    private val mutex = Mutex()
    private val cache = HashMap<Int, String>()
    private var tts: TextToSpeech? = null
    private var ttsOk = false
    @Volatile private var gen = 0
    private var job: Job? = null
    private var prefetchJob: Job? = null
    private var chunks: List<String> = emptyList()
    private var chunksPage = -1
    @Volatile private var curChunk = 0

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val u = parse(utteranceId) ?: return
            if (u.gen == gen) curChunk = u.chunk
        }

        override fun onDone(utteranceId: String?) {
            val u = parse(utteranceId) ?: return
            if (u.gen != gen || !u.last) return
            scope.launch(Dispatchers.Main) {
                if (u.gen == gen && speaking) nextPageAuto()
            }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) {
            scope.launch(Dispatchers.Main) {
                speaking = false
                status = "Error de la voz del celular"
            }
        }
    }

    init {
        tts = TextToSpeech(app) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val t = tts
                if (t != null) {
                    var r = t.setLanguage(Locale("es", "MX"))
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        r = t.setLanguage(Locale("es"))
                    }
                    ttsOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                    t.setOnUtteranceProgressListener(listener)
                    if (!ttsOk) status = "Falta la voz en español. Instálala en Ajustes > Salida de texto a voz."
                }
            } else {
                status = "No se pudo iniciar la voz del celular"
            }
        }
    }

    private fun parse(id: String?): Utt? {
        val p = id?.split(":") ?: return null
        if (p.size < 4) return null
        return try {
            Utt(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3] == "1")
        } catch (e: Exception) {
            null
        }
    }

    fun openUri(uri: Uri) {
        scope.launch {
            try {
                status = "Abriendo…"
                val f = withContext(Dispatchers.IO) {
                    var name = "documento.pdf"
                    app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (i >= 0) name = c.getString(i)
                        }
                    }
                    val out = File(app.cacheDir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                    val input = app.contentResolver.openInputStream(uri) ?: throw Exception("No se pudo abrir el archivo")
                    input.use { inp -> out.outputStream().use { o -> inp.copyTo(o) } }
                    out
                }
                openPdf(f)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                status = "No pude abrir el PDF: ${e.message}"
            }
        }
    }

    fun openPdf(src: File) {
        pause()
        isOpen = true
        title = src.nameWithoutExtension
        status = "Abriendo…"
        pageText = ""
        page = 0
        pageCount = 0
        cache.clear()
        chunks = emptyList()
        chunksPage = -1
        curChunk = 0
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    mutex.withLock {
                        doc?.close()
                        doc = PDDocument.load(src)
                        file = src
                    }
                }
                pageCount = doc?.numberOfPages ?: 0
                if (pageCount == 0) {
                    status = "El PDF no tiene páginas"
                    return@launch
                }
                status = ""
                play()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                status = "No pude abrir el PDF: ${e.message}"
            }
        }
    }

    private suspend fun textOf(p: Int, quiet: Boolean): String = mutex.withLock {
        val c = cache[p]
        if (c != null) return@withLock c
        val d = doc ?: throw Exception("No hay PDF abierto")
        var t = withContext(Dispatchers.IO) {
            val s = PDFTextStripper()
            s.setSortByPosition(true)
            s.setStartPage(p + 1)
            s.setEndPage(p + 1)
            s.getText(d)
        }
        if (t.trim().length < 15) t = ocr(p, quiet)
        cache[p] = t
        t
    }

    private suspend fun ocr(p: Int, quiet: Boolean): String {
        val key = getKey()
        if (key.isBlank()) {
            if (!quiet) needKey = true
            throw Exception("La página ${p + 1} es una imagen (escaneada). Necesito la API key de Groq para leerla.")
        }
        if (!quiet) status = "Leyendo página ${p + 1} con IA…"
        val jpeg = withContext(Dispatchers.IO) { renderJpeg(p) }
        return ocrJpeg(key, jpeg) { if (!quiet) status = it }
    }

    private fun renderJpeg(p: Int): ByteArray {
        val f = file ?: throw Exception("No hay PDF abierto")
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val r = PdfRenderer(pfd)
            try {
                val pg = r.openPage(p)
                try {
                    val w = 1400
                    val h = maxOf(1, (w.toLong() * pg.height / pg.width).toInt())
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val bo = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, bo)
                    return bo.toByteArray()
                } finally {
                    pg.close()
                }
            } finally {
                r.close()
            }
        } finally {
            pfd.close()
        }
    }

    private suspend fun loadChunks() {
        val t = textOf(page, false)
        pageText = t.trim()
        chunks = speechChunks(t)
        chunksPage = page
    }

    fun play() {
        if (!isOpen || pageCount == 0) return
        if (!ttsOk) {
            status = "La voz aún no está lista. Intenta de nuevo en un momento."
            return
        }
        tts?.setSpeechRate(speed)
        speaking = true
        gen++
        val g = gen
        job?.cancel()
        job = scope.launch { speakLoop(g) }
    }

    private suspend fun speakLoop(g: Int) {
        try {
            while (speaking && g == gen) {
                if (chunksPage != page) {
                    curChunk = 0
                    status = "Preparando página ${page + 1}…"
                    loadChunks()
                }
                if (g != gen) return
                status = ""
                if (chunks.isEmpty()) {
                    if (page + 1 < pageCount) {
                        page++
                        curChunk = 0
                        continue
                    }
                    speaking = false
                    status = "Terminé el documento"
                    return
                }
                val t = tts ?: return
                val from = curChunk.coerceIn(0, chunks.size - 1)
                val pg = page
                for (i in from until chunks.size) {
                    val last = i == chunks.size - 1
                    val id = "$g:$pg:$i:${if (last) 1 else 0}"
                    val mode = if (i == from) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    t.speak(chunks[i], mode, null, id)
                }
                prefetch(pg + 1)
                return
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            speaking = false
            status = e.message ?: "Error al leer"
        }
    }

    private fun prefetch(p: Int) {
        if (p >= pageCount || cache.containsKey(p)) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            try {
                textOf(p, true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    private fun nextPageAuto() {
        if (page + 1 < pageCount) {
            page++
            curChunk = 0
            play()
        } else {
            speaking = false
            status = "Terminé el documento"
        }
    }

    fun pause() {
        speaking = false
        gen++
        job?.cancel()
        tts?.stop()
    }

    fun goTo(p: Int) {
        if (!isOpen || p < 0 || p >= pageCount) return
        val was = speaking
        gen++
        job?.cancel()
        tts?.stop()
        page = p
        curChunk = 0
        chunksPage = -1
        if (was) {
            play()
        } else {
            speaking = false
            scope.launch {
                try {
                    loadChunks()
                    status = ""
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    status = e.message ?: "Error al leer"
                }
            }
        }
    }

    fun close() {
        pause()
        isOpen = false
        status = ""
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                doc?.close()
                doc = null
            }
        }
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
    }
}
