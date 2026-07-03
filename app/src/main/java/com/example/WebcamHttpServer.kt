package com.example

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WebcamHttpServer(
    private val port: Int = 8080,
    private val onAction: (String, String?) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    
    @Volatile
    var latestFrame: ByteArray? = null
    
    @Volatile
    var flashState = false
    
    @Volatile
    var currentCamera = "back" // "back" or "front"
    
    @Volatile
    var zoomLevel = 1.0f
    
    @Volatile
    var frameQuality = 70

    // sensor rotation in degrees; the PC client rotates the frame (much cheaper there)
    @Volatile
    var streamRotation = 0

    @Volatile
    var isSecurityEnabled = true

    @Volatile
    var serverPin = "1234"

    private val activeStreamsCount = java.util.concurrent.atomic.AtomicInteger(0)
    
    @Volatile
    private var lastRequestTime = 0L

    fun hasActiveClients(): Boolean {
        val now = System.currentTimeMillis()
        return activeStreamsCount.get() > 0 || (now - lastRequestTime < 4000)
    }

    fun start() {
        if (isRunning.getAndSet(true)) return
        
        executor.execute {
            try {
                serverSocket = ServerSocket(port)
                Log.d("WebcamHttpServer", "DroidCam Server started on port $port")
                
                while (isRunning.get()) {
                    val socket = serverSocket?.accept() ?: break
                    executor.execute { handleClient(socket) }
                }
            } catch (e: Exception) {
                Log.e("WebcamHttpServer", "Error in server socket loop", e)
            }
        }
    }
    
    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e("WebcamHttpServer", "Error closing server socket", e)
        }
        serverSocket = null
        latestFrame = null
    }
    
    private fun isAuthorized(params: Map<String, String?>): Boolean {
        if (!isSecurityEnabled) return true
        val pin = params["pin"] ?: params["token"]
        return pin == serverPin
    }

    private fun handleClient(socket: Socket) {
        try {
            val inputStream = socket.getInputStream()
            val writer = socket.getOutputStream()
            val reader = inputStream.bufferedReader()
            
            // Read HTTP request header safely
            val headerLine = reader.readLine() ?: return
            val parts = headerLine.split(" ")
            if (parts.size < 2) return
            
            val method = parts[0]
            val pathWithQuery = parts[1]
            
            // Read remaining headers
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty() || line == "\r") break
            }
            
            val path = pathWithQuery.split("?").first()
            val query = pathWithQuery.split("?").getOrNull(1)
            
            val params = query?.split("&")?.associate {
                val kv = it.split("=")
                kv.first() to kv.getOrNull(1)
            } ?: emptyMap()
            
            when {
                path == "/login" -> {
                    val pin = params["pin"] ?: params["token"]
                    if (pin == serverPin) {
                        serveJson(writer, """{"status": "ok", "message": "Success"}""")
                    } else {
                        serveJson(writer, """{"status": "error", "message": "Invalid PIN"}""")
                    }
                }
                path == "/" -> {
                    if (isAuthorized(params)) {
                        serveDashboard(writer)
                    } else {
                        serveLogin(writer)
                    }
                }
                path == "/video" -> {
                    if (isAuthorized(params)) {
                        serveVideo(writer)
                    } else {
                        serveUnauthorized(writer)
                    }
                }
                path.startsWith("/action/") -> {
                    if (isAuthorized(params)) {
                        val actionName = path.substringAfter("/action/")
                        val value = params["value"]
                        onAction(actionName, value)
                        serveJson(writer, """{"status": "ok", "action": "$actionName"}""")
                    } else {
                        serveUnauthorized(writer)
                    }
                }
                path == "/status" -> {
                    if (isAuthorized(params)) {
                        serveJson(writer, """{
                            "flash": $flashState,
                            "camera": "$currentCamera",
                            "zoom": $zoomLevel,
                            "quality": $frameQuality,
                            "rotation": $streamRotation
                        }""".trimIndent())
                    } else {
                        serveUnauthorized(writer)
                    }
                }
                path == "/snapshot" -> {
                    if (isAuthorized(params)) {
                        serveSnapshot(writer)
                    } else {
                        serveUnauthorized(writer)
                    }
                }
                else -> {
                    writer.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
        } catch (e: Exception) {
            // Client disconnected or connection reset - normal for MJPEG stream
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun serveUnauthorized(out: OutputStream) {
        val body = """{"status": "unauthorized", "message": "PIN required or invalid"}"""
        val header = """
            HTTP/1.1 401 Unauthorized
            Content-Type: application/json
            Content-Length: ${body.toByteArray().size}
            Access-Control-Allow-Origin: *
            Connection: close
            
            
        """.trimIndent().replace("\n", "\r\n")
        try {
            out.write(header.toByteArray())
            out.write(body.toByteArray())
            out.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun serveLogin(out: OutputStream) {
        val html = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Security Login - DroidCam Dashboard</title>
    <link href="https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@500;700&family=Plus+Jakarta+Sans:wght@400;500;600;700&display=swap" rel="stylesheet">
    <style>
        :root {
            --primary: #6750A4;
            --primary-light: #E8DDFF;
            --bg: #0F0E13;
            --card-bg: #1B1A21;
            --text: #E6E1E5;
            --text-secondary: #CAC4D0;
            --error: #EF4444;
        }
        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
            font-family: 'Plus Jakarta Sans', sans-serif;
        }
        body {
            background-color: var(--bg);
            color: var(--text);
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 24px;
        }
        .login-card {
            background-color: var(--card-bg);
            border-radius: 28px;
            padding: 40px 32px;
            width: 100%;
            max-width: 420px;
            border: 1px solid rgba(255, 255, 255, 0.05);
            text-align: center;
            box-shadow: 0 10px 30px rgba(0,0,0,0.5);
        }
        .lock-badge {
            width: 64px;
            height: 64px;
            background-color: rgba(103, 80, 164, 0.15);
            border: 1px solid rgba(103, 80, 164, 0.3);
            border-radius: 20px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 28px;
            margin: 0 auto 24px;
        }
        h1 {
            font-family: 'Space Grotesk', sans-serif;
            font-size: 24px;
            font-weight: 700;
            margin-bottom: 8px;
        }
        p {
            font-size: 14px;
            color: var(--text-secondary);
            margin-bottom: 24px;
            line-height: 20px;
        }
        .pin-input {
            width: 100%;
            background-color: rgba(255, 255, 255, 0.03);
            border: 1px solid rgba(255, 255, 255, 0.1);
            color: #fff;
            padding: 16px;
            font-size: 18px;
            border-radius: 14px;
            text-align: center;
            letter-spacing: 4px;
            font-weight: bold;
            margin-bottom: 16px;
            outline: none;
            transition: all 0.2s ease;
        }
        .pin-input:focus {
            border-color: var(--primary);
            background-color: rgba(103, 80, 164, 0.05);
            box-shadow: 0 0 10px rgba(103, 80, 164, 0.2);
        }
        .error-msg {
            color: var(--error);
            font-size: 13px;
            font-weight: 600;
            margin-bottom: 16px;
            display: none;
            background-color: rgba(239, 68, 68, 0.1);
            padding: 10px;
            border-radius: 8px;
            border: 1px solid rgba(239, 68, 68, 0.2);
        }
        .btn-submit {
            background-color: var(--primary);
            color: #fff;
            border: none;
            width: 100%;
            padding: 16px;
            font-size: 16px;
            font-weight: bold;
            border-radius: 14px;
            cursor: pointer;
            transition: all 0.2s ease;
        }
        .btn-submit:hover {
            opacity: 0.9;
            transform: translateY(-1px);
        }
    </style>
</head>
<body>
    <div class="login-card">
        <div class="lock-badge">🔒</div>
        <h1>Security PIN Required</h1>
        <p>This webcam stream is password-protected. Please enter the PIN shown on your phone screen to connect.</p>
        
        <div id="error" class="error-msg">Invalid PIN. Please try again!</div>
        
        <input type="text" id="pin" class="pin-input" placeholder="••••" maxlength="8" autocomplete="off" autofocus>
        <button class="btn-submit" onclick="verifyPin()">Unlock Dashboard</button>
    </div>

    <script>
        // Check if PIN already stored
        const storedPin = localStorage.getItem('droidcam_pin');
        if (storedPin && !window.location.search.includes('pin=')) {
            window.location.href = window.location.pathname + '?pin=' + storedPin;
        }

        async function verifyPin() {
            const pinVal = document.getElementById('pin').value.trim();
            const errorDiv = document.getElementById('error');
            errorDiv.style.display = 'none';
            
            if (!pinVal) return;
            
            try {
                const res = await fetch('/login?pin=' + encodeURIComponent(pinVal));
                const data = await res.json();
                if (data.status === 'ok') {
                    localStorage.setItem('droidcam_pin', pinVal);
                    window.location.href = window.location.pathname + '?pin=' + pinVal;
                } else {
                    errorDiv.style.display = 'block';
                }
            } catch (err) {
                errorDiv.innerText = 'Network error. Please try again!';
                errorDiv.style.display = 'block';
            }
        }

        // Enter key trigger
        document.getElementById('pin').addEventListener('keypress', function(e) {
            if (e.key === 'Enter') {
                verifyPin();
            }
        });
    </script>
</body>
</html>
        """.trimIndent()

        val response = """
            HTTP/1.1 200 OK
            Content-Type: text/html; charset=UTF-8
            Content-Length: ${html.toByteArray().size}
            Access-Control-Allow-Origin: *
            Connection: close
            
            
        """.trimIndent().replace("\n", "\r\n")
        try {
            out.write(response.toByteArray())
            out.write(html.toByteArray())
            out.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun serveDashboard(out: OutputStream) {
        val html = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>DroidCam Web Dashboard - Webcam Link</title>
    <link href="https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;500;700&family=Plus+Jakarta+Sans:wght@300;400;500;600;700&display=swap" rel="stylesheet">
    <style>
        :root {
            --primary: #6750A4;
            --primary-bg: #F3EDF7;
            --bg: #0F0E13;
            --card-bg: #1B1A21;
            --text: #E6E1E5;
            --text-secondary: #CAC4D0;
            --success: #48A673;
        }
        
        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
            font-family: 'Plus Jakarta Sans', sans-serif;
        }
        
        body {
            background-color: var(--bg);
            color: var(--text);
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            align-items: center;
            padding: 24px;
        }
        
        header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            width: 100%;
            max-width: 1100px;
            margin-bottom: 24px;
            padding: 16px 24px;
            background-color: var(--card-bg);
            border-radius: 20px;
            border: 1px solid rgba(255, 255, 255, 0.05);
        }
        
        .logo-container {
            display: flex;
            align-items: center;
            gap: 12px;
        }
        
        .logo-icon {
            font-size: 24px;
            width: 44px;
            height: 44px;
            background-color: var(--primary);
            border-radius: 12px;
            display: flex;
            align-items: center;
            justify-content: center;
        }
        
        h1 {
            font-family: 'Space Grotesk', sans-serif;
            font-size: 22px;
            font-weight: 700;
        }
        
        .status-badge {
            background-color: rgba(72, 166, 115, 0.15);
            color: var(--success);
            padding: 6px 12px;
            border-radius: 20px;
            font-size: 13px;
            font-weight: 600;
            display: flex;
            align-items: center;
            gap: 8px;
        }
        
        .status-dot {
            width: 8px;
            height: 8px;
            background-color: var(--success);
            border-radius: 50%;
            box-shadow: 0 0 10px var(--success);
            animation: pulse 1.5s infinite;
        }
        
        @keyframes pulse {
            0% { transform: scale(0.95); opacity: 0.5; }
            50% { transform: scale(1.1); opacity: 1; }
            100% { transform: scale(0.95); opacity: 0.5; }
        }
        
        .main-container {
            display: grid;
            grid-template-columns: 1fr;
            gap: 24px;
            width: 100%;
            max-width: 1100px;
        }
        
        @media (min-width: 768px) {
            .main-container {
                grid-template-columns: 2.2fr 1fr;
            }
        }
        
        .video-card {
            background-color: var(--card-bg);
            border-radius: 24px;
            padding: 16px;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            border: 1px solid rgba(255, 255, 255, 0.05);
            min-height: 480px;
            position: relative;
            overflow: hidden;
        }
        
        .video-container {
            width: 100%;
            height: 100%;
            display: flex;
            align-items: center;
            justify-content: center;
            border-radius: 16px;
            overflow: hidden;
            background-color: #000;
            max-height: 550px;
        }
        
        #stream {
            max-width: 100%;
            max-height: 100%;
            object-fit: contain;
            transition: transform 0.3s ease;
        }
        
        .control-card {
            background-color: var(--card-bg);
            border-radius: 24px;
            padding: 24px;
            border: 1px solid rgba(255, 255, 255, 0.05);
            display: flex;
            flex-direction: column;
            gap: 20px;
        }
        
        .control-section {
            display: flex;
            flex-direction: column;
            gap: 10px;
        }
        
        .control-title {
            font-size: 13px;
            font-weight: 700;
            color: var(--text-secondary);
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }
        
        .btn-group {
            display: grid;
            grid-template-columns: repeat(2, 1fr);
            gap: 10px;
        }
        
        button.btn {
            background-color: rgba(255, 255, 255, 0.05);
            color: var(--text);
            border: 1px solid rgba(255, 255, 255, 0.1);
            padding: 12px;
            border-radius: 12px;
            cursor: pointer;
            font-weight: 600;
            font-size: 14px;
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 8px;
            transition: all 0.2s ease;
        }
        
        button.btn:hover {
            background-color: var(--primary);
            color: #fff;
            border-color: var(--primary);
        }
        
        button.btn.active {
            background-color: var(--primary);
            color: #fff;
            border-color: var(--primary);
        }
        
        .slider-container {
            display: flex;
            align-items: center;
            gap: 12px;
            background-color: rgba(255, 255, 255, 0.03);
            padding: 12px;
            border-radius: 12px;
            border: 1px solid rgba(255, 255, 255, 0.05);
        }
        
        input[type="range"] {
            flex: 1;
            accent-color: var(--primary);
            cursor: pointer;
        }
        
        .slider-value {
            font-weight: bold;
            font-size: 14px;
            min-width: 36px;
            text-align: right;
        }
        
        .shortcut-banner {
            width: 100%;
            max-width: 1100px;
            margin-top: 24px;
            padding: 16px 24px;
            background-color: rgba(103, 80, 164, 0.1);
            border-radius: 16px;
            border: 1px solid rgba(103, 80, 164, 0.2);
            font-size: 14px;
            color: var(--text-secondary);
            display: flex;
            align-items: center;
            gap: 12px;
        }
        
        .shortcut-key {
            background-color: rgba(255, 255, 255, 0.1);
            color: var(--text);
            padding: 4px 8px;
            border-radius: 6px;
            font-weight: bold;
            font-size: 12px;
            border: 1px solid rgba(255, 255, 255, 0.15);
        }
    </style>
</head>
<body>
    <header>
        <div class="logo-container">
            <div class="logo-icon">📸</div>
            <div>
                <h1>DroidCam Dashboard</h1>
                <p style="font-size: 12px; color: var(--text-secondary)">Webcam Link Direct Streaming</p>
            </div>
        </div>
        <div class="status-badge">
            <div class="status-dot"></div>
            Connected
        </div>
    </header>
    
    <div class="main-container">
        <div class="video-card">
            <div class="video-container">
                <img id="stream" src="/video" alt="Live Webcam Stream">
            </div>
        </div>
        
        <div class="control-card">
            <div class="control-section">
                <span class="control-title">Camera Toggle</span>
                <div class="btn-group">
                    <button class="btn" onclick="toggleFlash()" id="flash-btn">🔦 Torch: Off</button>
                    <button class="btn" onclick="switchCamera()" id="cam-btn">🔄 Switch Cam</button>
                </div>
            </div>
            
            <div class="control-section">
                <span class="control-title">Zoom Factor</span>
                <div class="slider-container">
                    <span>🔍</span>
                    <input type="range" id="zoom" min="1" max="8" step="0.1" value="1" oninput="setZoom(this.value)">
                    <span id="zoom-val" class="slider-value">1.0x</span>
                </div>
            </div>
            
            <div class="control-section">
                <span class="control-title">Rotate View</span>
                <div style="display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px;">
                    <button class="btn" style="padding: 8px 4px; font-size: 12px;" onclick="rotate(0)">0°</button>
                    <button class="btn" style="padding: 8px 4px; font-size: 12px;" onclick="rotate(90)">90°</button>
                    <button class="btn" style="padding: 8px 4px; font-size: 12px;" onclick="rotate(180)">180°</button>
                    <button class="btn" style="padding: 8px 4px; font-size: 12px;" onclick="rotate(270)">270°</button>
                </div>
            </div>

            <div class="control-section">
                <span class="control-title">Stream Actions</span>
                <div class="btn-group">
                    <button class="btn" onclick="takeSnapshot()">📸 Snapshot</button>
                    <button class="btn" onclick="toggleFullscreen()">📺 Fullscreen</button>
                </div>
            </div>

            <div class="control-section">
                <span class="control-title">Compression Quality</span>
                <div style="display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px;">
                    <button class="btn" id="q-low" onclick="setQuality(40)" style="padding: 8px 4px; font-size: 12px;">Low</button>
                    <button class="btn active" id="q-med" onclick="setQuality(70)" style="padding: 8px 4px; font-size: 12px;">Medium</button>
                    <button class="btn" id="q-high" onclick="setQuality(95)" style="padding: 8px 4px; font-size: 12px;">High</button>
                </div>
            </div>
        </div>
    </div>
    
    <div class="shortcut-banner">
        <span>💡</span>
        <span><strong>Pro Tip:</strong> Over USB cable, run <span class="shortcut-key">adb forward tcp:8080 tcp:8080</span> and open <span class="shortcut-key">http://localhost:8080</span> for super fast streaming.</span>
    </div>

    <script>
        let rotation = 0;
        const urlParams = new URLSearchParams(window.location.search);
        let pin = urlParams.get('pin') || localStorage.getItem('droidcam_pin') || '';
        
        if (pin) {
            localStorage.setItem('droidcam_pin', pin);
        }

        // Apply authorized stream source
        const streamImg = document.getElementById('stream');
        if (streamImg) {
            streamImg.src = getAuthUrl('/video');
        }

        function getAuthUrl(path, extraQuery = '') {
            let connector = path.includes('?') ? '&' : '?';
            let url = path;
            if (pin) {
                url += connector + 'pin=' + encodeURIComponent(pin);
                connector = '&';
            }
            if (extraQuery) {
                url += connector + extraQuery;
            }
            return url;
        }
        
        async function fetchStatus() {
            try {
                const res = await fetch(getAuthUrl('/status'));
                if (res.status === 401) {
                    localStorage.removeItem('droidcam_pin');
                    window.location.href = '/';
                    return;
                }
                const data = await res.json();
                document.getElementById('flash-btn').innerText = '🔦 Torch: ' + (data.flash ? 'On' : 'Off');
                document.getElementById('zoom').value = data.zoom;
                document.getElementById('zoom-val').innerText = data.zoom.toFixed(1) + 'x';
                
                // Set quality active states
                document.getElementById('q-low').classList.remove('active');
                document.getElementById('q-med').classList.remove('active');
                document.getElementById('q-high').classList.remove('active');
                if (data.quality <= 40) document.getElementById('q-low').classList.add('active');
                else if (data.quality >= 90) document.getElementById('q-high').classList.add('active');
                else document.getElementById('q-med').classList.add('active');
            } catch (err) {
                console.error('Error fetching status', err);
            }
        }
        
        async function runAction(name, value = '') {
            try {
                const query = value ? 'value=' + encodeURIComponent(value) : '';
                const res = await fetch(getAuthUrl('/action/' + name, query));
                if (res.status === 401) {
                    localStorage.removeItem('droidcam_pin');
                    window.location.href = '/';
                    return;
                }
                setTimeout(fetchStatus, 200);
            } catch (err) {
                console.error(err);
            }
        }
        
        function toggleFlash() {
            runAction('toggle-flash');
        }
        
        function switchCamera() {
            runAction('switch-camera');
        }
        
        function setZoom(val) {
            document.getElementById('zoom-val').innerText = parseFloat(val).toFixed(1) + 'x';
            runAction('zoom', val);
        }
        
        function setQuality(val) {
            runAction('quality', val);
        }
        
        function rotate(deg) {
            rotation = deg;
            const img = document.getElementById('stream');
            img.style.transform = 'rotate(' + deg + 'deg)';
            if (deg === 90 || deg === 270) {
                img.style.maxHeight = '80vw';
            } else {
                img.style.maxHeight = '100%';
            }
        }
        
        function takeSnapshot() {
            window.open(getAuthUrl('/snapshot'), '_blank');
        }
        
        function toggleFullscreen() {
            const container = document.querySelector('.video-container');
            if (!document.fullscreenElement) {
                container.requestFullscreen().catch(err => {
                    alert('Error attempting to enable full-screen mode: ' + err.message);
                });
            } else {
                document.exitFullscreen();
            }
        }
        
        // Refresh status on load
        fetchStatus();
        setInterval(fetchStatus, 3000);
    </script>
</body>
</html>
        """.trimIndent()
        
        val response = """
            HTTP/1.1 200 OK
            Content-Type: text/html; charset=UTF-8
            Content-Length: ${html.toByteArray().size}
            Access-Control-Allow-Origin: *
            Connection: close
            
            
        """.trimIndent().replace("\n", "\r\n")
        
        try {
            out.write(response.toByteArray())
            out.write(html.toByteArray())
            out.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun serveVideo(out: OutputStream) {
        lastRequestTime = System.currentTimeMillis()
        activeStreamsCount.incrementAndGet()
        try {
            out.write("""
                HTTP/1.1 200 OK
                Content-Type: multipart/x-mixed-replace; boundary=--frame
                Connection: keep-alive
                Cache-Control: no-cache, no-store, must-revalidate
                Pragma: no-cache
                Expires: 0
                Access-Control-Allow-Origin: *
                
                
            """.trimIndent().replace("\n", "\r\n").toByteArray())
            out.flush()
            
            var lastFrameRef: ByteArray? = null
            while (isRunning.get()) {
                val frame = latestFrame
                if (frame != null && frame !== lastFrameRef) {
                    val now = System.currentTimeMillis()
                    lastRequestTime = now
                    
                    try {
                        out.write("--frame\r\n".toByteArray())
                        out.write("Content-Type: image/jpeg\r\n".toByteArray())
                        out.write("Content-Length: ${frame.size}\r\n\r\n".toByteArray())
                        out.write(frame)
                        out.write("\r\n".toByteArray())
                        out.flush()
                        lastFrameRef = frame
                    } catch (e: Exception) {
                        break // Browser or client disconnected
                    }
                }
                Thread.sleep(5)
            }
        } catch (e: Exception) {
            // Browser disconnected, ignore and end stream loop gracefully
        } finally {
            activeStreamsCount.decrementAndGet()
        }
    }
    
    private fun serveSnapshot(out: OutputStream) {
        lastRequestTime = System.currentTimeMillis()
        val frame = latestFrame
        if (frame != null) {
            val header = """
                HTTP/1.1 200 OK
                Content-Type: image/jpeg
                Content-Length: ${frame.size}
                Access-Control-Allow-Origin: *
                Connection: close
                
                
            """.trimIndent().replace("\n", "\r\n")
            try {
                out.write(header.toByteArray())
                out.write(frame)
                out.flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\nNo stream frames captured yet.".toByteArray())
        }
    }
    
    private fun serveJson(out: OutputStream, json: String) {
        val header = """
            HTTP/1.1 200 OK
            Content-Type: application/json
            Content-Length: ${json.toByteArray().size}
            Access-Control-Allow-Origin: *
            Connection: close
            
            
        """.trimIndent().replace("\n", "\r\n")
        try {
            out.write(header.toByteArray())
            out.write(json.toByteArray())
            out.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// Crop bitmap helper function
fun cropBitmapToAspectRatio(bitmap: Bitmap, aspectRatio: String): Bitmap {
    val targetRatio = when (aspectRatio) {
        "16:9" -> 16f / 9f
        "9:16" -> 9f / 16f
        "4:3" -> 4f / 3f
        "1:1" -> 1f / 1f
        else -> return bitmap
    }
    
    val currentRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
    
    var newWidth = bitmap.width
    var newHeight = bitmap.height
    var xOffset = 0
    var yOffset = 0
    
    if (currentRatio > targetRatio) {
        newWidth = (bitmap.height * targetRatio).toInt()
        xOffset = (bitmap.width - newWidth) / 2
    } else if (currentRatio < targetRatio) {
        newHeight = (bitmap.width / targetRatio).toInt()
        yOffset = (bitmap.height - newHeight) / 2
    } else {
        return bitmap
    }
    
    if (newWidth <= 0 || newHeight <= 0) return bitmap
    
    return try {
        val cropped = Bitmap.createBitmap(bitmap, xOffset, yOffset, newWidth, newHeight)
        if (cropped != bitmap) {
            bitmap.recycle()
        }
        cropped
    } catch (e: Exception) {
        e.printStackTrace()
        bitmap
    }
}

// Highly efficient YUV/ARGB ImageProxy to JPEG converter.
// applyRotation = false skips the expensive decode-rotate-reencode pass;
// the PC client rotates instead using the /status "rotation" value.
fun ImageProxy.toJpegBytes(quality: Int = 70, aspectRatio: String = "None", applyRotation: Boolean = true): ByteArray? {
    try {
        if (format == ImageFormat.YUV_420_888) {
            val w = width
            val h = height
            
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]
            
            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            
            val yRowStride = yPlane.rowStride
            val uRowStride = uPlane.rowStride
            val vRowStride = vPlane.rowStride
            
            val yPixelStride = yPlane.pixelStride
            val uPixelStride = uPlane.pixelStride
            val vPixelStride = vPlane.pixelStride
            
            // Output NV21 format: first w*h bytes as Y, then interleaved V and U.
            val nv21 = ByteArray(w * h * 3 / 2)
            
            // 1. Copy Y plane, row by row if yRowStride != w or yPixelStride != 1
            if (yRowStride == w && yPixelStride == 1) {
                yBuffer.position(0)
                yBuffer.get(nv21, 0, w * h)
            } else {
                for (row in 0 until h) {
                    yBuffer.position(row * yRowStride)
                    if (yPixelStride == 1) {
                        yBuffer.get(nv21, row * w, w)
                    } else {
                        val rowOffset = row * w
                        for (col in 0 until w) {
                            nv21[rowOffset + col] = yBuffer.get(row * yRowStride + col * yPixelStride)
                        }
                    }
                }
            }
            
            // 2. Copy and Interleave U and V plane
            // NV21 interleaved chroma order: V, U, V, U...
            var outOffset = w * h
            for (row in 0 until h / 2) {
                for (col in 0 until w / 2) {
                    val vIndex = row * vRowStride + col * vPixelStride
                    val uIndex = row * uRowStride + col * uPixelStride
                    
                    if (outOffset + 1 < nv21.size) {
                        nv21[outOffset++] = vBuffer.get(vIndex)
                        nv21[outOffset++] = uBuffer.get(uIndex)
                    }
                }
            }
            
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, w, h), quality, out)
            val jpegBytes = out.toByteArray()
            
            val rotation = if (applyRotation) imageInfo.rotationDegrees else 0
            if (rotation != 0 || aspectRatio != "None") {
                var bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                if (bitmap != null) {
                    if (rotation != 0) {
                        val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        bitmap.recycle()
                        bitmap = rotatedBitmap
                    }
                    if (aspectRatio != "None") {
                        bitmap = cropBitmapToAspectRatio(bitmap, aspectRatio)
                    }
                    val rotatedOut = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, rotatedOut)
                    bitmap.recycle()
                    return rotatedOut.toByteArray()
                }
            }
            return jpegBytes
        } else {
            // RGBA_8888 fallback
            val buffer = planes[0].buffer
            var bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            
            val rotation = if (applyRotation) imageInfo.rotationDegrees else 0
            if (rotation != 0) {
                val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                bitmap.recycle()
                bitmap = rotated
            }
            if (aspectRatio != "None") {
                bitmap = cropBitmapToAspectRatio(bitmap, aspectRatio)
            }
            
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bitmap.recycle()
            return out.toByteArray()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
}
