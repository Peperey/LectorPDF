package com.example.lectorpdf

import android.os.Environment
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

// Si un modelo deja de funcionar, mira la lista actual en console.groq.com/docs/models
const val BASE = "https://api.groq.com/openai/v1"
val VISION_MODELS = listOf("qwen/qwen3.6-27b", "qwen/qwen3.8-27b")

fun norm(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

private val STOP = setOf(
    "leeme", "lee", "leer", "lea", "leelo", "leela", "el", "la", "los", "las", "un", "una", "unos", "unas",
    "pdf", "archivo", "documento", "de", "del", "que", "se", "llama", "llamado", "en", "descargas",
    "descarga", "mi", "mis", "por", "favor", "abre", "abrir", "puedes", "quiero", "ultimo", "ultima",
    "reciente", "ese", "este", "con", "y", "lo", "me", "al", "para", "tengo", "nuevo"
)

fun wantsLatest(cmd: String): Boolean {
    val n = " " + norm(cmd) + " "
    return n.contains(" ultimo ") || n.contains(" ultima ") || n.contains(" reciente ") || n.contains(" nuevo ")
}

fun queryTokens(cmd: String): List<String> =
    norm(cmd).split(" ").filter { it.length >= 2 && it !in STOP }

fun score(file: File, tokens: List<String>): Int {
    val n = norm(file.nameWithoutExtension)
    return tokens.count { n.contains(it) }
}

@Suppress("DEPRECATION")
fun findPdfs(): List<File> {
    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    return dir.listFiles()
        ?.filter { it.isFile && it.name.lowercase().endsWith(".pdf") }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()
}

// Parte el texto de una página en pedazos cortos para la voz (permite pausar y reanudar con precisión).
fun speechChunks(raw: String, max: Int = 700): List<String> {
    val t = raw.replace("\r", "")
        .replace(Regex("-\\n(?=\\p{Ll})"), "")
        .replace(Regex("(?<!\\n)\\n(?!\\n)"), " ")
        .replace(Regex("[ \\t]+"), " ")
    val parts = t.split(Regex("(?<=[.!?…:;])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
    val out = ArrayList<String>()
    val sb = StringBuilder()
    for (p in parts) {
        if (p.length > max) {
            if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
            p.chunked(max).forEach { out.add(it) }
            continue
        }
        if (sb.length + p.length + 1 > max && sb.isNotEmpty()) {
            out.add(sb.toString())
            sb.setLength(0)
        }
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(p)
    }
    if (sb.isNotEmpty()) out.add(sb.toString())
    return out
}

private fun readResponse(c: HttpURLConnection): String {
    val ok = c.responseCode in 200..299
    val txt = (if (ok) c.inputStream else c.errorStream).bufferedReader().readText()
    if (!ok) throw Exception("Error ${c.responseCode}: " + txt.take(200))
    return txt
}

private fun post(key: String, body: JSONObject): String {
    val c = URL("$BASE/chat/completions").openConnection() as HttpURLConnection
    try {
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 30000
        c.readTimeout = 90000
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        return JSONObject(readResponse(c)).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    } finally {
        c.disconnect()
    }
}

// Lee el texto de una página escaneada (imagen JPEG) con un modelo de visión de Groq.
// Si hay error 404 o 429 prueba el otro modelo y, si siguen fallando, espera y reintenta.
suspend fun ocrJpeg(key: String, jpeg: ByteArray, onWait: (String) -> Unit): String {
    val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
    val prompt = "Transcribe todo el texto de esta página, en su idioma original y respetando el orden de lectura. " +
        "Responde solo con el texto, sin comentarios. Si no hay texto, responde con una sola palabra: VACIA."
    val content = JSONArray()
        .put(JSONObject().put("type", "text").put("text", prompt))
        .put(
            JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
        )
    var last: Exception? = null
    for (attempt in 0 until 3) {
        for (m in VISION_MODELS) {
            try {
                val body = JSONObject().put("model", m).put("temperature", 0.1).put("max_tokens", 900)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                val raw = withContext(Dispatchers.IO) { post(key, body) }
                val t = Regex("(?s)<think>.*?</think>").replace(raw, "").trim()
                return if (t.equals("VACIA", ignoreCase = true)) "" else t
            } catch (e: Exception) {
                last = e
                val msg = e.message ?: ""
                if (!msg.startsWith("Error 404") && !msg.startsWith("Error 429")) throw e
            }
        }
        if (attempt < 2) {
            onWait("Esperando el límite de la API (intento ${attempt + 2} de 3)…")
            delay(25000)
        }
    }
    throw last ?: Exception("No hay modelo de visión disponible")
}

data class MediaCmd(val query: String, val full: String, val inApp: String?, val openOnly: Boolean)

private val MEDIA_TRIGGERS = setOf("pon", "ponme", "pone", "reproduce", "reproduceme", "toca", "ponla", "ponlo")
private val MEDIA_KEYS = setOf("youtube", "musica", "cancion", "canciones", "video", "videos", "pelicula", "peliculas", "serie", "series")
private val MEDIA_WORDS = MEDIA_TRIGGERS + MEDIA_KEYS + setOf(
    "de", "la", "el", "los", "las", "en", "un", "una", "me", "algo", "por", "favor", "del", "al",
    "unas", "unos", "quiero", "escuchar", "ver"
)
private val OPEN_TRIGGERS = setOf("abre", "abrir", "abreme", "inicia", "iniciar", "lanza", "ejecuta")
private val APP_FILLER = setOf("la", "el", "app", "aplicacion", "de", "por", "favor", "mi", "en", "me")

// Entiende órdenes de música/video/apps. Devuelve null si no es una de ellas.
fun mediaCmd(cmd: String): MediaCmd? {
    val n = norm(cmd)
    if (n.isEmpty() || n.contains("pdf") || n.startsWith("lee")) return null
    val words = n.split(" ")
    if (words[0] in OPEN_TRIGGERS) {
        val app = words.drop(1).filter { it !in APP_FILLER }.joinToString(" ")
        return if (app.isBlank()) null else MediaCmd("", "", app, true)
    }
    val isMedia = words[0] in MEDIA_TRIGGERS || words.any { it in MEDIA_KEYS }
    if (!isMedia) return null
    val enIdx = words.lastIndexOf("en")
    var inApp: String? = null
    var qWords = words
    if (enIdx > 0 && enIdx < words.size - 1) {
        inApp = words.drop(enIdx + 1).filter { it !in APP_FILLER }.joinToString(" ").ifBlank { null }
        qWords = words.take(enIdx)
    }
    if (inApp == "youtube") inApp = null
    val q = qWords.filter { it !in MEDIA_WORDS }.joinToString(" ").ifBlank { qWords.joinToString(" ") }
    val full = words.filter { it !in MEDIA_WORDS }.joinToString(" ").ifBlank { n }
    return MediaCmd(q, full, inApp, false)
}

// Primer video que devuelve la búsqueda de YouTube (necesita API key de YouTube Data API v3).
fun youtubeFirstVideo(apiKey: String, q: String): String? {
    val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=1&q=" +
        java.net.URLEncoder.encode(q, "UTF-8") + "&key=" + apiKey
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 20000
        c.readTimeout = 20000
        val items = JSONObject(readResponse(c)).optJSONArray("items") ?: return null
        if (items.length() == 0) return null
        val id = items.getJSONObject(0).getJSONObject("id").optString("videoId")
        return if (id.isBlank()) null else id
    } finally {
        c.disconnect()
    }
}

val TEXT_MODELS = listOf("openai/gpt-oss-20b", "openai/gpt-oss-120b")

fun langName(code: String): String = when (code) {
    "en" -> "inglés"
    else -> "español"
}

// Traduce el texto de una página con un modelo de texto de Groq.
suspend fun translateText(key: String, text: String, lang: String, onWait: (String) -> Unit): String {
    val parts = speechChunks(text, 3000)
    val sb = StringBuilder()
    for (part in parts) {
        if (sb.isNotEmpty()) sb.append("\n\n")
        sb.append(translatePart(key, part, lang, onWait))
    }
    return sb.toString()
}

private suspend fun translatePart(key: String, text: String, lang: String, onWait: (String) -> Unit): String {
    val sys = "Eres un traductor. Traduce al $lang el texto que te envíe el usuario (viene de un PDF). " +
        "Responde solo con la traducción, sin comentarios ni explicaciones. Conserva los párrafos. " +
        "Si el texto ya está en $lang, devuélvelo igual."
    val msgs = JSONArray()
        .put(JSONObject().put("role", "system").put("content", sys))
        .put(JSONObject().put("role", "user").put("content", text))
    var reasoning = true
    var last: Exception? = null
    for (attempt in 0 until 3) {
        for (m in TEXT_MODELS) {
            try {
                val body = JSONObject().put("model", m).put("temperature", 0.2).put("max_tokens", 1800)
                    .put("messages", msgs)
                if (reasoning) body.put("reasoning_effort", "low")
                val raw = withContext(Dispatchers.IO) { post(key, body) }
                return Regex("(?s)<think>.*?</think>").replace(raw, "").trim()
            } catch (e: Exception) {
                last = e
                val msg = e.message ?: ""
                if (msg.startsWith("Error 400") && reasoning) {
                    reasoning = false
                } else if (!msg.startsWith("Error 404") && !msg.startsWith("Error 429") && !msg.startsWith("Error 400")) {
                    throw e
                }
            }
        }
        if (attempt < 2) {
            onWait("Esperando el límite de la API (intento ${attempt + 2} de 3)…")
            delay(20000)
        }
    }
    throw last ?: Exception("No hay modelo de traducción disponible")
}
