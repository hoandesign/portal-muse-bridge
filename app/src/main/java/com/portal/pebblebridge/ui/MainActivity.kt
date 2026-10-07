package com.portal.pebblebridge.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.viewinterop.AndroidView
import com.portal.pebblebridge.BuildConfig
import com.portal.pebblebridge.R
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.portal.pebblebridge.ble.MuseBleManager
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.portal.pebblebridge.model.BridgeConfig
import com.portal.pebblebridge.model.NoteStatus
import com.portal.pebblebridge.model.VoiceNote
import com.portal.pebblebridge.muse.MuseDeliveryClient
import com.portal.pebblebridge.service.BridgeService
import com.portal.pebblebridge.state.BridgeRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  companion object {
    /** Set when the Portal screensaver (HomeDreamService) opened us. */
    const val EXTRA_DREAM_MODE = "dream_mode"
  }

  private val deliveryClient = MuseDeliveryClient()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    BridgeRepository.initPersistence(this)
    com.portal.pebblebridge.home.HomePrefs.init(this)
    BridgeService.start(this)
    applyDreamMode(intent)
    hideSystemBars()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      // Location for BLE; the microphone for "hold the robot to talk".
      val wanted = listOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.RECORD_AUDIO,
      ).filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
      if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1001)
    }

    setContent {
      var showSettings by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
      if (!showSettings) {
        com.portal.pebblebridge.ui.home.HomeScreen(onOpenSettings = { showSettings = true })
        return@setContent
      }
      androidx.activity.compose.BackHandler { showSettings = false }
      MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF121214), surface = Color(0xFF1E1E24))) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          BridgeScreen(
            onBack = { showSettings = false },
            onCopyUrl = { url ->
              val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
              clipboard.setPrimaryClip(ClipData.newPlainText("Webhook URL", url))
              Toast.makeText(this, "Copied webhook URL", Toast.LENGTH_SHORT).show()
            },
            onSendTestNote = {
              val noteId = UUID.randomUUID().toString()
              val testNote = VoiceNote(
                id = noteId,
                timestampEpochMs = System.currentTimeMillis(),
                text = "Remember to buy milk on the way home, and check project deadlines.",
                title = "Groceries & Work",
                source = "Portal Test Trigger",
                status = NoteStatus.PENDING,
              )
              BridgeRepository.addNote(testNote)
              lifecycleScope.launch {
                val res = deliveryClient.deliverNote(testNote, BridgeRepository.config.value)
                if (res.isSuccess) {
                  BridgeRepository.updateNoteStatus(noteId, NoteStatus.DELIVERED, reply = res.getOrNull())
                } else {
                  BridgeRepository.updateNoteStatus(noteId, NoteStatus.FAILED, error = res.exceptionOrNull()?.message)
                }
              }
              Toast.makeText(this, "Dispatched test note", Toast.LENGTH_SHORT).show()
            }
          )
        }
      }
    }
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    applyDreamMode(intent)
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) hideSystemBars()
  }

  /** As the screensaver, keep the screen on (if the user wants) instead of letting it sleep. */
  private fun applyDreamMode(intent: android.content.Intent?) {
    val dream = intent?.getBooleanExtra(EXTRA_DREAM_MODE, false) == true
    if (dream && com.portal.pebblebridge.home.HomePrefs.settings.value.keepAwake) {
      window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
      window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
  }

  private fun hideSystemBars() {
    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
      hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeScreen(
  onBack: () -> Unit,
  onCopyUrl: (String) -> Unit,
  onSendTestNote: () -> Unit,
) {
  var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
  val serverStatus by BridgeRepository.serverStatus.collectAsState()
  val notes by BridgeRepository.notes.collectAsState()
  val config by BridgeRepository.config.collectAsState()
  val pairingPrompt by BridgeRepository.pairingPrompt.collectAsState()
  val isPaired by BridgeRepository.isPaired.collectAsState()
  val bleDeviceName by BridgeRepository.bleDeviceName.collectAsState()
  val museLinkState by BridgeRepository.museLinkState.collectAsState()
  var showSettings by remember { mutableStateOf(false) }

  val mcpUrl = "http://${serverStatus.localIp}:${serverStatus.port}/api/mcp"
  val webhookUrl = "http://${serverStatus.localIp}:${serverStatus.port}/ingest"
  val tailscaleIp = remember { tailscaleAddress() }

  Scaffold(
    topBar = {
      TopAppBar(
        navigationIcon = {
          androidx.compose.material3.TextButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) {
            Text("◀ Home", fontSize = 16.sp)
          }
        },
        title = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              "Settings",
              fontWeight = FontWeight.Bold,
              fontSize = 20.sp,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Box(
              modifier = Modifier
                .background(if (serverStatus.isRunning) Color(0xFF1B5E20) else Color(0xFFB71C1C), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
              Text(
                if (serverStatus.isRunning) "● LISTENING :${serverStatus.port}" else "● STOPPED",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
              )
            }
          }
        },
        actions = {
          OutlinedButton(onClick = { showSettings = true }, modifier = Modifier.padding(end = 12.dp)) {
            Text("⚙ Connection")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF18181B)),
      )
    }
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
    androidx.compose.material3.TabRow(selectedTabIndex = tab, containerColor = Color(0xFF18181B)) {
      listOf("Status", "Home screen", "Muse", "Screensaver").forEachIndexed { i, label ->
        androidx.compose.material3.Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label, fontSize = 15.sp) })
      }
    }
    when (tab) {
      1 -> com.portal.pebblebridge.ui.settings.HomeSettingsPanel()
      2 -> com.portal.pebblebridge.ui.settings.MuseSettingsPanel()
      3 -> com.portal.pebblebridge.ui.settings.ScreensaverSettingsPanel()
      else -> Row(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // Left Panel: Connection Info & Controls (40% width)
      Column(
        modifier = Modifier
          .weight(0.4f)
          .fillMaxHeight()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        Card(
          colors = CardDefaults.cardColors(containerColor = Color(0xFF27272A)),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pebble App Connection", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFFE4E4E7))
            Text("Pebble app ➔ Index 01 Settings ➔ Webhook (Transcription only):", fontSize = 13.sp, color = Color(0xFFA1A1AA))
            Text("At home:", fontSize = 12.sp, color = Color(0xFFD4D4D8))
            Text(webhookUrl, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color(0xFF60A5FA))
            if (tailscaleIp != null) {
              Text("Anywhere (Tailscale):", fontSize = 12.sp, color = Color(0xFFD4D4D8))
              Text("http://$tailscaleIp:${serverStatus.port}/ingest", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color(0xFF60A5FA))
            }
            Text("MCP (for agents): $mcpUrl", fontSize = 11.sp, color = Color(0xFF71717A))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
              Button(
                onClick = { onCopyUrl(webhookUrl) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
              ) {
                Text("Copy URL")
              }
              OutlinedButton(onClick = onSendTestNote) {
                Text("Send Test Note")
              }
            }
          }
        }

        Card(
          colors = CardDefaults.cardColors(containerColor = Color(0xFF27272A)),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Bridge Status", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFFE4E4E7))
            Text("Total Notes Processed: ${serverStatus.totalNotesReceived}", fontSize = 13.sp, color = Color(0xFFA1A1AA))
            Text("Muse Destination: ${if (config.museSdkToken.isNotBlank()) "Cloud Stream" else "Portal Local Inbox"}", fontSize = 13.sp, color = Color(0xFFA1A1AA))
            if (!serverStatus.lastError.isNullOrBlank()) {
              Text("Notice: ${serverStatus.lastError}", fontSize = 12.sp, color = Color(0xFFF87171))
            }
          }
        }

        Card(
          colors = CardDefaults.cardColors(containerColor = Color(0xFF27272A)),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Meta Muse Home Link (BLE)", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFFE4E4E7))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Box(
                modifier = Modifier
                  .size(10.dp)
                  .background(if (isPaired) Color(0xFF34D399) else Color(0xFF60A5FA), CircleShape)
              )
              Text(
                if (isPaired) "Paired & Connected" else "Ready to Pair (Advertising)",
                fontSize = 13.sp,
                color = if (isPaired) Color(0xFF34D399) else Color(0xFF60A5FA),
                fontWeight = FontWeight.SemiBold
              )
            }
            Text(
              "Device: $bleDeviceName",
              fontSize = 12.sp,
              color = Color(0xFFA1A1AA)
            )
            OutlinedButton(
              onClick = { MuseBleManager.restartPairingMode() },
              modifier = Modifier.fillMaxWidth()
            ) {
              Text(if (isPaired) "Re-pair Meta Muse" else "Restart Pairing Mode", fontSize = 12.sp)
            }
          }
        }

        Card(
          colors = CardDefaults.cardColors(containerColor = Color(0xFF27272A)),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Meta Muse Cloud AI Link", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFFE4E4E7))
            val (statusText, statusColor) = when (museLinkState) {
              com.portal.pebblebridge.model.LinkState.CONNECTED_ONLINE -> Pair("Connected & Streaming (Noise WS)", Color(0xFF34D399))
              com.portal.pebblebridge.model.LinkState.CONNECTING_WS,
              com.portal.pebblebridge.model.LinkState.HANDSHAKING,
              com.portal.pebblebridge.model.LinkState.REGISTERING -> Pair("Connecting (${museLinkState.name})...", Color(0xFFFBBF24))
              com.portal.pebblebridge.model.LinkState.FETCHING_VM -> Pair("Leasing VM from Cloud...", Color(0xFFFBBF24))
              com.portal.pebblebridge.model.LinkState.ERROR -> Pair("Connection Error (Retrying...)", Color(0xFFF87171))
              com.portal.pebblebridge.model.LinkState.DISCONNECTED -> Pair("Offline (Awaiting Pairing)", Color(0xFFA1A1AA))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Box(
                modifier = Modifier
                  .size(10.dp)
                  .background(statusColor, CircleShape)
              )
              Text(
                statusText,
                fontSize = 13.sp,
                color = statusColor,
                fontWeight = FontWeight.SemiBold
              )
            }
            Text(
              "Node: ${BridgeRepository.nodeId}",
              fontSize = 12.sp,
              color = Color(0xFFA1A1AA)
            )
          }
        }

        Card(
          colors = CardDefaults.cardColors(containerColor = Color(0xFF27272A)),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            BridgeAvatar(modifier = Modifier.size(72.dp))
            Column {
              Text("Pixel Bot", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFFE4E4E7))
              Text("Lives on the home screen", fontSize = 12.sp, color = Color(0xFFA1A1AA))
              Text(
                if (serverStatus.isRunning) "● Ready for Pebble Notes" else "● Stopped",
                fontSize = 11.sp,
                color = if (serverStatus.isRunning) Color(0xFF34D399) else Color(0xFFF87171),
                fontWeight = FontWeight.SemiBold
              )
            }
          }
        }
      }

      // Right Panel: Live Feed (60% width)
      Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .weight(0.6f)
          .fillMaxHeight()
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text("Live Voice Notes Feed", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFFF4F4F5))
            if (notes.isNotEmpty()) {
              OutlinedButton(onClick = { BridgeRepository.clearNotes() }) {
                Text("Clear", fontSize = 12.sp)
              }
            }
          }

          Spacer(modifier = Modifier.height(12.dp))

          if (notes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BridgeAvatar(modifier = Modifier.size(160.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Waiting for your ring", fontSize = 18.sp, color = Color(0xFFF4F4F5), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                  "Speak into your Pebble Index ring or tap 'Send Test Note'",
                  fontSize = 13.sp,
                  color = Color(0xFFA1A1AA)
                )
              }
            }
          } else {
            LazyColumn(
              verticalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxSize()
            ) {
              items(notes, key = { it.id }) { note ->
                NoteItemView(note)
              }
            }
          }
        }
      }
    }
    }
    }
  }

  if (showSettings) {
    SettingsDialog(
      config = config,
      onDismiss = { showSettings = false },
      onSave = { updated ->
        BridgeRepository.updateConfig(updated)
        showSettings = false
      }
    )
  }

  pairingPrompt?.let { prompt ->
    PairingConfirmationDialog(
      prompt = prompt,
      onConfirm = {
        com.portal.pebblebridge.ble.MuseBleManager.userConfirmPairing()
      },
      onDecline = {
        com.portal.pebblebridge.ble.MuseBleManager.userDeclinePairing()
      }
    )
  }
}

