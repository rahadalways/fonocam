package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.view.WindowManager
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
private val Line = Color(0xFF2E3742)
private val TextC = Color(0xFFE8ECEF)
private val Muted = Color(0xFF97A3AD)
private val Accent = Color(0xFFF2A93B)
private val Live = Color(0xFFE5484D)
private val Ok = Color(0xFF4CC38A)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    CamConnectApp()
                }
            }
        }
    }
}

@Composable
fun CamConnectApp() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val scope = rememberCoroutineScope()

    // ---- state ----
    var isStreaming by remember { mutableStateOf(false) }
    var flashEnabled by remember { mutableStateOf(false) }
    var selectedCamera by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
    var zoomRatio by remember { mutableStateOf(1.0f) }
    var quality by remember { mutableStateOf(70) }
    var localIp by remember { mutableStateOf(getLocalIpAddress(context)) }
    val serverPort = 8080
    val serverPin = remember { (1000..9999).random().toString() }
    var serverInstance by remember { mutableStateOf<WebcamHttpServer?>(null) }

    // battery saver: dim the screen after 30s of streaming with no touch
    var dimmed by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableStateOf(System.currentTimeMillis()) }

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
        }
    }

    // auto-dim timer
    LaunchedEffect(isStreaming, lastTouch) {
        if (isStreaming) {
            delay(30_000)
            dimmed = true
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
                    "zoom" -> value?.toFloatOrNull()?.let { zoomRatio = it }
                    "quality" -> value?.toIntOrNull()?.let { quality = it }
                }
            }
        }
        server.isSecurityEnabled = true
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
            ) {
                lastTouch = System.currentTimeMillis()
                dimmed = false
            }
    ) {
        // ---- camera viewfinder (fills the screen) ----
        if (hasCamPermission) {
            CameraPreviewContainer(
                selectedCamera = selectedCamera,
                flashEnabled = flashEnabled,
                zoomRatio = zoomRatio,
                quality = quality,
                shouldCapture = isStreaming,
                onFrameCaptured = { bytes -> serverInstance?.latestFrame = bytes },
                modifier = Modifier.fillMaxSize()
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

        // ---- top status chip ----
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (isStreaming) Live.copy(alpha = 0.9f) else Panel.copy(alpha = 0.85f))
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
                text = if (isStreaming) "STREAMING" else "OFFLINE",
                color = if (isStreaming) Color.White else Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.5.sp
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
            // address + PIN card
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Bg.copy(alpha = 0.82f))
                    .border(1.dp, Line, RoundedCornerShape(16.dp))
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "PC-TE EI THIKANA DAO",
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
                    text = "PIN  $serverPin",
                    color = Accent, fontSize = 15.sp,
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
                ) { flashEnabled = !flashEnabled }

                // big start/stop
                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .clip(CircleShape)
                        .background(if (isStreaming) Live else Accent)
                        .clickable(enabled = hasCamPermission) {
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
                    selectedCamera =
                        if (selectedCamera == CameraSelector.DEFAULT_BACK_CAMERA)
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        else CameraSelector.DEFAULT_BACK_CAMERA
                }
            }
        }

        // ---- battery saver overlay ----
        AnimatedVisibility(visible = dimmed && isStreaming, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.96f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        lastTouch = System.currentTimeMillis()
                        dimmed = false
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(10.dp).clip(CircleShape).background(Live)
                    )
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
    }
}

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
    quality: Int,
    shouldCapture: Boolean,
    onFrameCaptured: (ByteArray) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val targetResolutionSize = remember { android.util.Size(1280, 720) }

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

    LaunchedEffect(selectedCamera, quality, shouldCapture, analysisExecutor) {
        val cameraProvider = cameraProviderFuture.get()
        cameraProvider.unbindAll()

        val preview = Preview.Builder()
            .setTargetResolution(targetResolutionSize)
            .build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(targetResolutionSize)
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
