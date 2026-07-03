# CamConnect

Use your Android phone as a high-quality PC webcam - over WiFi or USB.

| Part | What it does | Where |
|------|--------------|-------|
| Mobile app | Streams the camera (simple UI: start/stop, flip, torch) | `app/` (Android, Kotlin) |
| PC app | Full control + virtual webcam for Zoom/Meet/OBS | `CamConnect_Desktop.py` |

## PC app

1. Install Python 3.12 and the packages:
   ```
   pip install customtkinter opencv-python pyvirtualcam pillow numpy
   ```
2. Double-click **`Run_CamConnect_Desktop.bat`**
3. Your phone appears automatically in the device list - press **Connect**
4. Press **Start Virtual Webcam** and pick "OBS Virtual Camera" in Zoom/Meet/Discord

> The virtual webcam needs the OBS Virtual Camera driver - install OBS Studio once (free, obsproject.com).

### USB connection
1. Enable **Developer Options → USB debugging** on the phone
2. Plug in the cable, press **USB** in the PC app

## Mobile app

Download the latest APK from the [Releases page](https://github.com/rahadalways/camconnect/releases) and install it. After that the app updates itself: **Settings → Check for update**.

Features: background streaming (keeps running when you leave the app), battery saver, pinch zoom, on-phone backup recording, auto-discovery.

## Building the APK

Every push to `main` builds a signed release APK on GitHub Actions and publishes it as a release. No local Android SDK needed.

## Roadmap
- Phone mic → PC microphone
- H.264 streaming (lower latency, real 1080p)
- PyInstaller CamConnect.exe installer