@Composable
fun NoteItemView(note: VoiceNote) {
  val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
  val formattedTime = remember(note.timestampEpochMs) { timeFormat.format(Date(note.timestampEpochMs)) }

  Card(
    colors = CardDefaults.cardColors(containerColor = Color(0xFF27272A)),
    shape = RoundedCornerShape(8.dp),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(note.title ?: note.source, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF60A5FA))
        Text(formattedTime, fontSize = 11.sp, color = Color(0xFF71717A))
      }

      Text(note.text, fontSize = 15.sp, color = Color(0xFFF4F4F5))

      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        val (badgeBg, badgeText) = when (note.status) {
          NoteStatus.DELIVERED -> Color(0xFF065F46) to "✓ Delivered to Muse"
          NoteStatus.PENDING -> Color(0xFF854D0E) to "⏳ Sending..."
          NoteStatus.FAILED -> Color(0xFF991B1B) to "✕ Delivery Failed"
        }
        Box(modifier = Modifier.background(badgeBg, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
          Text(badgeText, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        if (!note.museReply.isNullOrBlank()) {
          Spacer(modifier = Modifier.width(8.dp))
          Text(note.museReply, fontSize = 11.sp, color = Color(0xFFA1A1AA), maxLines = 1)
        }
      }
    }
  }
}

