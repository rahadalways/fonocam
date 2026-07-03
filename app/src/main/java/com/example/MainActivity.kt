package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.ui.theme.AppFont
import com.example.ui.theme.MonoFont
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface
import java.util.Collections

// ---------- palette (matches Fonocam Desktop) ----------
private val Bg = Color(0xFF14181D)
private val Panel = Color(0xFF1C2229)
private val Panel2 = Color(0xFF232B34)
private val Line = Color(0xFF2E3742)
private val TextC = Color(0xFFE8ECEF)
private val Muted = Color(0xFF97A3AD)
private val Accent = Color(0xFFF2A93B)
private val Live = Color(0xFFE5484D)
private val Ok = Color(0xFF4CC38A)

private const val PREFS_NAME = "fonocam_prefs"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    FonocamApp(prefs)
                }
            }
        }
    }
}

@Composable
fun FonocamApp(prefs: SharedPreferences) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val clipboard = LocalClipboardManager.current

    // ---- persisted settings ----
    var serverPort by remember { mutableStateOf(prefs.getInt("port", 8080)) }
    var resolutionName by remember { mutableStateOf(prefs.getString("resolution", "720p") ?: "720p") }
    var recQuality by remember { mutableStateOf(prefs.getString("rec_quality", "1080p") ?: "1080p") }
    var streamCodec by remember { mutableStateOf(prefs.getString("codec", "MJPEG") ?: "MJPEG") }
    var autoDimOn by remember { mutableStateOf(prefs.getBoolean("auto_dim", true)) }

    // ---- runtime state (mirrored from the service) ----
    var isStreaming by remember { mutableStateOf(StreamService.instance?.server != null) }
    var flashEnabled by remember { mutableStateOf(false) }
    var isRecordingPhone by remember { mutableStateOf(false) }
    var pcConnected by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableStateOf(1.0f) }
    var localIp by remember { mutableStateOf(getLocalIpAddress(context)) }
    var showSettings by remember { mutableStateOf(false) }

    // viewfinder frame (decoded from the service's latest JPEG)
    var frameBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    // battery saver
    var dimmed by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableStateOf(System.currentTimeMillis()) }

    fun wake() {
        lastTouch = System.currentTimeMillis()
        dimmed = false
    }

    // ---- permissions (camera + notifications) ----
    var hasCamPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> hasCamPermission = results[Manifest.permission.CAMERA] == true }

    LaunchedEffect(Unit) {
        val wanted = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) permissionLauncher.launch(wanted.toTypedArray())
    }

    // refresh IP every few seconds (WiFi can change)
    LaunchedEffect(Unit) {
        while (true) {
            localIp = getLocalIpAddress(context)
            delay(5000)
        }
    }

    // mirror service state into the UI
    LaunchedEffect(Unit) {
        while (true) {
            val svc = StreamService.instance
            isStreaming = svc?.server != null
            flashEnabled = svc?.flashOn ?: false
            isRecordingPhone = svc?.isRecording ?: false
            zoomRatio = svc?.zoomRatio ?: 1.0f
            pcConnected = svc?.server?.hasActiveClients() == true
            if (svc?.server?.startFailed == true) {
                context.stopService(Intent(context, StreamService::class.java))
                Toast.makeText(
                    context,
                    "Port $serverPort is already in use. Change the port in Settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
            delay(300)
        }
    }

    // viewfinder: decode the newest streamed frame ~15x/sec
    LaunchedEffect(isStreaming) {
        var lastRef: ByteArray? = null
        while (isStreaming) {
            val svc = StreamService.instance
            val bytes = svc?.server?.latestFrame
            if (bytes != null && bytes !== lastRef) {
                val rotation = svc?.server?.streamRotation ?: 0
                val bmp = withContext(Dispatchers.Default) {
                    try {
                        var b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (b != null && rotation != 0) {
                            val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                            val rotated = android.graphics.Bitmap.createBitmap(
                                b, 0, 0, b.width, b.height, m, true
                            )
                            if (rotated != b) b.recycle()
                            b = rotated
                        }
                        b
                    } catch (e: Exception) {
                        null
                    }
                }
                if (bmp != null) frameBitmap = bmp
                lastRef = bytes
            }
            delay(66)
        }
        frameBitmap = null
    }

    // keep screen awake while streaming and the app is open
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            dimmed = false
        }
    }

    // auto-dim timer
    LaunchedEffect(isStreaming, lastTouch, autoDimOn, showSettings) {
        if (isStreaming && autoDimOn && !showSettings) {
            delay(30_000)
            dimmed = true
        }
    }

    fun startStreaming() {
        ContextCompat.startForegroundService(context, Intent(context, StreamService::class.java))
    }

    fun stopStreaming() {
        context.stopService(Intent(context, StreamService::class.java))
    }

    // ---------------- UI ----------------
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { wake() }
    ) {
        // ---- viewfinder ----
        if (isStreaming && frameBitmap != null) {
            Image(
                bitmap = frameBitmap!!.asImageBitmap(),
                contentDescription = "Live camera",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, gestureZoom, _ ->
                            wake()
                            val z = ((StreamService.instance?.zoomRatio
                                ?: 1f) * gestureZoom).coerceIn(1f, 8f)
                            StreamService.instance?.setZoom(z)
                        }
                    }
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Videocam, null, tint = Muted, modifier = Modifier.size(52.dp))
                Spacer(Modifier.height(14.dp))
                Text(
                    text = if (hasCamPermission)
                        if (isStreaming) "Starting camera…"
                        else "Press Start to begin streaming"
                    else
                        "Camera permission is required.\nPlease allow it in system Settings.",
                    color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center
                )
            }
        }

        // ---- top bar: record + status chip + settings ----
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // backup recording on the phone
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isRecordingPhone) Live else Panel.copy(alpha = 0.85f))
                    .border(1.dp, if (isRecordingPhone) Live else Line, CircleShape)
                    .clickable(enabled = isStreaming) {
                        wake()
                        val svc = StreamService.instance ?: return@clickable
                        if (svc.isRecording) {
                            svc.stopRecording()
                            Toast.makeText(
                                context,
                                "Saved to Movies/Fonocam", Toast.LENGTH_LONG
                            ).show()
                        } else {
                            val ok = svc.startRecording()
                            Toast.makeText(
                                context,
                                if (ok) "Recording on phone…"
                                else "Recording is not supported on this device",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.FiberManualRecord, "Record on phone",
                    tint = if (isRecordingPhone) Color.White else if (isStreaming) Live else Muted,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            // status chip
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        when {
                            pcConnected -> Ok.copy(alpha = 0.92f)
                            isStreaming -> Live.copy(alpha = 0.9f)
                            else -> Panel.copy(alpha = 0.85f)
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isStreaming) Color.White else Muted)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        pcConnected -> "PC CONNECTED"
                        isStreaming -> "WAITING FOR PC"
                        else -> "OFFLINE"
                    },
                    color = if (isStreaming) Color.White else Muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = MonoFont,
                    letterSpacing = 1.5.sp
                )
            }
            Spacer(Modifier.weight(1f))
            // settings gear
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Panel.copy(alpha = 0.85f))
                    .border(1.dp, Line, CircleShape)
                    .clickable { wake(); showSettings = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Settings, "Settings", tint = TextC, modifier = Modifier.size(22.dp))
            }
        }

        // ---- zoom indicator ----
        if (zoomRatio > 1.05f) {
            Text(
                text = "${"%.1f".format(zoomRatio)}x",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = MonoFont,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 68.dp, end = 22.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Bg.copy(alpha = 0.7f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        // ---- bottom: address card + controls ----
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // address + PIN card (tap = copy)
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Bg.copy(alpha = 0.82f))
                    .border(1.dp, Line, RoundedCornerShape(16.dp))
                    .clickable {
                        wake()
                        clipboard.setText(AnnotatedString("$localIp:$serverPort"))
                        Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "AUTO-DETECTED ON PC · TAP TO COPY",
                    color = Muted, fontSize = 10.sp,
                    fontFamily = MonoFont, letterSpacing = 1.5.sp
                )
                Text(
                    text = "$localIp:$serverPort",
                    color = TextC, fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, fontFamily = MonoFont
                )
            }

            Spacer(Modifier.height(18.dp))

            // 3 buttons: torch · start/stop · flip
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                SideButton(
                    icon = if (flashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    active = flashEnabled,
                    enabled = isStreaming
                ) { wake(); StreamService.instance?.toggleFlash() }

                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .clip(CircleShape)
                        .background(if (isStreaming) Live else Accent)
                        .clickable(enabled = hasCamPermission) {
                            wake()
                            if (isStreaming) stopStreaming() else startStreaming()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isStreaming) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isStreaming) "Stop" else "Start",
                        tint = if (isStreaming) Color.White else Bg,
                        modifier = Modifier.size(38.dp)
                    )
                }

                SideButton(
                    icon = Icons.Default.Cameraswitch,
                    active = false,
                    enabled = isStreaming
                ) { wake(); StreamService.instance?.switchCamera() }
            }

            Spacer(Modifier.height(10.dp))
            AnimatedVisibility(visible = isStreaming, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    "You can close the app. Streaming keeps running in the background.",
                    color = Muted.copy(alpha = 0.8f), fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp)
                )
            }
        }

        // ---- battery saver overlay ----
        AnimatedVisibility(
            visible = dimmed && isStreaming && !showSettings,
            enter = fadeIn(), exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.96f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { wake() },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Live))
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Streaming in background…",
                        color = Muted, fontSize = 14.sp, fontFamily = MonoFont
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Battery saver · tap anywhere to wake",
                        color = Color(0xFF4A545E), fontSize = 12.sp
                    )
                }
            }
        }

        // ---- settings dialog ----
        if (showSettings) {
            SettingsDialog(
                isStreaming = isStreaming,
                resolutionName = resolutionName,
                onResolutionChange = {
                    resolutionName = it
                    prefs.edit().putString("resolution", it).apply()
                },
                port = serverPort,
                onPortChange = {
                    serverPort = it
                    prefs.edit().putInt("port", it).apply()
                },
                recQuality = recQuality,
                onRecQualityChange = {
                    recQuality = it
                    prefs.edit().putString("rec_quality", it).apply()
                },
                streamCodec = streamCodec,
                onStreamCodecChange = {
                    streamCodec = it
                    prefs.edit().putString("codec", it).apply()
                },
                autoDimOn = autoDimOn,
                onAutoDimChange = {
                    autoDimOn = it
                    prefs.edit().putBoolean("auto_dim", it).apply()
                },
                onClose = { wake(); showSettings = false }
            )
        }
    }
}

