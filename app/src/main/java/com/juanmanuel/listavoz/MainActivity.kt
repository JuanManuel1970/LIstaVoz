package com.juanmanuel.listavoz

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.coroutines.launch

// ===== ENTIDAD ROOM =====
@Entity(tableName = "items")
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val qty: Int = 1,
    val purchased: Boolean = false
)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    // Inputs UI
    private var nameInput = mutableStateOf("")
    private var qtyInput = mutableStateOf("1")

    // Voz TTS
    private var tts: TextToSpeech? = null
    private var ttsReady = mutableStateOf(false)
    private var statusText = mutableStateOf("Inicializando voz…")
    private var missingLang = mutableStateOf(false)

    // Room DAO
    private lateinit var dao: ItemDao

    // ===== Reconocimiento de voz =====
    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceRecognition()
        else statusText.value = "Permiso de micrófono denegado."
    }

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val list = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val phrase = list?.firstOrNull()?.lowercase() ?: return@registerForActivityResult
            val (name, qty) = parseSpokenItem(phrase)
            if (name.isNotBlank()) {
                lifecycleScope.launch { dao.upsert(Item(name = name, qty = qty)) }
                speak("Agregado: $qty $name")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        dao = AppDatabase.get(this).itemDao()

        setContent {
            val items by dao.observeAll().collectAsState(initial = emptyList())

            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Estado de la voz
                    Text(statusText.value)
                    Spacer(Modifier.height(8.dp))

                    // Fila de entrada
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = nameInput.value,
                            onValueChange = { nameInput.value = it },
                            label = { Text("Producto") },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = qtyInput.value,
                            onValueChange = { s ->
                                qtyInput.value = s.filter { it.isDigit() }.ifEmpty { "1" }
                            },
                            label = { Text("Cant.") },
                            modifier = Modifier.width(90.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            val name = nameInput.value.trim()
                            val qty = qtyInput.value.toIntOrNull() ?: 1
                            if (name.isNotEmpty()) {
                                lifecycleScope.launch {
                                    dao.upsert(Item(name = name, qty = qty))
                                }
                                nameInput.value = ""
                                qtyInput.value = "1"
                            }
                        }) { Text("Agregar") }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Acciones
                    Row {
                        Button(
                            onClick = {
                                val pendientes = items.filter { !it.purchased }
                                val texto = if (pendientes.isEmpty())
                                    "No hay pendientes."
                                else pendientes.joinToString(". ") { "${it.qty} ${it.name}" }
                                speak("Te faltan: $texto")
                            },
                            enabled = ttsReady.value
                        ) { Text("Leer pendientes") }

                        Spacer(Modifier.width(8.dp))

                        Button(onClick = { tryVoiceAdd() }) {
                            Text("Agregar por voz")
                        }

                        Spacer(Modifier.width(8.dp))

                        if (missingLang.value) {
                            OutlinedButton(onClick = { installTtsData() }) {
                                Text("Instalar voz")
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Lista
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(items, key = { it.id }) { item ->
                            ListItem(
                                headlineContent = { Text("${item.name} x${item.qty}") },
                                supportingContent = {
                                    Text(if (item.purchased) "Comprado" else "Pendiente")
                                },
                                leadingContent = {
                                    Checkbox(
                                        checked = item.purchased,
                                        onCheckedChange = {
                                            lifecycleScope.launch {
                                                dao.setPurchased(item.id, !item.purchased)
                                            }
                                        }
                                    )
                                },
                                trailingContent = {
                                    TextButton(onClick = {
                                        lifecycleScope.launch { dao.deleteById(item.id) }
                                    }) { Text("Eliminar") }
                                }
                            )
                            Divider()
                        }
                    }
                }
            }
        }
    }

    // ===== Text-to-Speech =====
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts?.setLanguage(java.util.Locale("es", "AR"))
            val ok = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ok) {
                val resEs = tts?.setLanguage(java.util.Locale("es", "ES"))
                if (resEs == TextToSpeech.LANG_MISSING_DATA || resEs == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = java.util.Locale.US
                    missingLang.value = true
                    statusText.value =
                        "Faltan voces en español. Tocá 'Instalar voz' o probalo en un celular."
                } else {
                    statusText.value = "TTS listo (es-ES) ✅"
                    ttsReady.value = true
                }
            } else {
                statusText.value = "TTS listo (es-AR) ✅"
                ttsReady.value = true
            }
            tts?.setSpeechRate(0.95f)
            tts?.setPitch(1.0f)
        } else {
            statusText.value = "Error inicializando TTS"
        }
    }

    private fun speak(text: String) {
        if (text.isNotBlank()) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "PRUEBA_TTS")
        }
    }

    private fun installTtsData() {
        val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
        startActivity(intent)
    }

    // ===== Reconocimiento de voz =====
    private fun tryVoiceAdd() {
        val perm = android.Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecognition()
        } else {
            requestMicPermission.launch(perm)
        }
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Decí el producto y cantidad (ej: 'leche 2')")
        }
        voiceLauncher.launch(intent)
    }

    private fun parseSpokenItem(phrase: String): Pair<String, Int> {
        val tokens = phrase.trim().split(" ").filter { it.isNotBlank() }
        val maybeQty = tokens.lastOrNull()?.toIntOrNull()
        return if (maybeQty != null && tokens.size > 1) {
            tokens.dropLast(1).joinToString(" ") to maybeQty
        } else {
            phrase to 1
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
