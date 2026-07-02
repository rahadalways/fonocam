package com.example

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.util.Collections

// ---------- palette (matches CamConnect Desktop) ----------
private val Bg = Color(0xFF14181D)
private val Panel = Color(0xFF1C2229)
private val Panel2 = Color(0xFF232B34)
private val Line = Color(0xFF2E3742)
private val TextC = Color(0xFFE8ECEF)
private val Muted = Color(0xFF97A3AD)
private val Accent = Color(0xFFF2A93B)
private val Live = Color(0xFFE5484D)
private val Ok = Color(0xFF4CC38A)

private const val PREFS_NAME = "camconnect_prefs"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    CamConnectApp(prefs)
                }
            }
        }
    }
}

@Composable
fun CamConnectApp(prefs: SharedPreferences) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // ---- persisted settings ----
    var serverPort by remember { mutableStateOf(prefs.getInt("port", 8080)) }
    var serverPin by remember {
        mutableStateOf(prefs.getString("pin", null) ?: (1000..9999).random().toString().also {
            prefs.edit().putString("pin", it).apply()
        })
    }
    var securityOn by remember { mutableStateOf(prefs.getBoolean("security", true)) }
    var resolutionName by remember { mutableStateOf(prefs.getString("resolution", "720p") ?: "720p") }
    var autoDimOn by remember { mutableStateOf(prefs.getBoolean("auto_dim", true)) }

    val targetResolution = remember(resolutionName) {
        when (resolutionName) {
            "1080p" -> android.util.Size(1920, 1080)
            "480p" -> android.util.Size(854, 480)
            else -> android.util.Size(1280, 720)
        }
    }

    // ---- runtime state ----
    var isStreaming by remember { mutableStateOf(false) }
    var flashEnabled by remember { mutableStateOf(false) }
    var selectedCamera by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
    var zoomRatio by remember { mutableStateOf(1.0f) }
    var quality by remember { mutableStateOf(70) }
    var localIp by remember { mutableStateOf(getLocalIpAddress(context)) }
    var serverInstance by remember { mutableStateOf<WebcamHttpServer?>(null) }
    var pcConnected by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // battery saver
    var dimmed by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableStateOf(System.currentTimeMillis()) }

    fun wake() {
        lastTouch = System.currentTimeMillis()
        dimmed = false
    }

    // ---- permission ----
    var hasCamPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasCamPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCamPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // refresh IP every few seconds (WiFi can change)
    LaunchedEffect(Unit) {
        while (true) {
            localIp = getLocalIpAddress(context)
            delay(5000)
        }
    }

    // keep screen awake while streaming
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            dimmed = false
            pcConnected = false
        }
    }

    // auto-dim timer
    LaunchedEffect(isStreaming, lastTouch, autoDimOn, showSettings) {
        if (isStreaming && autoDimOn && !showSettings) {
            delay(30_000)
            dimmed = true
        }
    }

    // poll: PC connected?
    LaunchedEffect(isStreaming) {
        while (isStreaming) {
            pcConnected = serverInstance?.hasActiveClients() == true
            delay(1500)
        }
    }

    fun startServer() {
        if (serverInstance != null) return
        val server = WebcamHttpServer(port = serverPort) { action, value ->
            scope.launch(Dispatchers.Main) {
                when (action) {
                    "toggle-flash" -> flashEnabled = !flashEnabled
                    "switch-camera" -> selectedCamera =
                        if (selectedCamera == CameraSelector.DEFAULT_BACK_CAMERA)
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        else CameraSelector.DEFAULT_BACK_CAMERA
                    "zoom" -> value?.toFloatOrNull()?.let { zoomRatio = it.coerceIn(1f, 8f) }
                    "quality" -> value?.toIntOrNull()?.let { quality = it }
                }
            }
        }
        server.isSecurityEnabled = securityOn
        server.serverPin = serverPin
        server.flashState = flashEnabled
        server.zoomLevel = zoomRatio
        server.frameQuality = quality
        server.start()
        serverInstance = server
        isStreaming = true
    }

    fun stopServer() {
        serverInstance?.stop()
        serverInstance = null
        isStreaming = false
        flashEnabled = false
        zoomRatio = 1.0f
    }

    DisposableEffect(Unit) {
        onDispose { serverInstance?.stop() }
    }

    // keep server state in sync
    LaunchedEffect(flashEnabled) { serverInstance?.flashState = flashEnabled }
    LaunchedEffect(zoomRatio) { serverInstance?.zoomLevel = zoomRatio }
    LaunchedEffect(quality) { serverInstance?.frameQuality = quality }
    LaunchedEffect(securityOn, serverPin) {
        serverInstance?.isSecurityEnabled = securityOn
        serverInstance?.serverPin = serverPin
    }
    LaunchedEffect(selectedCamera) {
        serverInstance?.currentCamera =
            if (selectedCamera == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"
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
        // ---- camera viewfinder (fills the screen, pinch to zoom) ----
        if (hasCamPermission) {
            CameraPreviewContainer(
                selectedCamera = selectedCamera,
                flashEnabled = flashEnabled,
                zoomRatio = zoomRatio,
                targetResolution = targetResolution,
                quality = quality,
                shouldCapture = isStreaming,
                onFrameCaptured = { bytes -> serverInstance?.latestFrame = bytes },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, gestureZoom, _ ->
                            wake()
                            zoomRatio = (zoomRatio * gestureZoom).coerceIn(1f, 8f)
                        }
                    }
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Videocam, null, tint = Muted, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    "Camera permission lagbe.\nSettings theke allow kore dao.",
                    color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center
                )
            }
        }

        // ---- top bar: status chip + settings ----
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.size(44.dp))
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
                    fontFamily = FontFamily.Monospace,
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
                fontFamily = FontFamily.Monospace,
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
                        Toast.makeText(context, "Address copy hoyeche ✓", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "PC-TE EI THIKANA DAO · TAP = COPY",
                    color = Muted, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp
                )
                Text(
                    text = "$localIp:$serverPort",
                    color = TextC, fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (securityOn) "PIN  $serverPin" else "PIN OFF",
                    color = if (securityOn) Accent else Muted, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
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
                    enabled = hasCamPermission
                ) { wake(); flashEnabled = !flashEnabled }

                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .clip(CircleShape)
                        .background(if (isStreaming) Live else Accent)
                        .clickable(enabled = hasCamPermission) {
                            wake()
                            if (isStreaming) stopServer() else startServer()
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
                    enabled = hasCamPermission
                ) {
                    wake()
                    selectedCamera =
                        if (selectedCamera == CameraSelector.DEFAULT_BACK_CAMERA)
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        else CameraSelector.DEFAULT_BACK_CAMERA
                }
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
                        "Streaming cholche…",
                        color = Muted, fontSize = 14.sp, fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Battery saver on · screen e tap koro",
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
                pin = serverPin,
                onPinChange = {
                    serverPin = it
                    prefs.edit().putString("pin", it).apply()
                },
                securityOn = securityOn,
                onSecurityChange = {
                    securityOn = it
                    prefs.edit().putBoolean("security", it).apply()
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
    pin: String,
    onPinChange: (String) -> Unit,
    securityOn: Boolean,
    onSecurityChange: (Boolean) -> Unit,
    autoDimOn: Boolean,
    onAutoDimChange: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    var portText by remember { mutableStateOf(port.toString()) }
    var pinText by remember { mutableStateOf(pin) }

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
                    "⚠ Streaming cholche — Resolution/Port change korle Stop kore abar Start dio.",
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

            // security + pin
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingLabel("PIN SECURITY")
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = securityOn,
                        onCheckedChange = onSecurityChange,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Accent,
                            checkedThumbColor = Bg,
                            uncheckedTrackColor = Panel2,
                            uncheckedThumbColor = Muted,
                            uncheckedBorderColor = Line
                        )
                    )
                }
                if (securityOn) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = pinText,
                            onValueChange = { text ->
                                pinText = text.filter { it.isDigit() }.take(8)
                                if (pinText.length >= 4) onPinChange(pinText)
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = settingsFieldColors(),
                            modifier = Modifier.weight(1f)
                        )
                        // new random pin
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Panel2)
                                .border(1.dp, Line, RoundedCornerShape(10.dp))
                                .clickable {
                                    val newPin = (1000..9999).random().toString()
                                    pinText = newPin
                                    onPinChange(newPin)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Refresh, "New PIN", tint = Accent, modifier = Modifier.size(20.dp))
                        }
                    }
                    Text(
                        "PIN chhara keu stream dekhte parbe na.",
                        color = Muted, fontSize = 11.sp
                    )
                }
            }

            // battery saver
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SettingLabel("BATTERY SAVER")
                    Text(
                        "30s haat na dile screen kalo hoye jabe (stream choltei thakbe)",
                        color = Muted, fontSize = 11.sp, lineHeight = 15.sp
                    )
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

            Text(
                "Zoom, quality, torch — egulo PC software theke o control kora jay. Viewfinder e pinch korle zoom hoy.",
                color = Color(0xFF5A6570), fontSize = 11.sp, lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text = text,
        color = Muted, fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
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

// Live CameraX preview and stream frame grabber container
@Composable
fun CameraPreviewContainer(
    selectedCamera: CameraSelector,
    flashEnabled: Boolean,
    zoomRatio: Float,
    targetResolution: android.util.Size,
    quality: Int,
    shouldCapture: Boolean,
    onFrameCaptured: (ByteArray) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(flashEnabled, camera) {
        try {
            camera?.cameraControl?.enableTorch(flashEnabled)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    LaunchedEffect(zoomRatio, camera) {
        try {
            camera?.cameraControl?.setZoomRatio(zoomRatio)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val analysisExecutor = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }

    DisposableEffect(analysisExecutor) {
        onDispose { analysisExecutor.shutdown() }
    }

    LaunchedEffect(selectedCamera, targetResolution, quality, shouldCapture, analysisExecutor) {
        val cameraProvider = cameraProviderFuture.get()
        cameraProvider.unbindAll()

        // lock the feed to 16:9 so the stream is never square
        val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            )
            .setResolutionStrategy(
                androidx.camera.core.resolutionselector.ResolutionStrategy(
                    targetResolution,
                    androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(resolutionSelector)
            .build()

        var lastProcessedTime = 0L
        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            try {
                val now = System.currentTimeMillis()
                if (shouldCapture && (now - lastProcessedTime >= 33)) {
                    val jpegBytes = imageProxy.toJpegBytes(quality, "None")
                    if (jpegBytes != null) {
                        onFrameCaptured(jpegBytes)
                        lastProcessedTime = now
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                imageProxy.close()
            }
        }

        try {
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                selectedCamera,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
