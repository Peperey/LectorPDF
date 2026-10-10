package com.example.lectorpdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    var target by mutableStateOf("")
    var question by mutableStateOf("")
    var answer by mutableStateOf("")
    var asking by mutableStateOf(false)
    var voiceMode by mutableStateOf(app.getSharedPreferences("p", Context.MODE_PRIVATE).getString("voice_mode", "phone") ?: "phone")

    private var doc: PDDocument? = null
    private var file: File? = null
    private val mutex = Mutex()
    private val cache = HashMap<Int, String>()
    private val tcache = HashMap<String, String>()
    private val tmutex = Mutex()
    private val ttsLock = Mutex()
    private val prefs = app.getSharedPreferences("p", Context.MODE_PRIVATE)
    private var qaPages: List<String>? = null
    @Volatile private var resumeChunk = -1
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
            if (u.gen == gen) {
                curChunk = u.chunk
                save()
            }
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
                    applyLang()
                    t.setOnUtteranceProgressListener(listener)
                }
            } else {
                status = "No se pudo iniciar la voz del celular"
            }
        }
    }

    private fun applyLang() {
        val t = tts ?: return
        val en = target == "en"
        var r = t.setLanguage(if (en) Locale.US else Locale("es", "MX"))
        if (!en && (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)) {
            r = t.setLanguage(Locale("es"))
        }
        ttsOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ttsOk) {
            status = if (en) "Falta la voz en inglés. Instálala en Ajustes > Salida de texto a voz."
            else "Falta la voz en español. Instálala en Ajustes > Salida de texto a voz."
        }
    }

    fun chooseTarget(t: String) {
        target = t
        applyLang()
        if (isOpen && pageCount > 0) goTo(page)
    }

    private suspend fun spokenText(p: Int, quiet: Boolean): String {
        val t = textOf(p, quiet)
        val tg = target
        if (tg.isEmpty() || t.isBlank()) return t
        val k = "$p:$tg"
        val c = tcache[k]
        if (c != null) return c
        return tmutex.withLock {
            val c2 = tcache[k]
            if (c2 != null) return@withLock c2
            val key = getKey()
            if (key.isBlank()) {
                if (!quiet) needKey = true
                throw Exception("Para traducir necesito la API key de Groq.")
            }
            if (!quiet) status = "Traduciendo página ${p + 1}…"
            val tr = translateText(key, t, langName(tg)) { if (!quiet) status = it }
            tcache[k] = tr
            tr
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
        tcache.clear()
        target = ""
        question = ""
        answer = ""
        qaPages = null
        resumeChunk = -1
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
                val pos = readPos(prefs, src)
                if (pos != null && pos.page in 0 until pageCount) {
                    page = pos.page
                    target = pos.target
                    resumeChunk = pos.chunk
                    status = "Continúo en la página ${pos.page + 1}"
                } else {
                    status = ""
                }
                applyLang()
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


    // ---------- Voz clonada (servidor) ----------
    private fun remoteCfg(): Remote? {
        val gk = (prefs.getString("gr_key", "") ?: "").trim()
        if (gk.isNotEmpty()) {
            // Si no pones el ID de tu voz, usa una voz de ejemplo en español (Valentina, México).
            val gv = (prefs.getString("gr_voice", "") ?: "").trim().ifEmpty { "B36pbz5_UoWn4BDl" }
            return Remote("https://api.gradium.ai/api", gv, gk, "gradium")
        }
        var u = (prefs.getString("srv_url", "") ?: "").trim().trimEnd('/').removeSuffix("/v1")
        if (u.isEmpty()) return null
        if (!u.startsWith("http")) u = "http://$u"
        val v = (prefs.getString("srv_voice", "") ?: "").trim()
        val k = (prefs.getString("srv_key", "") ?: "").trim()
        return Remote(u, v, k)
    }

    private fun countUse(cfg: Remote, n: Int) {
        if (cfg.kind == "gradium") addUsage(prefs, n)
    }

    fun chooseVoice(m: String) {
        val was = speaking
        pause()
        voiceMode = m
        prefs.edit().putString("voice_mode", m).apply()
        if (m == "server" && remoteCfg() == null) {
            status = "Falta configurar el servidor de voz. Toca 🔑 en la pantalla de inicio."
            return
        }
        if (was) play()
    }

    fun testServer() {
        scope.launch {
            try {
                val cfg = remoteCfg() ?: throw Exception("Escribe primero la dirección del servidor.")
                status = "Probando la voz del servidor…"
                val f = fetchLimited(cfg, "Hola, esta es mi voz. Ya puedo leer tus documentos.")
                status = "Prueba lista: escucha la voz."
                playFile(f)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                status = "No pude conectar con el servidor de voz: ${e.message}"
            }
        }
    }

    // Una sola petición a la vez al servidor de voz (el plan gratis limita las sesiones simultáneas).
    // Si aun así responde "Concurrency limit", espera un momento y reintenta.
    private suspend fun fetchLimited(cfg: Remote, text: String): File {
        ttsLock.lock()
        try {
            var last: Exception? = null
            for (attempt in 0..5) {
                try {
                    val f = withContext(Dispatchers.IO) { fetchSpeech(cfg, text, app.cacheDir) }
                    countUse(cfg, text.length)
                    return f
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    last = e
                    val m = e.message ?: ""
                    if (!m.contains("oncurrency")) throw e
                    delay(1500L * (attempt + 1))
                }
            }
            throw last ?: Exception("El servidor de voz tiene el límite de sesiones simultáneas. Espera unos segundos y toca Leer.")
        } finally {
            ttsLock.unlock()
        }
    }

    private suspend fun playFile(f: File) = suspendCancellableCoroutine<Unit> { cont ->
        val mp = MediaPlayer()
        cont.invokeOnCancellation {
            try { mp.release() } catch (e: Exception) { }
            f.delete()
        }
        try {
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener {
                mp.release()
                f.delete()
                if (cont.isActive) cont.resume(Unit)
            }
            mp.setOnErrorListener { _, _, _ ->
                mp.release()
                f.delete()
                if (cont.isActive) cont.resumeWithException(Exception("No pude reproducir el audio del servidor"))
                true
            }
            mp.setOnPreparedListener { p ->
                try {
                    p.playbackParams = p.playbackParams.setSpeed(speed)
                } catch (e: Exception) { }
                p.start()
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            try { mp.release() } catch (e2: Exception) { }
            f.delete()
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    // Genera el audio de cada pedazo en el servidor (el siguiente se prepara mientras suena el actual).
    private suspend fun playRemoteList(g: Int, list: List<String>, from: Int, onChunk: (Int) -> Unit) {
        val cfg = remoteCfg() ?: throw Exception("Falta configurar el servidor de voz. Toca 🔑 en la pantalla de inicio.")
        if (list.isEmpty() || from >= list.size) return
        coroutineScope {
            var next: Deferred<File> = async { fetchLimited(cfg, list[from]) }
            var i = from
            while (i < list.size && g == gen) {
                status = "Generando voz…"
                val file = next.await()
                status = ""
                if (i + 1 < list.size) {
                    val j = i + 1
                    next = async { fetchLimited(cfg, list[j]) }
                }
                onChunk(i)
                playFile(file)
                i++
            }
        }
    }

    // ---------- Dónde me quedé ----------
    private fun save() {
        val src = file ?: return
        if (!isOpen || pageCount == 0) return
        prefs.edit().putString("pos:" + fileId(src), "$page|$curChunk|$target|$pageCount").apply()
    }

    private fun clearPos() {
        val src = file ?: return
        prefs.edit().remove("pos:" + fileId(src)).apply()
    }

    // ---------- Preguntas sobre el PDF ----------
    private fun stripPage(d: PDDocument, p: Int): String {
        val s = PDFTextStripper()
        s.setSortByPosition(true)
        s.setStartPage(p + 1)
        s.setEndPage(p + 1)
        return s.getText(d)
    }

    private suspend fun plainText(p: Int): String = mutex.withLock {
        val c = cache[p]
        if (c != null) return@withLock c
        val d = doc ?: throw Exception("No hay PDF abierto")
        val t = withContext(Dispatchers.IO) { stripPage(d, p) }
        if (t.trim().length >= 15) cache[p] = t
        t
    }

    private suspend fun allPages(): List<String> {
        val c = qaPages
        if (c != null) return c
        status = "Leyendo el PDF…"
        val list = ArrayList<String>()
        for (i in 0 until pageCount) list.add(plainText(i))
        qaPages = list
        return list
    }

    fun ask(q: String) {
        if (!isOpen || pageCount == 0) return
        val key = getKey()
        if (key.isBlank()) {
            needKey = true
            status = "Para preguntar necesito la API key de Groq."
            return
        }
        pause()
        question = q
        answer = ""
        asking = true
        status = "Buscando en el PDF…"
        scope.launch {
            try {
                val pages = allPages()
                val ctxText = withContext(Dispatchers.Default) { pickContext(pages, q) }
                if (ctxText.isBlank()) {
                    answer = "No pude sacar texto de este PDF (parece escaneado). Léelo primero con ▶ para que la IA transcriba las páginas y vuelve a preguntar."
                    status = ""
                    return@launch
                }
                status = "Pensando…"
                val lang = if (target == "en") "inglés" else "español"
                val a = askPdf(key, title, ctxText, q, lang) { status = it }
                answer = a
                status = ""
                speakAnswer(a)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                status = e.message ?: "Error al preguntar"
            } finally {
                asking = false
            }
        }
    }

    private fun speakAnswer(a: String) {
        if (voiceMode == "server") {
            val g = gen
            job?.cancel()
            job = scope.launch {
                try {
                    playRemoteList(g, speechChunks(a, 700), 0) { }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    status = e.message ?: "Error de la voz del servidor"
                }
            }
            return
        }
        val t = tts ?: return
        if (!ttsOk) return
        t.setSpeechRate(speed)
        speechChunks(a, 700).forEachIndexed { i, c ->
            t.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "ans:$i")
        }
    }

    fun clearAnswer() {
        question = ""
        answer = ""
        if (!speaking) tts?.stop()
    }

    // ---------- Órdenes por voz ----------
    private fun has(n: String, vararg ws: String): Boolean {
        val p = " $n "
        return ws.any { p.contains(" $it ") }
    }

    private fun jumpAndPlay(p: Int): Boolean {
        if (!isOpen || pageCount == 0) return false
        if (p < 0) {
            status = "Ya estás en la primera página."
            return false
        }
        if (p >= pageCount) {
            status = "Es la última página."
            return false
        }
        pause()
        page = p
        curChunk = 0
        chunksPage = -1
        resumeChunk = -1
        play()
        return true
    }

    fun voice(text: String, wasSpeaking: Boolean) {
        val n = norm(text)
        val w = n.split(" ").filter { it.isNotEmpty() }
        if (w.isEmpty()) {
            if (wasSpeaking) play()
            return
        }
        status = "«$text»"
        val isQ = has(
            n, "resume", "resumen", "resumeme", "explica", "explicame", "cuanto", "cuanta", "cuantos", "cuantas",
            "quien", "quienes", "cuando", "donde", "como", "cual", "cuales", "dime", "menciona", "habla"
        ) || n.contains("que dice") || n.contains("de que trata") || n.contains("por que") ||
            n.contains("que significa") || n.startsWith("que es ") || n.startsWith("que son ")
        if (!isQ && w.size <= 7) {
            val m = Regex("pagina (\\d+)").find(n)
            if (m != null) {
                val num = m.groupValues[1].toIntOrNull() ?: 0
                if (num in 1..pageCount) {
                    jumpAndPlay(num - 1)
                } else {
                    status = "Este PDF tiene $pageCount páginas."
                    if (wasSpeaking) play()
                }
                return
            }
        }
        if (!isQ && w.size <= 5) {
            when {
                n.contains("traduc") -> {
                    val t = when {
                        has(n, "sin", "no", "quita", "quitar") -> ""
                        n.contains("ingles") -> "en"
                        else -> "es"
                    }
                    chooseTarget(t)
                    if (wasSpeaking) play()
                    return
                }
                n.contains("principio") || n.contains("inicio") || n.contains("empezar de nuevo") || n.contains("comienzo") -> {
                    if (!jumpAndPlay(0) && wasSpeaking) play()
                    return
                }
                has(n, "repite", "repetir", "repitela") || n.contains("otra vez") || n.contains("de nuevo") -> {
                    if (!jumpAndPlay(page) && wasSpeaking) play()
                    return
                }
                has(n, "siguiente", "adelante", "avanza", "adelanta") -> {
                    if (!jumpAndPlay(page + 1) && wasSpeaking) play()
                    return
                }
                has(n, "anterior", "atras", "regresa", "regresar", "retrocede") -> {
                    if (!jumpAndPlay(page - 1) && wasSpeaking) play()
                    return
                }
                has(n, "acelera", "rapido", "rapida", "deprisa") -> {
                    speed = minOf(2f, Math.round((speed + 0.2f) * 10) / 10f)
                    status = "Velocidad ${speed}x"
                    if (wasSpeaking) play()
                    return
                }
                has(n, "lento", "lenta", "despacio", "desacelera") -> {
                    speed = maxOf(0.6f, Math.round((speed - 0.2f) * 10) / 10f)
                    status = "Velocidad ${speed}x"
                    if (wasSpeaking) play()
                    return
                }
                has(n, "normal") -> {
                    speed = 1f
                    status = "Velocidad normal"
                    if (wasSpeaking) play()
                    return
                }
                has(n, "sigue", "seguir", "continua", "continuar", "reanuda", "reanudar", "lee", "leer", "play", "dale") -> {
                    play()
                    return
                }
                has(n, "pausa", "pausar", "detente", "detener", "para", "alto", "silencio", "espera", "stop", "callate") -> {
                    pause()
                    status = "Pausado"
                    return
                }
            }
        }
        if (!isQ && w.size <= 2) {
            status = "No entendí: «$text»"
            if (wasSpeaking) play()
            return
        }
        ask(text)
    }

    private suspend fun loadChunks() {
        val t = spokenText(page, false)
        pageText = t.trim()
        chunks = speechChunks(t)
        chunksPage = page
    }

    fun play() {
        if (!isOpen || pageCount == 0) return
        if (voiceMode != "server" && !ttsOk) {
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
                    if (resumeChunk >= 0) {
                        curChunk = resumeChunk.coerceIn(0, maxOf(0, chunks.size - 1))
                        resumeChunk = -1
                    }
                }
                if (g != gen) return
                status = ""
                save()
                if (chunks.isEmpty()) {
                    if (page + 1 < pageCount) {
                        page++
                        curChunk = 0
                        continue
                    }
                    speaking = false
                    status = "Terminé el documento"
            clearPos()
                    return
                }
                if (voiceMode == "server") {
                    playRemoteList(g, chunks, curChunk.coerceIn(0, chunks.size - 1)) { i ->
                        curChunk = i
                        save()
                    }
                    if (g == gen && speaking) nextPageAuto()
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
        if (p >= pageCount) return
        if (target.isEmpty()) {
            if (cache.containsKey(p)) return
        } else if (tcache.containsKey("$p:$target")) {
            return
        }
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            try {
                spokenText(p, true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    private fun nextPageAuto() {
        if (page + 1 < pageCount) {
            page++
            curChunk = 0
            resumeChunk = -1
            play()
        } else {
            speaking = false
            status = "Terminé el documento"
            clearPos()
        }
    }

    fun pause() {
        speaking = false
        gen++
        job?.cancel()
        tts?.stop()
        save()
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
        resumeChunk = -1
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
