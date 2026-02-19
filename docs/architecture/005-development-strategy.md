# ADR-005: Development and Deployment Strategy

**Status:** Accepted
**Date:** 2025-02-17
**Decision makers:** Sébastien Binet, Claude (AI assistant)

## Development Phases

### Phase 1: Foundation (MVP)
**Goal:** End-to-end pothole detection and recording.

1. **Protobuf schema definition** — Define `.proto` files, generate Kotlin and
   Python code.
2. **Server MVP** — FastAPI server that receives hit reports and stores them to
   disk. Health endpoint. Ingestion queue.
3. **Android app MVP:**
   - Sensor module (accelerometer + GPS).
   - Simple hit detection (threshold over rolling average).
   - Real-time reporting mode only.
   - Basic UI: acceleration graph, hit counter, data usage display.
4. **Hit simulator tool** — Python script that generates fake hit reports and
   sends them to the server. For testing without a phone.
5. **Unit tests** — Hit detection logic tested with synthetic accelerometer
   data.

### Phase 2: Robustness
**Goal:** Production-ready data pipeline.

1. **Car mount detection module.**
2. **Wi-Fi batch upload mode** with local SQLite buffer.
3. **Data usage tracking** (KB/min, MB/hour, MB/month).
4. **Server queue hardening** — overflow to disk, backpressure.
5. **Hit detection calibration** — adaptive baseline, configurable threshold.

### Phase 3: Visualization
**Goal:** See potholes on a map.

1. **Web dashboard** — Heatmap of all potholes (Google Maps JavaScript API).
2. **Android map view** — User sees their own hits on Google Maps.
3. **Pothole clustering** — Server groups nearby hits into pothole entities.
4. **Analysis pipeline** — Near-real-time (2s delay) processing.

### Phase 4: Social & Admin
**Goal:** Community and city features.

1. **Google Sign-In** — Link device to user account.
2. **Friends feature** — See friends' potholes on map.
3. **City admin portal** — View potholes, add comments, mark as repaired.
4. **User notifications** — Graphical indicator when a reported pothole is
   handled by the city.
5. **Developer reset tool** — Clear user/pothole data.

## Android Development & Deployment Strategy

### Development Setup
- **IDE:** Android Studio (latest stable).
- **Build:** Gradle with Kotlin DSL.
- **Dependencies:**
  - Jetpack Compose for UI.
  - Protobuf Lite for Android (smaller than full protobuf).
  - OkHttp for networking.
  - Room (SQLite wrapper) for local hit buffer.
  - MPAndroidChart or Compose Canvas for acceleration graph.
  - Google Maps SDK for Android (Phase 3).

### Testing on Device
- During development: deploy directly from Android Studio via USB or Wi-Fi.
- Use Android's "developer options" to install unsigned APKs.
- Sensor data can't be simulated on emulator — real device needed for
  accelerometer testing.
- GPS can be mocked on emulator for location-related tests.

### Distribution to Initial 100 Users
**Options (from simplest to most involved):**

1. **Direct APK sharing** (Recommended for early phase)
   - Build a signed APK, share via link/email.
   - Users enable "Install from unknown sources."
   - Pro: No review process, instant updates.
   - Con: Manual update process, security warning on install.

2. **Google Play Internal Testing**
   - Upload to Google Play Console as internal test track.
   - Invite up to 100 testers by email.
   - Pro: Auto-updates via Play Store, no sideloading needed.
   - Con: Requires Google Play developer account ($25 one-time), review may
     take hours/days.

3. **Firebase App Distribution**
   - Upload APK/AAB, invite testers by email.
   - Pro: Easy distribution, no Play Store needed.
   - Con: Requires Firebase project, testers install a helper app.

**Recommendation:** Start with direct APK sharing (simplest). Move to Google
Play Internal Testing when the user base needs auto-updates.

## Server Development & Deployment Strategy

### Development
- Develop locally on the Mac.
- Use `uvicorn` to run FastAPI in development mode with auto-reload.
- Use `pytest` for server tests.
- Use `venv` or `conda` for Python environment isolation.

### Python Version on macOS 10.15
- macOS 10.15 (Catalina) ships with Python 2.7 (deprecated) and may have 3.7.
- **Install Python 3.10+ via Homebrew:** `brew install python@3.11`
- FastAPI requires Python 3.8+. Python 3.11 recommended for performance.
- If Homebrew is too old for Catalina, use `pyenv` to build Python from source.

### Running the Server
```bash
# Create virtual environment
python3.11 -m venv venv
source venv/bin/activate

# Install dependencies
pip install -r requirements.txt

# Run server
uvicorn server.main:app --host 0.0.0.0 --port 8000 --reload
```

### Exposing via Tunnel

**Option A: ngrok**
```bash
brew install ngrok
ngrok http 8000
# Gives you: https://abc123.ngrok.io → localhost:8000
```
- Free plan: random URL changes on restart.
- Paid plan ($8/mo): stable subdomain.

**Option B: Cloudflare Tunnel (recommended, free)**
```bash
brew install cloudflared
cloudflared tunnel login
cloudflared tunnel create nidsdepoule
cloudflared tunnel route dns nidsdepoule nidsdepoule.yourdomain.com
cloudflared tunnel run nidsdepoule
```
- Requires a domain managed by Cloudflare (free plan).
- Stable URL, no monthly cost.
- Built-in DDoS protection.

**The Android app should have a configurable server URL** so you can switch
between localhost (emulator testing), ngrok URL, and Cloudflare URL.

### Handling Router/NAT

The tunnel approach eliminates NAT issues entirely:
- The tunnel client runs on your Mac and makes an outbound connection to
  ngrok/Cloudflare.
- No port forwarding needed on your router.
- No need for a static IP or DDNS.
- The phone connects to the tunnel's public URL — standard HTTPS, works on any
  network.

## Google Maps Integration

### On Android (Phase 3)
- Use **Google Maps SDK for Android**.
- Requires a Google Cloud project with Maps SDK enabled.
- API key restricted to your app's package name + signing certificate.
- Free tier: 28,000 map loads/month (more than enough for 100 users).
- Add custom markers for the user's own hits.
- Add a heatmap layer for nearby potholes (using `google-maps-android-heatmap`
  utility library).

### On Web Dashboard (Phase 3)
- Use **Google Maps JavaScript API**.
- Heatmap visualization layer.
- Custom markers for individual potholes.
- Free tier: 28,000 map loads/month.

### API Key Security
- Android key: restricted by package name in Google Cloud Console.
- Web key: restricted by HTTP referrer.
- Never commit API keys to git. Use environment variables or a local config
  file.

## Testing Strategy

### Unit Tests (Priority)
- **Hit detection module:** Test with synthetic accelerometer data.
  - Test: no false positives on smooth road data.
  - Test: detects hits at various severities.
  - Test: adaptive baseline adjusts over time.
  - Test: different phone orientations produce consistent results.
- **Protobuf serialization:** Verify roundtrip encode/decode.
- **Data usage tracking:** Verify byte counting.

### Integration Tests
- **Server endpoint:** Send protobuf messages, verify they're stored correctly.
- **Queue behavior:** Verify backpressure under load.

### Simulation Tool
- Python script that generates realistic hit streams.
- Configurable: number of devices, hit frequency, geographic area, route
  patterns.
- Can replay recorded real sensor data (capture raw data from a test drive).
- Sends to server via same HTTP API as the real app.

### Manual Testing
- Real device test drives.
- Compare detected hits with known potholes on the route.
