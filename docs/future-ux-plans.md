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

---

## 6. Dev Mode — Simulation & Mock GPS

During development we need to test the full pipeline without actually driving.
Dev mode provides two simulation capabilities:

### 6.1 Table-tap mode (no car required)

When dev mode is enabled, the developer can simulate pothole hits by **tapping
the phone on a desk or table**. The accelerometer spike from a table tap is
much smaller than a real pothole, so dev mode lowers the detection thresholds
dramatically.

- **Purpose**: Quick iteration on the detection → reporting → server → dashboard
  pipeline without leaving the office.
- **Data isolation**: Accelerometer patterns collected in dev mode are tagged
  `dev_mode: true` and should **not** pollute production calibration datasets.
  They serve only the short-term development effort.

### 6.2 Simulated driving circuits (mock GPS)

The app plays back a pre-recorded GPS trace in a **60-second loop**, feeding
mock `Location` objects to the rest of the app as if the phone were moving.

Two circuits are planned, both using real Montreal roads:

| Circuit | Character | Example route |
|---|---|---|
| **City (with stops)** | Urban streets, traffic lights, 30–50 km/h, frequent stops | Rue Saint-Denis → Rue Sherbrooke → Boulevard Saint-Laurent loop |
| **Highway** | Autoroute, steady 100 km/h, no stops | A-40 (Métropolitaine) eastbound section |

#### Mock GPS implementation

- Use Android's **`FusedLocationProviderClient` test API** or a
  **`LocationManager.setTestProvider()`** to inject simulated positions.
- The simulated positions must be **real coordinates on real Montreal roads** so
  that Google Maps and Waze, if open in split-screen, display the simulated
  position on actual streets and react to the movement (route updates, traffic
  layer, etc.).
- Speed, bearing, and accuracy fields in each mock `Location` must be
  realistic for the circuit type.
- The 60-second loop restarts seamlessly — the last point connects back to the
  first point.

#### Tricking Google Maps / Waze

Android's mock location provider mechanism (`Settings → Developer options →
Select mock location app`) allows one app to override the system location for
all other apps. This means:

1. NidsDePoule registers itself as the mock location provider.
2. It injects GPS coordinates along the chosen circuit.
3. Google Maps / Waze see these injected coordinates and navigate accordingly.
4. The developer can visually confirm that the pothole overlay or audio alerts
   trigger at the right map positions.

> **Note**: Some apps detect mock locations via `Location.isFromMockProvider()`.
> This is fine for dev testing but should be disabled for production builds.

#### Circuit selection

The circuit choice can be discussed. Criteria:

- Roads should have a **mix of smooth and rough sections** so simulated
  potholes feel plausible at specific points.
- The city circuit should include at least one **stop sign and one traffic
  light** so speed variation is realistic.
- The highway circuit should include a **gentle curve** so bearing changes are
  non-zero.
