# ADR-008: Scaling and Cost Analysis — From 100 to 10 Million Users

**Status:** Accepted
**Date:** 2025-02-17
**Decision makers:** Sébastien Binet, Claude (AI assistant)

## Purpose

This document analyzes every cost and bottleneck if the system grows overnight
from 100 users to 10 million users (1 million simultaneous). The goal is to
make informed decisions now that don't paint us into a corner later, and to
know exactly where the breaking points are.

## Three Scales Analyzed

| Scale         | Total users | Simultaneous | Hits/second | Description     |
|---------------|------------|--------------|-------------|-----------------|
| **Startup**   | 100        | 10           | ~2          | Mac on desk     |
| **Growth**    | 50,000     | 5,000        | ~830        | Single cloud VM |
| **Massive**   | 10,000,000 | 1,000,000    | ~16,700     | Distributed     |

*Assumption: each active user averages ~1 hit per minute (mix of smooth and
rough roads).*

---

## 1. INCOMING DATA — Bandwidth and Throughput

### Per-Hit Size (with 150 waveform samples)
| Component        | Binary (bytes) | Notes                          |
|------------------|---------------|--------------------------------|
| Envelope         | ~45           | version, device_id, app_version |
| Timestamp        | 5             | varint                         |
| Location         | 10            | 2×sfixed32 + varint            |
| Speed + bearings | 12            | 3 floats                       |
| Hit pattern      | ~630          | peaks, duration, 150×2 samples |
| **Total**        | **~700**      | Binary protobuf                |
| HTTP overhead    | ~500          | Headers, TLS framing           |
| **Wire total**   | **~1,200**    | What hits the network          |

### Bandwidth by Scale

| Scale      | Hits/sec | Raw data/sec | Wire data/sec | Per day       |
|------------|----------|-------------|---------------|---------------|
| Startup    | 2        | 1.4 KB/s    | 2.4 KB/s      | 120 MB        |
| Growth     | 830      | 580 KB/s    | 1 MB/s        | 50 GB         |
| Massive    | 16,700   | 11.7 MB/s   | 20 MB/s       | 1 TB          |

### Bottleneck Analysis

| Scale    | Bottleneck?  | Solution                                      |
|----------|-------------|-----------------------------------------------|
| Startup  | No          | Home Wi-Fi upload: ~5 Mbps typical (=600 KB/s) |
| Growth   | Maybe       | Need ~8 Mbps sustained. Fine for a cloud VM   |
| Massive  | Yes         | 160 Mbps sustained. Need multiple endpoints + CDN/load balancer |

**At startup scale:** Your home internet upload speed is the only concern. Most
home connections provide 5-20 Mbps upload, which is 600 KB/s to 2.4 MB/s. At 2
hits/second (2.4 KB/s), you're using <1% of bandwidth. No problem.

**At massive scale:** A single endpoint can't handle 160 Mbps + 16,700
connections. Need geographic distribution. See §6 (Infrastructure).

---

## 2. STORAGE — Disk Space and I/O

### Storage by Scale

| Scale    | Raw data/day | With JSON index | Per month  | Per year   |
|----------|-------------|----------------|------------|------------|
| Startup  | 120 MB      | 360 MB          | 11 GB      | 130 GB     |
| Growth   | 50 GB       | 150 GB          | 4.5 TB     | 54 TB      |
| Massive  | 1 TB        | 3 TB            | 90 TB      | 1 PB       |

### Bottleneck Analysis

| Scale    | Bottleneck? | Solution                                      |
|----------|------------|-----------------------------------------------|
| Startup  | No         | Any Mac hard drive. 130 GB/year is trivial     |
| Growth   | Maybe      | 4.5 TB/month. Cloud block storage, ~$500/month |
| Massive  | Yes        | 90 TB/month. Need object storage (S3) + lifecycle policies |

**At massive scale:**
- Drop JSON Lines index (save 2×). Use binary-only with on-demand indexing.
- Reduce waveform to 15 samples: cuts raw data by ~10× (from 700 to ~90 bytes/hit).
  At 90 bytes: 1 TB/day → 130 GB/day → 3.9 TB/month.
- Lifecycle policy: move data older than 30 days to cold storage (S3 Glacier:
  $0.004/GB/month).
- After pothole clustering: delete raw hits older than 90 days, keep only
  aggregated pothole data.

