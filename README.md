# iSpy Camera

LAN baby-monitor **camera** app for Android (Galaxy S9+ and similar). This phone captures video + one-way audio, hosts signaling, and streams to a browser on the same Wi‑Fi. Screen can be off while streaming.

## Features

- WebRTC video (prefer HW H.264) + microphone audio (camera → viewer only)
- Embedded HTTP + WebSocket signaling on the phone (`:8765`)
- Shared PIN gate before SDP exchange
- Foreground service + partial wake lock (stream continues with screen locked)
- Portrait and landscape
- Minimal HTML test viewer served from the phone

## Requirements

- Android 9+ (minSdk 28), targetSdk 35
- Same Wi‑Fi LAN for phone and viewer device
- Camera, microphone, and notification permissions

## Build & install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Use on device

1. Open **iSpy Camera** and grant Camera, Microphone, and Notifications.
2. Tap **Start streaming**. A persistent notification shows status, LAN URL, and PIN.
3. Note the **PIN** and **LAN URL** (e.g. `http://192.168.1.42:8765/`) on the screen or notification.
4. On another device on the same Wi‑Fi, open that URL in Chrome/Safari/Firefox.
5. Enter the PIN and tap **Connect**. You should see live video and hear audio.
6. Lock the camera phone — preview stops; the stream should continue.
7. Tap **Stop streaming** (or the notification Stop action) when done.

### Signaling protocol (WebSocket `/ws`)

JSON text frames:

| Direction       | Type                | Payload                                                       |
| --------------- | ------------------- | ------------------------------------------------------------- | ------------- |
| viewer → camera | `hello`             | `{ "role": "viewer", "pin": "1234" }`                         |
| camera → viewer | `welcome` / `error` | `{ "type": "welcome" }` or `{ "type":"error","code":"bad_pin" | "busy",... }` |
| camera → viewer | `offer`             | `{ "sdp": "..." }`                                            |
| viewer → camera | `answer`            | `{ "sdp": "..." }`                                            |
| both            | `ice`               | `{ "candidate", "sdpMid", "sdpMLineIndex" }`                  |

Single viewer only in v1. Kill and reopen the viewer to reconnect without restarting the camera app.

## Overnight defaults

- ~1280×720, 15 fps, ~1.2 Mbps
- Preview surface detached when the UI is paused
- `PARTIAL_WAKE_LOCK` only (no keep-screen-on in background)

## Project layout

```text
com.ispy.camera/
  MainActivity.kt
  service/CameraStreamService.kt
  camera/CameraController.kt
  webrtc/WebRtcClient.kt
  signaling/SignalingServer.kt
  signaling/SignalingMessages.kt
  debug/FrameInspector.kt   # optional learning/debug
  debug/H264Encoder.kt      # optional learning/debug
```

## Out of scope (this pass)

- React Native viewer app
- mDNS discovery
- Talk-back (return audio)
- Play Store distribution
