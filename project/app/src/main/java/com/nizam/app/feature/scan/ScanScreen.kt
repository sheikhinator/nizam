package com.nizam.app.feature.scan

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.net.Ai
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device OCR. The photo never leaves the phone; only text you choose to act on goes to the AI. */
internal suspend fun recognise(context: Context, uri: Uri, chinese: Boolean): String =
    suspendCancellableCoroutine { cont ->
        val client = if (chinese) TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        else TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        runCatching { InputImage.fromFilePath(context, uri) }
            .onFailure { cont.resumeWithException(it) }
            .onSuccess { image ->
                client.process(image)
                    .addOnSuccessListener { cont.resume(it.text) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
    }

@Composable
fun ScanScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var script by remember { mutableStateOf("Latin") }
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val shot = remember { File(context.cacheDir, "scan.jpg") }
    val shotUri = remember { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shot) }

    fun read(uri: Uri) = scope.launch {
        busy = true; status = "Reading…"
        text = runCatching { recognise(context, uri, script == "Chinese") }.getOrElse { status = "Couldn't read it: ${com.nizam.app.ui.components.Feedback.friendly(it)}"; "" }
        if (text.isNotBlank()) status = "Read ${text.length} characters on-device."
        busy = false
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) read(shotUri) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { read(it) } }

    fun ai(prompt: String, then: suspend (String) -> Unit) = scope.launch {
        busy = true
        runCatching { Ai.generate(container.settingsRepository, prompt, maxOutputTokens = 1500) }
            .onSuccess { then(it) }.onFailure { status = "AI failed: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
        busy = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Scanner", "Receipts, notes, documents — read on your phone")
        ChipRow(listOf("Latin", "Chinese"), script, { script = it }, labels = { if (it == "Latin") "English / Latin" else "中文 Chinese" })
        Row {
            Button(enabled = !busy, onClick = { camera.launch(shotUri) }) { Text("Take photo") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(enabled = !busy, onClick = {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("From gallery") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        if (text.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(text, { text = it }, label = { Text("Recognised text — edit if needed") },
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = {
                    scope.launch { container.noteRepository.createFull("Scan · ${java.time.LocalDate.now()}", text); status = "Saved to Notes" }
                }) { Text("Save as note") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        if (container.dynamicRepository.spec("documents") != null) {
                            container.dynamicRepository.addEntry("documents",
                                text.lines().firstOrNull { it.length in 4..60 } ?: "Scan ${java.time.LocalDate.now()}",
                                mapOf("notes" to text.take(4000)))
                            status = "Filed in Documents — searchable in your second brain"
                        } else status = "Documents module isn't installed"
                    }
                }) { Text("File in Documents") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(enabled = !busy, onClick = {
                    ai("Translate into clear English, keeping technical and product terms precise. Output only the translation.\n\n$text") {
                        text = it; status = "Translated"
                    }
                }) { Text("Translate") }
            }
            OutlinedButton(enabled = !busy, onClick = {
                ai("This is OCR text from a receipt. Return ONLY JSON {\"amount\":number,\"category\":\"Food|Transport|Bills|Groceries|Shopping|Health|Family|Other\",\"note\":\"merchant\",\"date\":\"YYYY-MM-DD or empty\"}.\n\n$text") { raw ->
                    val o = JSONObject(Ai.cleanJson(raw))
                    val amount = o.optDouble("amount", 0.0)
                    if (amount.isFinite() && amount > 0) {
                        val result = runCatching {
                            container.financeRepository.addOn(o.optString("date"), amount, "EXPENSE",
                                o.optString("category", "Other"), o.optString("note"))
                        }
                        status = result.fold(
                            onSuccess = { "Logged ${amount.toInt()} PKR · ${o.optString("category")} · ${o.optString("note")}" },
                            onFailure = { "Receipt was not logged: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
                        )
                    } else status = "Couldn't find a valid total on this receipt."
                }
            }) { Text("Log as expense") }
        }
        Spacer(Modifier.height(60.dp))
    }
}
