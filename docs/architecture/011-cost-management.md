# ADR-011: Cost Management — Running on $0-10/month

**Status:** Accepted
**Date:** 2025-02-17
**Decision makers:** Sébastien Binet, Claude (AI assistant)

## Context

This is a hobby project with no revenue goal. Sébastien can spend up to
$10/month. The system must be designed to run for free as long as possible,
with clear cost thresholds and mitigation strategies.

## The $0/month Stack (Phase 1-2, up to ~5,000 users)

### Discovery: Oracle Cloud Free Tier

Oracle Cloud offers a **permanently free** tier that is absurdly generous:

| Resource            | Free allowance          | What we need (100 users) |
|---------------------|------------------------|--------------------------|
| ARM Compute         | 4 OCPU, 24 GB RAM      | 1 OCPU, 2 GB RAM        |
| Block storage       | 200 GB                 | 20 GB                   |
| Outbound bandwidth  | 10 TB/month            | ~3 GB/month             |

This single free tier can comfortably host the server for **thousands of users**
at zero cost. For comparison, the Mac-on-desk approach works too, but Oracle
Cloud gives:
- Public IP (no tunnel needed, though we'll still use Cloudflare Tunnel for HTTPS)
- 99.9% uptime (vs your Mac sleeping or losing power)
- ARM CPU (efficient, FastAPI runs well on ARM)
- Located in a data center (low latency for all users)

### Revised Infrastructure: $0/month

```
Phone → Cloudflare Tunnel (free) → Oracle Cloud VM (free)
                                      ├── FastAPI server
                                      ├── Local disk storage (200 GB free)
                                      └── Cloudflare R2 overflow (10 GB free)
```

| Component                | Provider             | Cost    |
|--------------------------|---------------------|---------|
| Server compute           | Oracle Cloud Free   | $0      |
| Disk storage (200 GB)    | Oracle Cloud Free   | $0      |
| HTTPS tunnel             | Cloudflare Tunnel   | $0      |
| Overflow storage (10 GB) | Cloudflare R2       | $0      |
| Domain name              | Optional            | $0-1/mo |
| Google Maps (Android)    | Google Maps SDK     | $0      |
| Google Maps (web admin)  | Google Maps JS API  | $0 (free tier: 10K loads/mo) |
| **Total**                |                     | **$0**  |

### How Long Does $0 Last?

| Users   | Hits/day  | Storage/month | Bandwidth/month | Still free? |
|---------|----------|---------------|-----------------|-------------|
| 100     | 14K      | 1 GB          | 3 GB            | Yes         |
| 1,000   | 140K     | 10 GB         | 30 GB           | Yes         |
| 5,000   | 700K     | 50 GB         | 150 GB          | Yes         |
| 10,000  | 1.4M     | 100 GB        | 300 GB          | Yes (tight) |
| 25,000  | 3.5M     | 250 GB        | 750 GB          | Storage limit hit |

**The $0 stack handles up to ~10,000-20,000 users** before hitting the 200 GB
storage limit. At that point, the oldest data can be purged, or we switch to
Cloudflare R2 for overflow.

With waveform reduction (150 → 15 samples), storage drops by ~10×, extending
the free tier to **~100,000 users**.

---

## When $0 Is No Longer Enough: Graduated Strategies

### Tier 1: $0/month (up to ~10K users)
Oracle Cloud Free + Cloudflare Tunnel + Cloudflare R2.
No changes needed.

### Tier 2: $5/month (~10K-50K users)
- Domain name: ~$1/month (optional but professional).
- Cloudflare R2 paid: $0.015/GB/month beyond 10 GB. At 100 GB = $1.35/month.
- Total: ~$2-5/month.

### Tier 3: $10/month (~50K-100K users)
- Second Oracle Cloud account (free tier is per-account, per-tenancy).
  Run a second VM for redundancy.
- OR: add a $5/month Hetzner ARM VPS (4 vCPU, 8 GB, 20 TB bandwidth) as
  a secondary node.
- Reduce waveform to 15 samples (cuts storage 10×).
- Total: $5-10/month.

### Tier 4: Beyond $10/month (>100K users)
This is where you need either revenue or cost-limiting measures. See below.

---

## Cost-Limiting Measures (Knobs to Turn)

These are things you can activate at any time to cap costs without killing the
service:

### 1. Rate Limiting Per Device (Recommended First Lever)

**How:** Server returns a `max_hits_per_hour` in the response. App respects it.
If a user generates more hits than the limit, the app keeps them locally but
doesn't upload until the next hour.

**Effect:** Directly controls server load and storage growth.

**Suggested limits:**
| Tier     | Hits/hour limit | Rationale                        |
|----------|----------------|----------------------------------|
| Normal   | 600 (10/min)   | Plenty for city driving           |
| Savings  | 60 (1/min)     | Still catches major potholes      |
| Minimal  | 10             | Only the worst hits               |

**Impact:** At "Savings" mode, 100K users generate 10× less data. The $0 stack
extends to effectively 1M users.

### 2. Waveform Reduction (Biggest Single Lever)

**How:** Server config endpoint tells the app how many waveform samples to send.

| Samples | Hit size | Storage for 100K users/month |
|---------|---------|------------------------------|
| 150     | ~700 B  | 100 GB                       |
| 50      | ~250 B  | 36 GB                        |
| 15      | ~100 B  | 14 GB                        |
| 0       | ~40 B   | 6 GB                         |

**Impact:** Going from 150 to 15 samples is a 7× storage reduction with
minimal loss of useful data (once the hit patterns are understood).

### 3. Geographic Throttling

**How:** Server accepts hits only from active geographic zones (e.g., cities
that are participating). Hits from outside these zones get HTTP 202 (accepted
but not stored).

**Effect:** Limits data to where it's actually useful.

### 4. Smart De-duplication

**How:** If a known pothole already has 50+ hits, additional hits at that
location are acknowledged but not stored (just increment the counter).

**Effect:** Dramatically reduces storage for well-known potholes. A pothole
hit by 1,000 users stores ~50 raw records + a counter, not 1,000 records.

### 5. Batch-Only Mode

**How:** Disable real-time uploads. All users switch to Wi-Fi-batch mode.
Server only accepts uploads during off-peak hours (e.g., 1am-6am).

**Effect:** Smooths load peaks. Allows the server to ingest at its own pace.
Also saves users' cellular data.

### 6. Maximum User Cap

**How:** Server stops accepting new device registrations after N devices.
Existing users continue to work.

**This should be the last resort**, not the first. The other measures above
can handle 10-100× more users before a hard cap is needed.

---

## Revenue Ideas (If Needed, If You Want)

If the project grows beyond what free tiers cover and you want to keep it
running without paying out of pocket:

### Option A: Voluntary Donations (Easiest, No Commitment)

- **GitHub Sponsors** — add a "Sponsor" button to the repo. Users who care can
  donate.
- **Buy Me a Coffee / Ko-fi** — one-time donations. Link in the app's "About"
  screen.
- **Liberapay** — recurring donations, open-source focused.

Expected: $0-50/month. Unpredictable but zero effort to maintain.

### Option B: City Sponsorship (Most Aligned With the Mission)

- Cities that want admin access (acknowledge/repair potholes) pay a small fee.
- Suggested: $20-50/month per city. Covers hosting + your time.
- 5 cities = $100-250/month → covers any hosting cost up to 10M users.
- This is the **most natural revenue model**: cities get direct value
  (pothole reports from citizens), and citizens use it for free.

**How to approach cities:**
1. Run the app for free in your own city for 6 months.
2. Collect data showing pothole locations and severity.
3. Present the data to the city's road maintenance department.
4. Offer: "For $30/month, you get a dashboard to see and manage these."

### Option C: Open Data Grant

- Apply for civic tech grants (e.g., French "French Tech" initiatives,
  EU civic innovation funds).
- Open-source the data (anonymized). Research institutions may sponsor hosting
  in exchange for data access.

### Option D: Premium Features (Not Recommended for a Hobby)

- Charge for features like "show potholes ahead on your route" or
  "export data." This adds complexity (payments, accounts, support) that
  doesn't fit a hobby project.

### Recommendation

**Do nothing about revenue until your costs exceed $10/month.** Given the
Oracle Cloud Free Tier, that won't happen until ~50,000+ users. If it does:
1. First: activate cost-limiting measures (rate limiting + waveform reduction).
2. Second: add a "Buy Me a Coffee" link in the app.
3. Third: approach 2-3 cities for sponsorship.

---

## Decision: Oracle Cloud Free Tier as Primary Infrastructure

**Migration path from Mac:**

| Phase | Infrastructure              | Cost  | When                     |
|-------|----------------------------|-------|--------------------------|
| Dev   | Mac on desk + CF Tunnel    | $0    | Now (development)        |
| v1    | Oracle Cloud VM + CF Tunnel| $0    | When ready for real users |
| Scale | + Cloudflare R2 overflow   | $0-5  | When storage > 200 GB    |
| Big   | + second VM or Hetzner     | $5-10 | When >50K users          |

**Important: keep the Mac as a development/staging environment.** Deploy to
Oracle Cloud for production. The code is the same (Python + FastAPI), just
running on ARM instead of x86. FastAPI works identically on both.

### Oracle Cloud Setup Notes (for later)

1. Sign up at cloud.oracle.com (credit card required but never charged for
   Always Free resources).
2. Choose a region close to your users (e.g., eu-marseille-1 for France).
3. Create an Ampere A1 instance (1 OCPU, 6 GB RAM is a good start).
4. Install Python 3.11, copy the server code, set up systemd service.
5. Point Cloudflare Tunnel to the VM (or use the VM's public IP directly with
   Let's Encrypt for HTTPS).

---

## Summary: What Costs What

| Question                                  | Answer                              |
|-------------------------------------------|-------------------------------------|
| Can I run this for free?                   | Yes, up to ~10K-20K users          |
| When does it cost $5/month?               | ~20K-50K users (storage overflow)  |
| When does it cost $10/month?              | ~50K-100K users                    |
| When does it cost $100/month?             | Only if I skip all cost-limiting measures at 500K+ users |
| What's the single biggest cost lever?     | Waveform size (150 → 15 samples = 7× reduction) |
| Should I limit users?                      | Last resort. Rate-limit and de-duplicate first |
| Do I need revenue?                         | Not until >50K users, and even then city sponsorship at $30/city covers it |
