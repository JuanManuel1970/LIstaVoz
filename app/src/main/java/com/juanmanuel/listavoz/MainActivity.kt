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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay


import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.KeyboardType
import java.util.Locale



import androidx.compose.runtime.rememberCoroutineScope


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
                speak(buildAddedSpeech(name, qty))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        dao = AppDatabase.get(this).itemDao()

        setContent {
            // Splash mínimo
            var showSplash by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                delay(4000) // 1.5s (cambiá a gusto)
                showSplash = false
            }

            if (showSplash) {
                // Pantalla con imagen centrada
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.juan_wolf),

                        contentDescription = "Logo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            } else {
                // ⬇️ TU UI ORIGINAL (sin cambios)
                val scope = rememberCoroutineScope()
                val items by dao.observeAll().collectAsState(initial = emptyList())

                // Ordenamos pendientes arriba (purchased=false) y luego por nombre (case-insensitive)
                val uiItems = remember(items) {
                    items.sortedWith(
                        compareBy<Item> { it.purchased }
                            .thenBy { it.name.lowercase() }
                    )
                }

                // Estado para diálogo de edición
                var editing by remember { mutableStateOf<Item?>(null) }
                var newName by remember { mutableStateOf("") }

                MaterialTheme {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        // Título principal
                        Text(
                            "Mi Lista de Compras",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(8.dp))

                        // Fila de entrada
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = nameInput.value,
                                onValueChange = { nameInput.value = it },
                                label = { Text("Producto") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = qtyInput.value,
                                onValueChange = { s ->
                                    qtyInput.value = s.filter { it.isDigit() }.ifEmpty { "1" }
                                },
                                label = { Text("Cant.") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                ),
                                singleLine = true,
                                modifier = Modifier.width(90.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                val name = nameInput.value.trim()
                                val qty = qtyInput.value.toIntOrNull() ?: 1
                                if (name.isNotEmpty()) {
                                    scope.launch { dao.upsert(Item(name = name, qty = qty)) }
                                    nameInput.value = ""
                                    qtyInput.value = "1"
                                }
                            }) { Text("Agregar") }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Acciones
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    val pendientes = uiItems.filter { !it.purchased }
                                    val texto = if (pendientes.isEmpty()) {
                                        "No hay pendientes."
                                    } else {
                                        pendientes.joinToString(". ") { it.name + " (" + it.qty + ")" }
                                    }
                                    val encabezado = if (pendientes.size == 1) "Te falta: " else "Te faltan: "
                                    speak(encabezado + texto)
                                },
                                enabled = ttsReady.value
                            ) { Text("Leer pendientes") }

                            Spacer(Modifier.width(8.dp))

                            Button(onClick = { tryVoiceAdd() }) { Text("Agregar por voz") }

                            Spacer(Modifier.width(8.dp))

                            Button(onClick = { shareList(uiItems) }) { Text("Compartir") }

                            Spacer(Modifier.width(8.dp))

                            Button(onClick = { scope.launch { dao.deletePurchased() } }) {
                                Text("Eliminar comprados")
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
                            items(uiItems, key = { it.id }) { item ->
                                ListItem(
                                    headlineContent = { Text(item.name) },
                                    supportingContent = {
                                        Text(if (item.purchased) "Comprado" else "Pendiente")
                                    },
                                    leadingContent = {
                                        Checkbox(
                                            checked = item.purchased,
                                            onCheckedChange = {
                                                scope.launch { dao.setPurchased(item.id, !item.purchased) }
                                            }
                                        )
                                    },
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            // MENOS
                                            TextButton(onClick = { scope.launch { dao.changeQty(item.id, -1) } }) { Text("−") }

                                            // Cantidad
                                            Text(
                                                text = "${item.qty}",
                                                modifier = Modifier.width(28.dp),
                                                textAlign = TextAlign.Center
                                            )

                                            // MÁS
                                            TextButton(onClick = { scope.launch { dao.changeQty(item.id, +1) } }) { Text("+") }

                                            Spacer(Modifier.width(8.dp))

                                            // Eliminar
                                            TextButton(onClick = { scope.launch { dao.deleteById(item.id) } }) { Text("Eliminar") }
                                        }
                                    }
                                )
                                Divider()
                            }
                        }

                        // Diálogo de edición
                        if (editing != null) {
                            AlertDialog(
                                onDismissRequest = { editing = null },
                                confirmButton = {
                                    TextButton(onClick = {
                                        val current = editing!!
                                        val nn = newName.trim()
                                        if (nn.isNotEmpty()) {
                                            scope.launch { dao.rename(current.id, nn) }
                                        }
                                        editing = null
                                    }) { Text("Guardar") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { editing = null }) { Text("Cancelar") }
                                },
                                title = { Text("Editar ítem") },
                                text = {
                                    OutlinedTextField(
                                        value = newName,
                                        onValueChange = { newName = it },
                                        singleLine = true,
                                        label = { Text("Nombre") }
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

    }

    // ===== Text-to-Speech =====
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts?.setLanguage(Locale("es", "AR"))
            val ok = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ok) {
                val resEs = tts?.setLanguage(Locale("es", "ES"))
                if (resEs == TextToSpeech.LANG_MISSING_DATA || resEs == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.US
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

    // Parseo de ítem dicho por voz
    private fun parseSpokenItem(phrase: String): Pair<String, Int> {
        val p = phrase.lowercase().trim()

        fun wordToInt(w: String): Int? = when (w) {
            "un", "uno", "una" -> 1
            "dos" -> 2
            "tres" -> 3
            "cuatro" -> 4
            "cinco" -> 5
            "seis" -> 6
            "siete" -> 7
            "ocho" -> 8
            "nueve" -> 9
            "diez" -> 10
            else -> null
        }

        val raw = p.split(Regex("\\s+")).filter { it.isNotBlank() }

        // "<nombre> <cantidad>"  (leche 2 / yerba dos)
        val tailNum = raw.lastOrNull()?.let { it.toIntOrNull() ?: wordToInt(it) }
        if (tailNum != null && raw.size > 1) {
            val name = raw.dropLast(1)
                .filterNot { it in listOf("de", "del", "la", "el", "los", "las") }
                .joinToString(" ")
            return name to tailNum
        }

        // "<cantidad> (de) <nombre>"  (dos litros de leche / 3 pan)
        val headNum = raw.firstOrNull()?.let { it.toIntOrNull() ?: wordToInt(it) }
        if (headNum != null) {
            val name = raw.drop(1)
                .filterNot { it in listOf("de", "del", "la", "el", "los", "las") }
                .joinToString(" ")
            return (name.ifBlank { p }) to headNum
        }

        // Sin cantidad -> 1
        val name = raw.filterNot { it in listOf("de", "del", "la", "el", "los", "las") }
            .joinToString(" ")
            .ifBlank { p }
        return name to 1
    }

    private fun shareList(items: List<Item>) {
        val texto = if (items.isEmpty()) {
            "Lista vacía"
        } else {
            items.joinToString("\n") { i ->
                val marca = if (i.purchased) "✓" else "•"
                val qty = if (i.qty == 1) "" else " (${i.qty})"
                "$marca ${i.name}$qty"
            }
        }
        val pendientes = items.count { !it.purchased }
        val encabezado = "Pendientes: $pendientes\n\n"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, encabezado + texto)
        }
        startActivity(Intent.createChooser(intent, "Compartir lista"))
    }

    private fun buildAddedSpeech(name: String, qty: Int): String =
        if (qty == 1) "Agregado: $name" else "Agregado: $qty $name"

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}