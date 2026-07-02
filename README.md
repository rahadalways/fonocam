# 📸 CamConnect

Tomar phone ke banao PC-r HD webcam — nijer banano DroidCam alternative.

**Duita part:**

| Part | Ki | Kothay |
|------|-----|--------|
| 📱 Mobile app | Camera stream kore (simple UI — start/stop, flip, torch) | `app/` (Android, Kotlin) |
| 🖥️ PC software | Sob control + Virtual Webcam (Zoom/Meet e camera hishebe dekhay) | `CamConnect_Desktop.py` |

---

## 🖥️ PC software chalano

1. Python 3.12 + packages lagbe (ei PC te already install kora ache):
   ```
   pip install customtkinter opencv-python pyvirtualcam pillow numpy
   ```
2. **`Run_CamConnect_Desktop.bat`** double-click koro
3. Phone er IP + PIN diye **Connect** chapo
4. **Start Virtual Webcam** chapo → Zoom/Meet/Discord e "OBS Virtual Camera" select koro

> Virtual webcam er jonno OBS Studio ekbar install kora lage (free, obsproject.com) — eta driver ta dey.

### USB diye connect
1. Phone e **Developer Options → USB debugging** on koro
2. Cable lagao, PC app e **🔌 USB** chapo — bas

## 📱 Mobile app banano (APK)

1. [Android Studio](https://developer.android.com/studio) install koro
2. **Open** → ei folder ta select koro
3. Phone lagiye **Run ▶** chapo (ba **Build → Build APK**)

App e ekta boro Start button — chaple screen e IP:Port ar PIN dekhabe, oitai PC te dibe.

## 🔒 Security
- Prottek bar app khulle notun random 4-digit PIN hoy
- PIN chhara keu stream dekhte parbe na

## 📝 Baki plan (v2)
- Phone er mic → PC te microphone
- Auto-discovery (IP type kora lagbe na)
- H.264 stream (aro kom latency)
- PyInstaller diye CamConnect.exe installer
