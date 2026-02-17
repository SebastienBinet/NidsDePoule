# NidsDePoule

Crowdsourced pothole detection system. An Android app passively detects
potholes using the phone's accelerometer while driving, and reports them to a
central server for mapping and analysis.

## Project Components

| Component  | Tech Stack                | Directory  |
|------------|---------------------------|------------|
| Android app | Kotlin, Jetpack Compose  | `android/` |
| Server      | Python, FastAPI          | `server/`  |
| Wire format | Protocol Buffers         | `proto/`   |
| Dev tools   | Python (simulator, scripts) | `tools/` |
| Web dashboard | TBD                    | `web/`     |

## Architecture

See [docs/architecture/](docs/architecture/) for Architecture Decision Records
(ADRs) covering project goals, technology choices, data formats, and
development strategy.

## Quick Start

### Server (on macOS)

```bash
cd server
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
uvicorn server.main:app --host 0.0.0.0 --port 8000 --reload
```

### Expose Server (via tunnel)

```bash
# Option A: ngrok
ngrok http 8000

# Option B: Cloudflare Tunnel
cloudflared tunnel run nidsdepoule
```

### Android App

Open `android/` in Android Studio. Build and deploy to a device.

### Hit Simulator (for testing)

```bash
cd tools
python simulator/simulate.py --server http://localhost:8000 --devices 5
```

## Status

**Phase 1: Foundation** — in progress.

## License

See [LICENSE](LICENSE).
