# ADR-013: App Distribution, Developer Mode, and Third-Party APIs

**Status:** Accepted
**Date:** 2025-02-17
**Decision makers:** Sébastien Binet, Claude (AI assistant)

---

## Part 1: App Distribution and Updates

### The Problem

How to distribute the app to 100 users, push updates without friction, and
handle protocol-breaking changes that require all users to update?

### Distribution Strategy by Phase

#### Phase 1 — Development (1-10 testers)

**Method: GitHub Releases + Direct APK**

```
Developer pushes code → GitHub Actions builds APK → APK attached to Release
Tester gets notification → Downloads APK → Installs manually
```

- Build signed APKs via GitHub Actions on every tagged release.
- Testers download from the GitHub Releases page.
- Testers must enable "Install from unknown sources" once.
- Free, no review, instant.

**Why not Google Play yet:** No need for 2 people. The overhead of managing a
Play Console listing isn't worth it.

#### Phase 2 — Early Users (10-100 testers)

**Method: Google Play Internal Testing**

- Upload to Google Play Console internal test track.
- Invite up to 100 testers by email (Google account required).
- **No Google review required** — available within seconds of upload.
- Users get updates through the Play Store (familiar, automatic).
- Cost: $25 one-time Google Play developer fee.

**Why this works:** Internal testing has zero review delay, supports 100
testers, and users get automatic updates via the Play Store. It's the
easiest path to a professional update experience.

#### Phase 3 — Public (100+ users)

**Two parallel channels:**

| Channel       | Audience          | Auto-updates | Review  | Cost |
|---------------|-------------------|-------------|---------|------|
| Google Play   | General public    | Yes (Play Store) | 1-3 days | $25 one-time |
| F-Droid       | Open-source community | Yes (Android 12+) | Days-weeks (built from source) | Free |

**F-Droid** is a natural fit because:
- The project is open source.
- F-Droid builds from source (verifiable, trusted).
- On Android 12+ (our minimum), F-Droid auto-updates silently.
- No cost, no Google dependency.
- Strong in the European/French open-source community.

**Both channels serve the same APK** (same code, same signing key for Play,
F-Droid signs with their own key from source). Users choose their preferred
distribution channel.

### Update Mechanism: Three Layers

#### Layer 1: Store Updates (Background, Automatic)

Google Play and F-Droid handle this. Users get updates within hours/days
automatically. No code needed.

#### Layer 2: In-App Update Prompt (For Important Updates)

When the server has a critical update, the app shows a banner:

```
┌─────────────────────────────────────┐
│ ⬆ A new version is available.      │
│ [Update now]  [Later]               │
└─────────────────────────────────────┘
```

**How it works:**

1. App calls `GET /api/v1/config` on startup (already planned for waveform
   size and rate limits).
2. Server response includes:

```json
{
  "min_app_version": 5,
  "latest_app_version": 12,
  "update_urgency": "recommended",
  "update_message_en": "Improved pothole detection accuracy",
  "update_message_fr": "Amélioration de la précision de détection",
  "update_url_play": "https://play.google.com/store/apps/details?id=fr.nidsdepoule.app",
  "update_url_fdroid": "https://f-droid.org/packages/fr.nidsdepoule.app",
  "update_url_github": "https://github.com/SebastienBinet/NidsDePoule/releases/latest"
}
```

3. App compares its `app_version` (versionCode) with `latest_app_version`.
4. If behind, shows a non-blocking banner with "Update now" (opens the
   appropriate store based on how the app was installed).

**No forced updates via the store.** Users can always dismiss the banner.

#### Layer 3: Protocol Gate (For Breaking Changes)

This is the **hard stop** for truly incompatible changes.

When the server cannot understand old client data (breaking protobuf change,
new `protocol_version`), the server returns:

```
HTTP 426 Upgrade Required
Content-Type: application/json

{
  "error": "protocol_version_too_old",
  "min_protocol_version": 2,
  "your_protocol_version": 1,
  "message_en": "Please update the app to continue reporting potholes.",
  "message_fr": "Veuillez mettre à jour l'application pour continuer.",
  "update_url": "https://play.google.com/store/apps/details?id=fr.nidsdepoule.app"
}
```

