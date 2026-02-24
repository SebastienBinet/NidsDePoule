# Plan: Real-Time Pothole Display on Map

## Context & Research Findings

### The math
- Refresh interval: 5 seconds (dev), will increase later
- Worst-case speed: 100 km/h = 27.78 m/s
- Safety factor: 5x
- Required radius: 27.78 * 5 * 5 = **694 m** (not 139 m — the formula should use the refresh interval × safety factor)
  - Wait — re-reading your formula: `(100/3.6) * 5 = 139 m` where the `5` is the safety factor, not the refresh interval. The logic is: at 100 km/h you travel ~28 m/s, so in 5 seconds you travel ~139 m. The factor of 5 means you fetch potholes within 5× that distance = 139 m. Actually re-reading: you wrote `(100km/h) * (1000/3600) * 5 = 138.888 m`. This is speed_m/s × 5 = 27.78 × 5 = 139 m. So the "5" is the safety multiplier applied to 1 second of travel. This equals ~139 m radius. I'll use **radius = 139 m** as specified.

### Can we inject markers into the standalone Google Maps app?
**No.** Google Maps is sandboxed. There are no intents, content providers, or APIs that allow a third-party app to add custom markers/overlays to the Google Maps app. This is by design.

### Proposed solution: Embedded map in the NidsDePoule app

The simplest and most reliable approach is to **embed a Google Maps view directly in the NidsDePoule app** using:
- `com.google.android.gms:play-services-maps` (Google Maps Android SDK)
- `com.google.maps.android:maps-compose` (Jetpack Compose wrapper)

This gives us:
- Full map rendering with the familiar Google Maps look
- Custom `Marker` composables at each pothole location
- `Circle` overlays for severity visualization
- Camera that follows the user's position
- My-location blue dot
- Real-time marker updates via Compose recomposition

**For navigation**, the user can still launch the standalone Google Maps app via intent. A floating overlay approach (warnings on top of Google Maps during navigation) could be added in a future iteration.

**Alternative considered:** Mapbox Maps + Navigation SDK would give an all-in-one experience (map + turn-by-turn + custom markers), but it adds a new dependency ecosystem and pricing concern. Google Maps SDK is simpler since we already use Play Services for location.

---

## Implementation Steps

### Step 1: Server — Add nearby-potholes endpoint

**File:** `server/server/api/potholes.py`

Add `GET /api/v1/potholes/nearby?lat=<microdeg>&lon=<microdeg>&radius_m=<float>`:
- Accept lat/lon in **microdegrees** (consistent with existing data model)
- Accept radius_m (default: 139, matching the 5s refresh calculation)
- Reuse existing `cluster_hits()` from `clustering.py` to get `PotholeCluster` objects
- Filter clusters whose centroid is within `radius_m` of the provided position (using existing `haversine_distance_m()`)
- Return a lightweight JSON array (not full GeoJSON — minimize payload):
  ```json
  {
    "potholes": [
      {
        "lat_microdeg": 45510234,
        "lon_microdeg": -73612345,
        "severity_avg": 2.1,
        "severity_max": 3,
        "confidence": 0.85,
        "hit_count": 7
      }
    ]
  }
  ```

**File:** `server/server/core/clustering.py`
- No changes needed — `haversine_distance_m()` and `cluster_hits()` already exist

**Tests:** Add test for the new endpoint in `server/tests/`

### Step 2: Android — Add Google Maps SDK + maps-compose dependencies

**File:** `android/app/build.gradle.kts`

Add dependencies:
```kotlin
implementation("com.google.android.gms:play-services-maps:19.0.0")
implementation("com.google.maps.android:maps-compose:4.3.0")
```

**File:** `android/app/src/main/AndroidManifest.xml`

Add the Maps API key meta-data:
```xml
<meta-data
    android:name="com.google.android.geo.API_KEY"
    android:value="${MAPS_API_KEY}" />
```