@Composable
fun SettingsDialog(
  config: BridgeConfig,
  onDismiss: () -> Unit,
  onSave: (BridgeConfig) -> Unit,
) {
  var mcpToken by remember { mutableStateOf(config.mcpToken) }
  var developerSdkToken by remember { mutableStateOf(config.developerSdkToken) }
  var museSessionId by remember { mutableStateOf(config.museSessionId) }
  var museApiUrl by remember { mutableStateOf(config.museApiUrl) }
  val scrollState = rememberScrollState()

  Dialog(onDismissRequest = onDismiss) {
    Card(
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = Color(0xFF27272A)),
      modifier = Modifier
        .fillMaxWidth(0.9f)
        .padding(16.dp)
    ) {
      Column(
        modifier = Modifier
          .verticalScroll(scrollState)
          .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Text("Bridge Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)

        OutlinedTextField(
          value = mcpToken,
          onValueChange = { mcpToken = it },
          label = { Text("Pebble MCP Bearer Token (Optional)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
          value = developerSdkToken,
          onValueChange = { developerSdkToken = it },
          label = { Text("Muse Token (mgst_...)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
          value = museSessionId,
          onValueChange = { museSessionId = it },
          label = { Text("Muse Target Chat / Session ID (Optional)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
          value = museApiUrl,
          onValueChange = { museApiUrl = it },
          label = { Text("Muse API Base URL") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End
        ) {
          OutlinedButton(onClick = onDismiss, modifier = Modifier.padding(end = 8.dp)) {
            Text("Cancel")
          }
          Button(onClick = {
            onSave(
              config.copy(
                mcpToken = mcpToken.trim(),
                developerSdkToken = developerSdkToken.trim(),
                museSessionId = museSessionId.trim(),
                museApiUrl = museApiUrl.trim(),
              )
            )
          }) {
            Text("Save")
          }
        }
      }
    }
  }
}

/** Pulsing ring badge. Meta's Jollybot artwork is not open-source licensed, so it isn't bundled. */
@Composable
fun BridgeAvatar(modifier: Modifier = Modifier) {
  val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse").animateFloat(
    initialValue = 0.85f,
    targetValue = 1f,
    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
      animation = androidx.compose.animation.core.tween(1200),
      repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
    ),
    label = "scale",
  )
  androidx.compose.foundation.layout.BoxWithConstraints(
    modifier = modifier,
    contentAlignment = androidx.compose.ui.Alignment.Center,
  ) {
    val emojiSize = (maxWidth.value * 0.45f).sp
    androidx.compose.foundation.layout.Box(
      modifier = Modifier
        .fillMaxSize()
        .graphicsLayer { scaleX = pulse; scaleY = pulse }
        .background(Color(0xFF10B981), androidx.compose.foundation.shape.CircleShape),
      contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
      Text("💍", fontSize = emojiSize)
    }
  }
}

@Composable
fun PairingConfirmationDialog(
  prompt: com.portal.pebblebridge.state.BridgeRepository.PairingPrompt,
  onConfirm: () -> Unit,
  onDecline: () -> Unit,
) {
  Dialog(onDismissRequest = onDecline) {
    Card(
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
      border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF10B981)),
      modifier = Modifier
        .fillMaxWidth(0.85f)
        .padding(16.dp)
    ) {
      Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          BridgeAvatar(modifier = Modifier.size(56.dp))
          Column {
            Text(
              "🔗 Meta Muse Pairing Request",
              fontSize = 20.sp,
              fontWeight = FontWeight.Bold,
              color = Color.White
            )
            Text(
              "A mobile app is requesting to pair with this Portal",
              fontSize = 13.sp,
              color = Color(0xFFA1A1AA)
            )
          }
        }

        Card(
          colors = CardDefaults.cardColors(containerColor = Color(0xFF27272A)),
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(12.dp)) {
            Text("Bluetooth Peer:", fontSize = 11.sp, color = Color(0xFF71717A))
            Text(
              prompt.peerAddress.ifBlank { "Mobile Device (BLE Central)" },
              fontSize = 14.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.SemiBold,
              color = Color(0xFF60A5FA)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              "Tap 'Confirm Pairing' to authenticate the link and enable voice note streaming to Muse AI.",
              fontSize = 12.sp,
              color = Color(0xFFD4D4D8)
            )
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
        ) {
          OutlinedButton(
            onClick = onDecline,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
          ) {
            Text("Decline")
          }
          Button(
            onClick = onConfirm,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
          ) {
            Text("✓ Confirm Pairing", fontWeight = FontWeight.Bold, color = Color.White)
          }
        }
      }
    }
  }
}


/** The Portal's Tailscale address (100.64.0.0/10), if Tailscale is connected. */
private fun tailscaleAddress(): String? = try {
  java.net.NetworkInterface.getNetworkInterfaces().toList()
    .flatMap { it.inetAddresses.toList() }
    .filterIsInstance<java.net.Inet4Address>()
    .map { it.hostAddress ?: "" }
    .firstOrNull { ip ->
      val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
      parts.size == 4 && parts[0] == 100 && parts[1] in 64..127
    }
} catch (_: Exception) { null }