The app shows a blocking dialog:

```
┌─────────────────────────────────────┐
│ Update required                     │
│                                     │
│ This version is too old to send     │
│ data to the server. Please update.  │
│                                     │
│ The app will continue detecting     │
│ potholes and store them locally     │
│ until you update.                   │
│                                     │
│ [Update now]                        │
└─────────────────────────────────────┘
```

**Critical design decision:** Even when blocked, the app **continues detecting
and storing hits locally**. Once the user updates and the new version connects,
it sends the buffered hits. No data is lost.

### Release Versioning

```
versionCode: 1, 2, 3, ...        (monotonically increasing integer)
versionName: "0.1.0", "0.2.0"    (human-readable semver)
protocol_version: 1               (only bumps on wire-breaking changes)
```

- `versionCode` increments on every release.
- `versionName` follows semver (MAJOR.MINOR.PATCH).
- `protocol_version` is separate and rarely changes. Multiple app versions
  share the same protocol version.

### GitHub Actions CI/CD Pipeline (From Day 1)

```yaml
# .github/workflows/android-release.yml
# Triggered on tagged releases (v*)
# 1. Build release APK (signed)
# 2. Run unit tests
# 3. Attach APK to GitHub Release
# 4. Optionally upload to Google Play internal track via Gradle Play Publisher
```

This means every release is automated: tag → build → test → publish.

---

## Part 2: Developer Mode

### The Problem

Developers need access to raw sensor data, threshold adjustment, server URL
override, ASCII protocol mode, and debug endpoints. Regular users should never
see these. The activation must be deliberate but not require a special build.

### Strategy: Three Tiers of Access

#### Tier 1: Release Build (Regular Users)

- No dev features visible.
- Server URL is hardcoded (with fallback list).
- Binary protobuf mode only.
- No raw sensor display.
- Standard UI.

#### Tier 2: Developer Mode (Activated in Release Build)

**Activation: Tap the version number 7 times.**

This mirrors Android's own "Enable developer options" pattern — a gesture that
developers know by instinct and regular users will never stumble on.

```
Settings → About → Version: 0.3.0
[tap] [tap] [tap] [tap] [tap] [tap] [tap]
→ "Developer mode enabled! 🔧"
```

**What it unlocks:**
- A new "Developer" section appears in Settings.
- Server URL override field (localhost, ngrok, custom).
- ASCII protobuf mode toggle.
- Detection threshold slider (adjust in real time).
- Raw sensor data display (live accelerometer values + gravity vector).
- "Force send" mode (sends every bump, not just detected hits).
- Mock GPS toggle (for indoor testing).
- Hit counter with detailed breakdown (detected, sent, failed, buffered).
- Export local buffer to file.
- A small "DEV" badge appears in the app's status bar to remind
  the developer that dev mode is active.

**Persistence:** Developer mode stays enabled across app restarts (stored in
SharedPreferences). Disable by tapping the version number 7 times again.

**Security:** Dev mode doesn't expose any sensitive data or server credentials.
It only changes local app behavior (server URL, protocol format, sensor
display). The server treats dev-mode clients identically to regular clients.

#### Tier 3: Debug Build (Developers Only)

A separate build variant (`buildType = debug`) compiled from Android Studio:

- Developer mode always enabled, cannot be disabled.
- Extra logging (verbose logcat output).
- Network inspector integration (OkHttp logging interceptor).
- StrictMode enabled (detects performance issues).
- Debug signing key (not for distribution).
- Test server URL as default.

**How build variants work:**

```kotlin
// build.gradle.kts
android {
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"  // Can install alongside release
            buildConfigField("boolean", "DEV_MODE_DEFAULT", "true")
            buildConfigField("String", "DEFAULT_SERVER_URL",
                "\"http://10.0.2.2:8000\"")  // Android emulator → localhost
        }
        release {
            buildConfigField("boolean", "DEV_MODE_DEFAULT", "false")
            buildConfigField("String", "DEFAULT_SERVER_URL",
                "\"https://nidsdepoule.example.com\"")
            isMinifyEnabled = true
            proguardFiles(...)
        }
    }
}
```