**Cost at massive scale:**
- Hot storage (30 days): 3.9 TB × $0.023/GB = ~$90/month (S3 Standard)
- Cold storage (1 year): 47 TB × $0.004/GB = ~$190/month (S3 Glacier)
- **Total storage: ~$280/month** (with 15-sample waveforms and lifecycle policy)

---

## 3. SERVER COMPUTE — Request Processing

### Throughput Estimates

A FastAPI server with uvicorn typically handles:
- Simple protobuf parse + queue: ~3,000–5,000 req/s per worker
- With 4 workers: ~15,000 req/s per machine (modern cloud VM)

| Scale    | Hits/sec | Servers needed | Type               |
|----------|----------|---------------|--------------------|
| Startup  | 2        | 1 (Mac)       | Your desk Mac      |
| Growth   | 830      | 1             | 4-core cloud VM    |
| Massive  | 16,700   | 4-8           | Behind load balancer |

### Bottleneck Analysis

| Scale    | Bottleneck? | Solution                                           |
|----------|------------|-----------------------------------------------------|
| Startup  | No         | Single FastAPI process handles 2 req/s trivially    |
| Growth   | No         | Single VM with 4 uvicorn workers                    |
| Massive  | Moderate   | 4-8 FastAPI instances behind a load balancer. Switch ingestion queue from in-process asyncio to Kafka or Redis Streams |

**Cost at massive scale (cloud compute):**
- 8 × c6g.large (AWS, 2 vCPU, 4GB RAM): 8 × $50/month = **$400/month**
- Load balancer (ALB): ~$20/month + $8/million requests = **$150/month**
- Total compute: ~**$550/month**

### Queue Architecture Evolution

| Scale    | Queue technology       | Why                                    |
|----------|----------------------|----------------------------------------|
| Startup  | `asyncio.Queue`       | In-process, zero dependencies          |
| Growth   | Redis Streams          | Persistent, survives restarts          |
| Massive  | Apache Kafka           | Distributed, handles 100K+ msg/sec, replayable |

**Kafka cost at massive scale:**
- 3-node Kafka cluster (m6g.xlarge): ~$400/month
- OR managed: AWS MSK ~$600/month
- OR: skip Kafka, write directly to object storage (S3) with batch uploads.
  The app already batches. The server can batch-write.

---

## 4. GOOGLE MAPS — Cost Analysis

### Critical Finding: Android SDK is FREE

**Google Maps SDK for Android: unlimited free usage.** No per-load charges.
This eliminates the biggest potential cost for 10M mobile users.

### Web Dashboard Costs

The web dashboard (heatmap for cities, admin portal) uses the Maps JavaScript
API. Pricing (as of March 2025):

| Monthly loads   | Price per 1,000 | Monthly cost |
|-----------------|----------------|-------------|
| First 10,000    | Free           | $0          |
| 10K - 100K      | $7.00          | $70 - $630  |
| 100K - 500K     | $5.60          | $630 - $2,800 |
| 500K - 1M       | $4.20          | $2,800 - $4,900 |
| 1M - 5M         | $2.10          | $4,900 - $13,300 |
| 5M+             | $0.53          | $13,300+    |

**Who uses the web dashboard?**
- City admins: maybe 100-1,000 users → negligible cost (free tier)
- Web-based pothole map for public: this is where costs could grow

**Scenario: 10M users also check a web map**
- If 1% of users check the web map daily: 100K loads/day = 3M/month
- Cost: ~$4,500/month
- If 10% check: 1M loads/day = 30M/month → ~$18,000/month

### Mitigation Strategies

1. **Don't build a public web map.** Show potholes in-app (Android SDK = free).
   The web dashboard is only for city admins (dozens of users).
2. **Cache map tiles aggressively.** Serve static heatmap images updated every
   5 minutes instead of live Google Maps.
3. **Switch to OpenStreetMap + MapLibre.** Self-hosted tile server for the web
   dashboard costs ~$60-100/month regardless of traffic.
4. **Use Mapbox for web.** 50K free loads/month, then competitive pricing.

**Recommendation:** Use Google Maps Android SDK (free) on mobile. For the web
dashboard, start with Google Maps (under free tier for <100 city admins).
If a public web map is needed at scale, use OpenStreetMap + MapLibre.

### Google Roads API (Snap-to-Road)

