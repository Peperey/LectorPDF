package com.example.lectorpdf

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Teal = Color(0xFF00796B)
val Bg = Color(0xFFF4F8F7)
val Ink = Color(0xFF2B2B2B)
val Muted = Color(0xFF777777)
val RedErr = Color(0xFFC62828)

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Teal)) {
                App(prefs, tick)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tick++
    }
}

fun hasFileAccess(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
    else ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

fun findApp(ctx: Context, name: String): Pair<String, String>? {
    val pm = ctx.packageManager
    val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
    val target = norm(name)
    var best: Pair<String, String>? = null
    var bestScore = 0
    for (ri in list) {
        val label = ri.loadLabel(pm).toString()
        val l = norm(label)
        val sc = when {
            l == target -> 4
            l.startsWith(target) -> 3
            l.contains(target) -> 2
            l.length >= 3 && target.contains(l) -> 1
            else -> 0
        }
        val cur = best
        if (sc > bestScore || (sc > 0 && sc == bestScore && cur != null && label.length < cur.first.length)) {
            best = Pair(label, ri.activityInfo.packageName)
            bestScore = sc
        }
    }
    return best
}

fun fmtDate(ms: Long): String = SimpleDateFormat("d MMM yyyy", Locale("es")).format(Date(ms))

@Composable
fun App(prefs: SharedPreferences, tick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(prefs.getString("groq", "") ?: "") }
    var ytKey by remember { mutableStateOf(prefs.getString("yt", "") ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var ytInput by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var permTick by remember { mutableIntStateOf(0) }
    val reader = remember { ReaderState(ctx.applicationContext, scope) { key } }

    DisposableEffect(Unit) { onDispose { reader.release() } }
    BackHandler(enabled = reader.isOpen) { reader.close() }

    val hasAccess = remember(tick, permTick) { hasFileAccess(ctx) }
    val all = remember(tick, permTick, hasAccess) { if (hasAccess) findPdfs() else emptyList() }
    val tokens = queryTokens(query)
    val shown = if (tokens.isEmpty()) all else all
        .filter { score(it, tokens) > 0 }
        .sortedWith(compareByDescending<File> { score(it, tokens) }.thenByDescending { it.lastModified() })

    fun openUrl(url: String, pkg: String? = null) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) i.setPackage(pkg)
        ctx.startActivity(i)
    }

    fun runMedia(m: MediaCmd) {
        val pm = ctx.packageManager
        val target = if (m.inApp != null) findApp(ctx, m.inApp) else null
        if (m.openOnly) {
            if (target == null) {
                msg = "No encontré la app «${m.inApp}» en tu celular."
                return
            }
            val li = pm.getLaunchIntentForPackage(target.second)
            if (li != null) {
                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(li)
                msg = "Abriendo ${target.first}"
            } else {
                msg = "No pude abrir ${target.first}."
            }
            return
        }
        if (target != null) {
            try {
                val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(target.second)
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    .putExtra(SearchManager.QUERY, m.query)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (i.resolveActivity(pm) != null) {
                    ctx.startActivity(i)
                    msg = "Buscando «${m.query}» en ${target.first}"
                    return
                }
                if (norm(target.first).contains("tubi")) {
                    openUrl("https://tubitv.com/search/" + Uri.encode(m.query), target.second)
                    msg = "Buscando «${m.query}» en ${target.first}"
                    return
                }
                val li = pm.getLaunchIntentForPackage(target.second)
                if (li != null) {
                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(li)
                    msg = "Abrí ${target.first}, pero no puedo buscar dentro de ella. Busca «${m.query}» tú."
                    return
                }
            } catch (e: Exception) {
                msg = "No pude abrir ${target.first}."
                return
            }
        }
        val q = m.full
        val searchUrl = "https://www.youtube.com/results?search_query=" + Uri.encode(q)
        if (ytKey.isBlank()) {
            try {
                openUrl(searchUrl)
                msg = "Abrí la búsqueda en YouTube. Para que ponga el primer video solo, agrega tu API key de YouTube en 🔑."
            } catch (e: Exception) {
                msg = "No pude abrir YouTube."
            }
            return
        }
        msg = "Buscando en YouTube…"
        scope.launch {
            try {
                val id = withContext(Dispatchers.IO) { youtubeFirstVideo(ytKey, q) }
                if (id != null) {
                    openUrl("https://www.youtube.com/watch?v=$id")
                    msg = "Reproduciendo: $q"
                } else {
                    openUrl(searchUrl)
                    msg = "No encontré resultados; abrí la búsqueda."
                }
            } catch (e: Exception) {
                try { openUrl(searchUrl) } catch (e2: Exception) { }
                msg = "Falló la API de YouTube (${(e.message ?: "").take(80)}). Abrí la búsqueda."
            }
        }
    }

    val runCommand: (String) -> Unit = { text ->
        query = text
        msg = ""
        val toks = queryTokens(text)
        val media = mediaCmd(text)
        if (media != null) {
            runMedia(media)
        } else if (all.isEmpty()) {
            msg = if (hasAccess) "No encontré PDFs en Descargas." else "Primero da el permiso para ver Descargas."
        } else if (toks.isEmpty()) {
            if (wantsLatest(text)) reader.openPdf(all.first())
            else msg = "Dime el nombre del PDF o elige uno de la lista."
        } else {
            val scored = all.map { it to score(it, toks) }.filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<File, Int>> { it.second }.thenByDescending { it.first.lastModified() })
            if (scored.isEmpty()) {
                msg = "No encontré un PDF que coincida con «${toks.joinToString(" ")}»."
            } else {
                val top = scored[0].second
                val ties = scored.filter { it.second == top }
                if (ties.size == 1 || wantsLatest(text)) reader.openPdf(ties.first().first)
                else msg = "Varios PDFs coinciden. Elige uno de la lista."
            }
        }
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !t.isNullOrBlank()) runCommand(t)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) reader.openUri(uri)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }

    fun askAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))
                )
            } catch (e: Exception) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            permLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Di: léeme el PDF de… o pon música de…")
        try {
            speech.launch(i)
        } catch (e: Exception) {
            msg = "Este celular no tiene reconocimiento de voz. Escribe el nombre."
        }
    }

    if (reader.isOpen) {
        ReaderScreen(reader)
    } else {
        Column(
            Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding().padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📖 Lector PDF", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
                Text("🔑", fontSize = 22.sp, modifier = Modifier.clickable { keyInput = key; ytInput = ytKey; showKey = true }.padding(8.dp))
            }
            Spacer(Modifier.height(12.dp))

            if (!hasAccess) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp)
                ) {
                    Text("Para ver tus PDFs de Descargas necesito permiso de acceso a archivos.", color = Ink)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { askAccess() }) { Text("Dar permiso") }
                }
                Spacer(Modifier.height(12.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; msg = "" },
                    placeholder = { Text("léeme el PDF de… / pon música de… / abre Tubi") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { listen() }) { Text("🎤") }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = { runCommand(query) }) { Text("Leer") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { picker.launch(arrayOf("application/pdf")) }) { Text("Elegir archivo…") }
            }
            if (msg.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(msg, color = RedErr, fontSize = 14.sp)
            }
            if (reader.status.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(reader.status, color = Muted, fontSize = 14.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (hasAccess) "PDFs en Descargas (${shown.size})" else "",
                color = Muted, fontSize = 13.sp
            )
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.absolutePath }) { f ->
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp)).background(Color.White)
                            .clickable { msg = ""; reader.openPdf(f) }
                            .padding(14.dp)
                    ) {
                        Text(f.nameWithoutExtension, color = Ink, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(fmtDate(f.lastModified()) + " · " + (f.length() / 1024) + " KB", color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (reader.needKey || showKey) {
        AlertDialog(
            onDismissRequest = { reader.needKey = false; showKey = false },
            title = { Text("Groq API key") },
            text = {
                Column {
                    Text("Groq: solo hace falta para leer PDFs escaneados (páginas que son imágenes). Los PDFs con texto se leen sin ella.", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = keyInput, onValueChange = { keyInput = it }, singleLine = true, placeholder = { Text("gsk_…") })
                    Spacer(Modifier.height(12.dp))
                    Text("YouTube (opcional): para que «pon música de…» reproduzca el primer video solo.", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = ytInput, onValueChange = { ytInput = it }, singleLine = true, placeholder = { Text("AIza…") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    key = keyInput.trim()
                    ytKey = ytInput.trim()
                    prefs.edit().putString("groq", key).putString("yt", ytKey).apply()
                    reader.needKey = false
                    showKey = false
                }) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { reader.needKey = false; showKey = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
fun ReaderScreen(r: ReaderState) {
    val view = LocalView.current
    DisposableEffect(r.speaking) {
        view.keepScreenOn = r.speaking
        onDispose { view.keepScreenOn = false }
    }
    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding().padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("←", fontSize = 28.sp, color = Ink, modifier = Modifier.clickable { r.close() }.padding(end = 12.dp, top = 4.dp, bottom = 4.dp))
            Text(r.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (r.pageCount > 0) "Página ${r.page + 1} de ${r.pageCount}" else "",
            color = Muted, fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp)
        ) {
            key(r.page) {
                Text(r.pageText, color = Ink, fontSize = 16.sp, modifier = Modifier.verticalScroll(rememberScrollState()))
            }
        }
        if (r.status.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(r.status, color = if (r.status.startsWith("Preparando") || r.status.startsWith("Leyendo") || r.status.startsWith("Esperando") || r.status.startsWith("Abriendo") || r.status.startsWith("Terminé")) Muted else RedErr, fontSize = 14.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { r.goTo(r.page - 1) }) { Text("⏮") }
            Button(onClick = { if (r.speaking) r.pause() else r.play() }) { Text(if (r.speaking) "⏸ Pausa" else "▶ Leer") }
            Button(onClick = { r.goTo(r.page + 1) }) { Text("⏭") }
        }
        Spacer(Modifier.height(8.dp))
        Text("Velocidad " + String.format(Locale.US, "%.1f", r.speed) + "x", color = Muted, fontSize = 13.sp)
        Slider(
            value = r.speed,
            onValueChange = { r.speed = it },
            onValueChangeFinished = { if (r.speaking) r.play() },
            valueRange = 0.6f..2f
        )
    }
}
