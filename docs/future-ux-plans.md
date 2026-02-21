# NidsDePoule — Future UX Plans

This document captures ideas for future user interaction features. These are
**not yet implemented** but inform the architecture and data model decisions.

---

## 1. Voice Commands

The text of the four report buttons can serve as voice trigger phrases:

| Phrase | Meaning |
|---|---|
| **"Hiii !"** | Visually spotted a small/medium pothole |
| **"HIIIIIII !!!"** | Visually spotted a big pothole |
| **"Ouch !"** | Just hit a small/medium pothole |
| **"AYOYE !"** | Just hit a big pothole |

### Voice detection approach

- **First-run training**: On first launch the app plays each phrase and asks
  the user to repeat it. This records per-user voice templates.
- **Continuous listening**: While driving, a lightweight on-device keyword
  spotter runs in the background (similar to "Hey Google" wake-word detection).
  Candidates: Android `SpeechRecognizer`, TensorFlow Lite micro-models, or
  Vosk offline recognition.
- **Intonation / duration analysis**: The *loudness*, *pitch curve*, and
  *duration* of the voice command encode severity information beyond the word
  itself. A short flat "hiii" is different from a long alarmed "HIIIIIII!!!".
  This signal can feed into severity estimation alongside accelerometer data.
- **Combined button + voice training**: The app can prompt the user to press a
  button **and** say the phrase simultaneously. This provides paired
  (button-press, voice-clip) training data per user, which improves the
  keyword spotter over time.

---

## 2. Alerting the Driver

The driver must keep eyes on the road. Feedback must be **non-visual or
glanceable**.

### When the app detects a pothole (accelerometer or user input)

- **Haptic pulse** — Short vibration pattern confirming the report was sent.
- **Audio chime / voice confirmation** — e.g. a short "ding" or synthesised
  "Got it!".

### When approaching a known pothole (server-pushed or local cache)

- **Synthesised voice warning**: "Pothole ahead in 50 meters".
- **Celebrity / community voice packs** (fun): Warnings voiced by public
  personalities or by the last user who hit that pothole (opt-in recording).
- **Progressive audio cue**: Increasing-frequency beeps as distance closes,
  like a parking sensor.
- **Haptic escalation**: Gentle pulse → strong pulse as proximity decreases.

---

## 3. Map Overlay While Using Another Navigation App

The user will typically have Google Maps or Waze open. Ideas to still surface
pothole data:

- **Android overlay (SYSTEM_ALERT_WINDOW)**: Draw translucent red dots on top
  of any map app. Requires special permission but is the most direct approach.
- **Screen-tint flash**: Briefly flash the screen edges red/orange when a
  pothole is detected or when approaching one. Less intrusive than full
  overlay.
- **Notification heads-up**: A floating notification with a mini-map snippet
  showing nearby potholes. Works without special permissions.
- **Companion Wear OS / Android Auto surface**: For Android Auto users, show
  pothole warnings directly in the car's head unit.
- **Background audio-only mode**: When another app is in foreground, fall back
  to audio-only alerts (voice / chimes). No visual overlay needed.

---

## 4. More Report Buttons (Future)

The button panel is designed to be extensible. Possible additions:

| Button | Meaning |
|---|---|
| **"Partout !"** | Many potholes in this zone (area-level report) |
| **"Réparé !"** | A previously reported pothole appears to be fixed |
| **"Attention !"** | Generic road hazard (debris, flooding, etc.) |

The `ReportSource` enum on both client and server is open for extension.

---

## 5. Calibration Loop

The "Ouch !" and "AYOYE !" buttons capture 5 seconds of accelerometer data
**with explicit human-labelled severity**. Over time this creates a
per-device, per-vehicle labelled dataset that can:

1. **Tune the auto-detection threshold** per vehicle (a truck has different
   baseline vibration from a sedan).
2. **Train an ML classifier** that replaces the simple threshold detector with
   a model that accounts for speed, vehicle type, road surface, etc.
3. **Cross-correlate** visual reports ("Hiii !") that were immediately followed
   by an impact report from another device — confirming the pothole exists and
   measuring its severity from the second device's accelerometer.