- $10 per 1,000 requests
- At massive scale (16,700 hits/sec): **$600/hour = $14,400/day = $432K/month**
- **DO NOT use Google Roads API for snap-to-road at scale.**
- **Alternative:** Use an offline OpenStreetMap-based solution:
  - OSRM (Open Source Routing Machine): self-hosted, free, snap-to-road
    feature built in.
  - Valhalla: another self-hosted option.
  - Cost: one cloud VM ($50/month) serving snap-to-road queries.

### Google Maps Cost Summary

| Scale    | Android Maps | Web Maps  | Roads API | Total/month |
|----------|-------------|-----------|-----------|-------------|
| Startup  | Free        | Free      | N/A       | **$0**      |
| Growth   | Free        | Free      | $0-50     | **$0-50**   |
| Massive  | Free        | $60 (OSM) | $50 (OSRM) | **$110**  |

**Key insight:** By using Google Maps Android SDK (free) + self-hosted
OpenStreetMap for web + self-hosted OSRM for snap-to-road, the mapping cost
at 10M users is approximately **$110/month**, not millions.

---

## 5. OTHER COSTS

### Tunnel / Networking

| Scale    | Solution              | Cost/month |
|----------|-----------------------|-----------|
| Startup  | Cloudflare Tunnel     | Free      |
| Growth   | Cloud VM public IP    | Included  |
| Massive  | Cloud load balancer   | ~$150     |

### Domain & SSL