@Composable
private fun SettingsDialog(
    isStreaming: Boolean,
    resolutionName: String,
    onResolutionChange: (String) -> Unit,
    port: Int,
    onPortChange: (Int) -> Unit,
    recQuality: String,
    onRecQualityChange: (String) -> Unit,
    streamCodec: String,
    onStreamCodecChange: (String) -> Unit,
    autoDimOn: Boolean,
    onAutoDimChange: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    var portText by remember { mutableStateOf(port.toString()) }

    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Panel)
                .border(1.dp, Line, RoundedCornerShape(24.dp))
                .padding(22.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Settings",
                    color = TextC, fontSize = 20.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Panel2)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, "Close", tint = Muted, modifier = Modifier.size(18.dp))
                }
            }

            if (isStreaming) {
                Text(
                    "⚠ Streaming is live. Stop and start again after changing resolution or port.",
                    color = Accent, fontSize = 12.sp, lineHeight = 16.sp
                )
            }

            // resolution
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingLabel("CAMERA RESOLUTION")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("480p", "720p", "1080p").forEach { r ->
                        val selected = r == resolutionName
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) Accent else Panel2)
                                .border(
                                    1.dp, if (selected) Accent else Line,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { onResolutionChange(r) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                r,
                                color = if (selected) Bg else TextC,
                                fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // recording quality
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingLabel("RECORDING QUALITY")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("720p", "1080p", "4K").forEach { r ->
                        val selected = r == recQuality
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) Accent else Panel2)
                                .border(
                                    1.dp, if (selected) Accent else Line,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { onRecQualityChange(r) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                r,
                                color = if (selected) Bg else TextC,
                                fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // stream codec
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingLabel("STREAM CODEC")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("MJPEG", "H.264").forEach { c ->
                        val selected = c == streamCodec
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) Accent else Panel2)
                                .border(
                                    1.dp, if (selected) Accent else Line,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { onStreamCodecChange(c) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (c == "H.264") "H.264 HD" else c,
                                color = if (selected) Bg else TextC,
                                fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // port
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingLabel("PORT")
                OutlinedTextField(
                    value = portText,
                    onValueChange = { text ->
                        portText = text.filter { it.isDigit() }.take(5)
                        portText.toIntOrNull()?.let { if (it in 1024..65535) onPortChange(it) }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = settingsFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // battery saver
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SettingLabel("BATTERY SAVER")
                }
                Switch(
                    checked = autoDimOn,
                    onCheckedChange = onAutoDimChange,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Accent,
                        checkedThumbColor = Bg,
                        uncheckedTrackColor = Panel2,
                        uncheckedThumbColor = Muted,
                        uncheckedBorderColor = Line
                    )
                )
            }

            // docs link
            val docsCtx = LocalContext.current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Panel2)
                    .border(1.dp, Line, RoundedCornerShape(10.dp))
                    .clickable {
                        docsCtx.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://rahadalways.github.io/camconnect/")
                            )
                        )
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text("Read the docs", color = TextC, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("↗", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }

            // ---- app update ----
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingLabel("APP UPDATE")
                val ctx = LocalContext.current
                val scope = rememberCoroutineScope()
                val currentVersion = remember {
                    try {
                        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
                    } catch (e: Exception) {
                        "?"
                    }
                }
                var checking by remember { mutableStateOf(false) }
                var updateInfo by remember { mutableStateOf<Pair<String, String>?>(null) }
                var statusMsg by remember { mutableStateOf<String?>(null) }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "v$currentVersion",
                        color = Muted, fontSize = 12.sp, fontFamily = MonoFont
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Panel2)
                            .border(1.dp, Line, RoundedCornerShape(10.dp))
                            .clickable(enabled = !checking) {
                                checking = true
                                statusMsg = null
                                updateInfo = null
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val txt = java.net.URL(
                                            "https://api.github.com/repos/rahadalways/camconnect/releases/latest"
                                        ).readText()
                                        val jo = org.json.JSONObject(txt)
                                        val tag = jo.getString("tag_name").removePrefix("v")
                                        var url = jo.optString("html_url")
                                        val assets = jo.optJSONArray("assets")
                                        if (assets != null) {
                                            for (i in 0 until assets.length()) {
                                                val a = assets.getJSONObject(i)
                                                if (a.getString("name").endsWith(".apk")) {
                                                    url = a.getString("browser_download_url")
                                                    break
                                                }
                                            }
                                        }
                                        withContext(Dispatchers.Main) {
                                            checking = false
                                            if (tag != currentVersion) updateInfo = tag to url
                                            else statusMsg = "You have the latest version."
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            checking = false
                                            statusMsg = "Could not check for updates. Check your internet."
                                        }
                                    }
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            if (checking) "Checking…" else "Check for update",
                            color = TextC, fontSize = 12.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
                updateInfo?.let { (tag, url) ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Accent)
                            .clickable {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                )
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Download v$tag",
                            color = Bg, fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
                statusMsg?.let {
                    Text(it, color = Muted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text = text,
        color = Muted, fontSize = 11.sp,
        fontFamily = MonoFont,
        fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp
    )
}

@Composable
private fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextC,
    unfocusedTextColor = TextC,
    focusedContainerColor = Panel2,
    unfocusedContainerColor = Panel2,
    focusedBorderColor = Accent,
    unfocusedBorderColor = Line,
    cursorColor = Accent
)

@Composable
private fun SideButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(if (active) Accent else Panel.copy(alpha = 0.85f))
            .border(1.dp, if (active) Accent else Line, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) Bg else TextC,
            modifier = Modifier.size(24.dp)
        )
    }
}

// Fetches the active IPv4 address of the device
fun getLocalIpAddress(context: Context): String {
    try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiInfo = wifiManager?.connectionInfo
        val ipAddress = wifiInfo?.ipAddress
        if (ipAddress != null && ipAddress != 0) {
            return Formatter.formatIpAddress(ipAddress)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    try {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (intf in interfaces) {
            val addrs = Collections.list(intf.inetAddresses)
            for (addr in addrs) {
                if (!addr.isLoopbackAddress) {
                    val sAddr = addr.hostAddress ?: continue
                    if (sAddr.indexOf(':') < 0) return sAddr
                }
            }
        }
    } catch (ex: Exception) {
        ex.printStackTrace()
    }
    return "127.0.0.1"
}