Note: A Google Maps API key will be needed. For development, we can use an unrestricted key. For production, it should be restricted to the app's package name and signing certificate. The user will need to provide or create a key from the Google Cloud Console.

### Step 3: Android — Create PotholeRepository (data layer)

**New file:** `android/app/src/main/java/fr/nidsdepoule/app/reporting/PotholeRepository.kt`

- Holds the current list of nearby `PotholeMarker` data objects
- Has a `fetchNearby(latMicrodeg: Int, lonMicrodeg: Int, radiusM: Float)` suspend function
- Uses OkHttp (already in dependencies) to call `GET /api/v1/potholes/nearby`
- Parses the JSON response into a list of `PotholeMarker`:
  ```kotlin
  data class PotholeMarker(
      val latMicrodeg: Int,
      val lonMicrodeg: Int,
      val severityAvg: Float,
      val severityMax: Int,
      val confidence: Float,
      val hitCount: Int,
  )
  ```
- Exposes a `StateFlow<List<PotholeMarker>>` for the UI to observe

### Step 4: Android — Create PotholePoller (periodic fetch)

**New file:** `android/app/src/main/java/fr/nidsdepoule/app/reporting/PotholePoller.kt`

- Runs on a coroutine with a configurable interval (default 5 seconds)
- On each tick:
  1. Gets the latest location from MainViewModel's location state
  2. Calls `PotholeRepository.fetchNearby()` with that location and radius=139m
- Starts/stops with the detection lifecycle (when the app is active)
- Handles errors gracefully (network failures don't crash the loop)

### Step 5: Android — Wire into MainViewModel

**File:** `android/app/src/main/java/fr/nidsdepoule/app/MainViewModel.kt`

- Instantiate `PotholeRepository` and `PotholePoller`
- Expose `nearbyPotholes: StateFlow<List<PotholeMarker>>` to the UI
- Start/stop the poller alongside existing location tracking
- Pass the server base URL from preferences to the repository

### Step 6: Android — Add MapScreen composable

**New file:** `android/app/src/main/java/fr/nidsdepoule/app/ui/MapScreen.kt`

- Full-screen `GoogleMap` composable
- Camera follows user's current position (bearing-oriented)
- Displays pothole markers from `nearbyPotholes` state:
  - Color by severity (green/yellow/orange/red)
  - Size by hit_count or confidence
  - Info window on tap showing severity details
- Shows user's location (my-location layer enabled)
- Minimal chrome — maximize the map area

### Step 7: Android — Add navigation between MainScreen and MapScreen

**File:** `android/app/src/main/java/fr/nidsdepoule/app/ui/MainScreen.kt`
**File:** `android/app/src/main/java/fr/nidsdepoule/app/MainActivity.kt`

- Add a "Map" FAB or bottom nav button on MainScreen to switch to MapScreen
- Simple two-screen navigation (could use Compose Navigation or a basic state toggle)
- Detection continues running in background regardless of which screen is shown

### Step 8: Deploy canary bump

Update version badge to **v6** with a new color after all changes are working.

---

## Architecture Diagram

```
[User's Phone]
     │
     ├─ MainScreen (status + accel graph + buttons)
     │
     └─ MapScreen (Google Maps + pothole markers)  ◄── NEW
            │
            ▼
     PotholePoller (every 5s)
            │
            ▼ HTTP GET /api/v1/potholes/nearby?lat=..&lon=..&radius_m=139
            │
     [Server]
            │
            ▼
     cluster_hits() → filter by distance → return JSON
```

## Open Questions for User

1. **Google Maps API key** — Do you already have a Google Cloud project with Maps SDK for Android enabled? If not, one will need to be created.
2. **Map as default screen?** — Should the map be the main screen (with status as secondary), or should it be a secondary screen accessed via a button?
3. **Severity visualization** — Any preference on how potholes should look on the map? (colored circles, custom icons, standard markers with colors?)