| Scale    | Solution                   | Cost/month |
|----------|---------------------------|-----------|
| All      | Domain (nidsdepoule.fr)   | ~$1       |
| All      | SSL (Let's Encrypt)        | Free      |

### Android App Distribution

| Scale    | Solution                    | Cost        |
|----------|----------------------------|-------------|
| Startup  | Direct APK                  | Free        |
| Growth   | Google Play Internal Testing| $25 one-time|
| Massive  | Google Play public listing  | $25 one-time|

### Push Notifications (for city admin updates)

| Scale    | Solution           | Cost/month |
|----------|--------------------|-----------|
| Startup  | Firebase Cloud Messaging | Free |
| Growth   | FCM                | Free      |
| Massive  | FCM                | Free (up to unlimited messages) |

---

## 6. INFRASTRUCTURE EVOLUTION

### Startup (100 users)

```
Phone → Cloudflare Tunnel → Mac (FastAPI + asyncio.Queue + disk)
```

**Total cost: $0/month** (excluding your internet bill)

### Growth (50,000 users)

```
Phone → Cloud VM (FastAPI + Redis Streams + disk)
          ↓
        Block storage (EBS/Persistent Disk)
```

**Monthly cost breakdown:**
| Item              | Cost    |
|-------------------|---------|
| VM (t3.medium)    | $30     |
| Block storage 500GB | $50   |
| Bandwidth (1 MB/s)| $80    |
| Domain + misc     | $5     |
| **Total**         | **$165/month** |

### Massive (10,000,000 users)

```
                    ┌──────────────────────────────┐
Phone ──→ CDN/LB ──┤  FastAPI instances (4-8)     │
                    │  behind auto-scaling group    │
                    └──────────┬───────────────────┘
                               │
                    ┌──────────▼───────────────────┐
                    │  Kafka / Redis Streams       │
                    │  (3-node cluster)            │
                    └──────────┬───────────────────┘
                               │
                    ┌──────────▼───────────────────┐
                    │  Storage workers             │
                    │  → S3 (raw data)             │
                    │  → PostgreSQL + PostGIS      │
                    │    (pothole entities)        │
                    └──────────┬───────────────────┘
                               │
                    ┌──────────▼───────────────────┐
                    │  Analysis pipeline           │
                    │  (batch + stream)            │
                    └──────────┬───────────────────┘
                               │
                    ┌──────────▼───────────────────┐
                    │  Web dashboard               │
                    │  OSM tile server + MapLibre  │
                    │  City admin portal           │
                    └──────────────────────────────┘
```

**Monthly cost breakdown:**
| Item                            | Cost       |
|---------------------------------|------------|
| 8 × API servers (c6g.large)    | $400       |
| Load balancer (ALB)            | $150       |
| Kafka cluster (3 nodes)        | $400       |
| S3 storage (hot + cold)        | $280       |
| PostgreSQL (RDS, db.r6g.large) | $300       |
| OSRM snap-to-road VM           | $50        |
| OSM tile server                | $60        |
| Bandwidth (20 MB/s out)        | $4,000     |
| Monitoring/logging             | $100       |
| Domain + SSL                   | $5         |
| **Total**                      | **~$5,750/month** |

**The biggest single cost is bandwidth** ($4,000/month for ~50 TB/month
outbound). This can be reduced by:
- Using a CDN with peering agreements (Cloudflare: $0 bandwidth on Pro plan at
  $20/month, but terms prohibit using it purely as a CDN for API traffic).
- Reducing hit message size (15 samples → ~90 bytes → 5× less bandwidth).
- Having the app batch more aggressively (fewer HTTP requests, amortize
  headers).

With 15-sample waveforms and aggressive batching:
- Bandwidth drops to ~$800/month
- **Revised total: ~$2,550/month**

---

## 7. BOTTLENECK SUMMARY

| Bottleneck                     | Hits at   | Severity  | Mitigation                           |
|--------------------------------|-----------|-----------|--------------------------------------|
| Home internet upload bandwidth | ~5K users | Critical  | Move to cloud VM                     |
| Single FastAPI process         | ~3K users | High      | Add uvicorn workers (trivial)        |
| asyncio.Queue (in-memory)      | ~10K users| Medium    | Switch to Redis Streams              |
| Single VM compute              | ~50K users| Medium    | Add more API server instances        |
| Disk I/O (single disk)        | ~50K users| Medium    | Switch to S3 or distributed storage  |
| DNS/tunnel throughput          | ~5K users | High      | Drop tunnel, use cloud public IP     |
| Waveform size (150 samples)   | ~100K users| Medium   | Reduce to 15 samples                 |
| Single database for potholes  | ~1M users | Low       | Shard by geographic region           |

### When to Migrate Off Your Mac

**Trigger: ~1,000 users or ~100 simultaneous.**

At this point:
- Your home upload bandwidth may become noticeable.
- The tunnel adds latency and has reliability limits.
- A $30/month cloud VM (AWS t3.medium, GCP e2-medium) gives you:
  - Public IP (no tunnel needed)
  - 5 Gbps network (vs ~20 Mbps home upload)
  - Reliable uptime
  - Easy to scale up

The migration is straightforward: copy the Python server to the VM, point DNS
to the new IP, done. No code changes needed.

---

## 8. WHAT TO BUILD NOW TO NOT BLOCK SCALING

These design decisions should be made now even though they only matter at scale:

1. **Stateless API servers.** The FastAPI server must not store state in memory
   beyond the transient queue. All persistent state goes to storage. This
   ensures we can run multiple instances behind a load balancer.

2. **Configurable server URL in the app.** Already planned. Essential for
   migrating from Mac → cloud → CDN.

3. **Batch endpoint.** The `HitBatch` message already supports this. Batching
   amortizes HTTP overhead and is critical for bandwidth at scale.

4. **Waveform length as a server-configurable parameter.** The server should be
   able to tell the app "use 15 samples" via a config endpoint. This avoids
   pushing an app update to reduce bandwidth.

5. **Date/hour directory structure for storage.** Already planned. Makes it
   trivial to implement lifecycle policies (delete old data, move to cold
   storage).

6. **Device ID not tied to server state.** The server doesn't maintain sessions
   or per-device state. Each hit is independently processable. This is key for
   horizontal scaling.

7. **Use geographic coordinates as the primary key.** Potholes are inherently
   spatial. If we need to shard the database later, sharding by geographic
   region (e.g., by city or by geohash prefix) is natural.

---

## 9. COST COMPARISON: STARTUP vs. MASSIVE

| Item                 | 100 users  | 10M users    | Factor  |
|----------------------|-----------|--------------|---------|
| Compute              | $0 (Mac)  | $550         | —       |
| Storage              | $0 (Mac)  | $280         | —       |
| Bandwidth            | $0 (home) | $800-4,000   | —       |
| Maps (Android)       | $0        | $0           | Free!   |
| Maps (web/admin)     | $0        | $110         | —       |
| Queue infrastructure | $0        | $400         | —       |
| Database             | $0        | $300         | —       |
| Other                | $0        | $110         | —       |
| **Total**            | **$0**    | **$2,550-$5,750** | —  |

**Key takeaway:** 10 million users costs **$2,550-$5,750/month** in
infrastructure — not millions. The commonly-feared Google Maps cost is avoided
because the Android SDK is free and the web dashboard can use self-hosted OSM.

The only way costs explode is if you build a public-facing web map using Google
Maps JavaScript API with millions of visitors. Don't do that — keep the web
dashboard for city admins only, and show everything else in-app.