The debug build installs as a separate app (`.debug` suffix) so developers
can have both debug and release versions on the same phone.

### Server-Side Developer Flag (Future)

For remote troubleshooting, the server's `/api/v1/config` response can include:

```json
{
  "dev_mode_enabled_devices": ["device-uuid-1", "device-uuid-2"]
}
```

If a device's UUID is in this list, the app auto-enables developer mode. This
lets you debug a specific user's phone without them needing to know the 7-tap
trick.

---

## Part 3: Third-Party APIs

### The Problem

Other companies and applications might want to:
- **Read** pothole data (navigation apps, insurance, researchers).
- **Write** pothole data (other sensor apps, city IoT devices, dashcams).
- **React** to pothole events (city maintenance systems, alerts).

How to make this possible without overcomplicating the system?

### Strategy: Public REST API with API Keys

#### API Design

The third-party API is a **separate set of endpoints** from the internal
app-to-server API. It uses JSON (not protobuf) for maximum compatibility.

```
Internal (app ↔ server):    POST /api/v1/hits        (protobuf)
Third-party (anyone):       GET  /api/v2/potholes     (JSON)
                            POST /api/v2/reports       (JSON)
```

#### Read API — Get Pothole Data

```
# Get potholes in a bounding box
GET /api/v2/potholes?bbox=45.74,4.81,45.78,4.87&severity=medium,heavy
Accept: application/json

Response:
{
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "geometry": {
        "type": "Point",
        "coordinates": [4.835659, 45.764043]
      },
      "properties": {
        "id": "pothole-a1b2c3",
        "severity": "heavy",
        "hit_count": 47,
        "first_reported": "2025-01-15T08:30:00Z",
        "last_hit": "2025-02-17T14:32:00Z",
        "status": "acknowledged",
        "city_comment": "Scheduled for repair March 2025"
      }
    }
  ]
}

# Get potholes near a point
GET /api/v2/potholes?lat=45.764&lon=4.835&radius_m=500

# Get potholes along a route (for navigation apps)
POST /api/v2/potholes/along-route
Content-Type: application/json
{
  "route": [[4.83, 45.76], [4.84, 45.77], [4.85, 45.78]],
  "corridor_m": 50
}

# Get a single pothole's detail and history
GET /api/v2/potholes/{pothole_id}
```

**Response format: GeoJSON.** This is the standard for geographic data. Every
GIS tool, mapping library, and spatial database understands GeoJSON natively.
Third parties can drop the response directly onto a map.

#### Write API — Report Potholes

Third parties can report potholes without using our Android app:

```
POST /api/v2/reports
Content-Type: application/json
X-API-Key: abc123...

{
  "source": "dashcam-app-xyz",
  "timestamp": "2025-02-17T14:32:01.123Z",
  "location": {
    "lat": 45.764043,
    "lon": 4.835659,
    "accuracy_m": 8
  },
  "speed_mps": 13.5,
  "bearing_deg": 270.0,
  "severity_estimate": "medium",
  "metadata": {
    "device_model": "RaspberryPi4",
    "sensor_type": "custom_accelerometer",
    "app_version": "2.1.0"
  }
}
```

**Key design decisions:**
- JSON (not protobuf) — lowest barrier to entry. Any HTTP client works.
- The `source` field identifies which third-party app sent the data.
- `metadata` is a free-form object — third parties can include whatever
  extra information they have.
- The `severity_estimate` is a hint. The server does its own classification
  (or skips it if no waveform data is provided).
- No waveform required. Third parties may not have raw accelerometer data.
  They might detect potholes via other means (camera, LIDAR, etc.).

#### Webhook API — Real-Time Notifications

For third parties that want to be notified when potholes are detected or
updated:

```
# Register a webhook
POST /api/v2/webhooks
X-API-Key: abc123...

{
  "url": "https://city-gis.lyon.fr/pothole-callback",
  "events": ["pothole.new", "pothole.severity_changed", "pothole.repaired"],
  "filter": {
    "bbox": [4.81, 45.74, 4.87, 45.78],
    "min_severity": "medium"
  }
}

# Server calls the registered URL when matching events occur:
POST https://city-gis.lyon.fr/pothole-callback
Content-Type: application/json
X-Webhook-Signature: sha256=...

{
  "event": "pothole.new",
  "timestamp": "2025-02-17T14:32:05Z",
  "pothole": {
    "id": "pothole-d4e5f6",
    "location": {"lat": 45.767891, "lon": 4.832145},
    "severity": "medium",
    "hit_count": 3,
    "first_reported": "2025-02-17T14:30:00Z"
  }
}
```

**Webhook signature:** Each callback includes an HMAC-SHA256 signature so
the receiver can verify it came from our server. Standard webhook security
pattern.

#### API Key Management

```
# API keys are managed via a simple admin endpoint or CLI tool

# Create a key
python -m tools.scripts.api_keys create \
  --name "Lyon City GIS" \
  --type read \
  --rate-limit 1000/hour

# List keys
python -m tools.scripts.api_keys list

# Revoke a key
python -m tools.scripts.api_keys revoke --key abc123...
```

**Key types:**

| Type       | Can read potholes | Can write reports | Can register webhooks |
|------------|------------------|-------------------|----------------------|
| `read`     | Yes              | No                | Yes                  |
| `write`    | No               | Yes               | No                   |
| `readwrite`| Yes              | Yes               | Yes                  |
| `admin`    | Yes              | Yes               | Yes (+ manage keys)  |

**Rate limiting per key:**
- Read: 1,000 requests/hour (default).
- Write: 100 reports/hour (default).
- Configurable per key for partners who need more.

### Data Export Formats

For bulk data access (researchers, cities doing annual analysis):

```
# Export all potholes as GeoJSON
GET /api/v2/export?format=geojson&since=2025-01-01

# Export as CSV
GET /api/v2/export?format=csv&since=2025-01-01

# Export as KML (for Google Earth)
GET /api/v2/export?format=kml&since=2025-01-01

# Export as GeoPackage (for QGIS / ArcGIS)
GET /api/v2/export?format=gpkg&since=2025-01-01
```

These are heavy operations. Rate-limited to 1 per hour per API key. Cached
for 1 hour (same export for all requesters within the cache window).

### Who Would Use This?

| Third party             | API usage                  | Value for them           |
|-------------------------|---------------------------|--------------------------|
| Navigation apps (Waze)  | Read: potholes along route | Warn drivers in real-time|
| City GIS departments    | Read + webhooks            | Track and manage repairs |
| Insurance companies     | Read: pothole density by area | Risk assessment        |
| Road research labs      | Bulk export                | Road degradation studies |
| Other sensor apps       | Write: report potholes     | Contribute without our app |
| Dashcam companies       | Write: camera-detected potholes | Complementary data   |
| Fleet management tools  | Read: route planning       | Avoid damaged roads      |

### Implementation Priority

This is not for v1. The third-party API comes **after the core system works.**

| Priority | Feature            | Phase |
|----------|--------------------|-------|
| 1        | `GET /potholes` (read) | Phase 3 (with web dashboard) |
| 2        | Export (GeoJSON, CSV)  | Phase 3 |
| 3        | `POST /reports` (write)| Phase 4 |
| 4        | Webhooks              | Phase 4 |
| 5        | API key management    | Phase 4 |
| 6        | KML/GeoPackage export | Phase 4+ |

### Open Data Considerations

If the project grows and you want to maximize impact:
- Consider publishing the pothole dataset as **open data** (anonymized: no
  device IDs, no individual hits, just aggregated pothole locations and
  severity).
- Open data can be published on data.gouv.fr (French government open data
  portal).
- This can attract city partnerships and research collaborations.
- The API remains the mechanism for real-time access; open data dumps are
  periodic exports.
