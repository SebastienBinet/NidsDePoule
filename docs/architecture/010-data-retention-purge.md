# ADR-010: Data Retention and Purge Strategy

**Status:** Accepted
**Date:** 2025-02-17
**Decision makers:** Sébastien Binet, Claude (AI assistant)

## On-Device Strategy

### After Successful Upload
- **Delete hit data immediately** from local SQLite buffer after server
  confirms receipt (HTTP 200 with `ServerResponse.accepted = true`).
- Keep no local history beyond the current session's circular buffer
  (60 seconds of accelerometer data for the graph).

### During Wi-Fi Batch Mode
- Hits accumulate in local SQLite database until Wi-Fi is available.
- Maximum local buffer size: configurable, default 10,000 hits (~7 MB).
- If buffer is full, drop oldest hits (FIFO) to make room for new ones.
- A hit that has been in the buffer for more than 7 days without upload
  is considered stale and can be dropped.

### Data Usage Counters
- Bytes sent per minute, per hour, per month are tracked in SharedPreferences.
- Monthly counter resets on the 1st of each month.
- These counters persist across app restarts but are not backed up.

## On-Server Strategy

### Raw Data Retention
- **Default: Keep everything.** Disk is cheap at startup scale
  (~130 GB/year for 100 users).
- Data is stored in date/hour directory structure:
  `data/incoming/YYYY/MM/DD/HH/`

### Developer Purge Tools

Two purge operations are provided as CLI tools (not automated):

#### 1. Purge by Age — Remove Oldest 90%

```bash
python -m tools.scripts.purge --mode=age --keep-percent=10
```

**How it works:**
1. List all date/hour directories in chronological order.
2. Calculate total data size.
3. Delete oldest directories until only the newest 10% (by size) remains.
4. Print a summary of what was deleted.

**Use case:** Free up disk space during development. Keep only recent data.

#### 2. Purge by Severity — Remove Weakest 90%

```bash
python -m tools.scripts.purge --mode=severity --keep-percent=10
```

**How it works:**
1. Scan all `.binpb` files.
2. Read each `ServerHitRecord`, extract `peak_vertical_mg` from the hit
   pattern.
3. Sort all records by peak acceleration.
4. Rewrite files, keeping only the top 10% strongest hits.
5. Rebuild the JSON Lines index.

**Use case:** Focus analysis on significant potholes. Remove noise from
light bumps.

#### 3. Purge by Device — Remove a Specific Device's Data

```bash
python -m tools.scripts.purge --mode=device --device-id=<UUID>
```

**How it works:**
1. Scan all `.binpb` files.
2. Remove records matching the given device ID.
3. Rewrite files and rebuild JSON Lines index.

**Use case:** User requests data deletion (privacy). Developer resets test
data from a specific device.

### Safety Features

- **Dry run mode:** All purge commands support `--dry-run` which shows what
  would be deleted without actually deleting.
- **Backup before purge:** Optionally copy data to an archive directory
  before deletion (`--backup-dir=/path/to/backup`).
- **Confirmation prompt:** Unless `--yes` is passed, the tool asks for
  confirmation before deleting.

### Future: Automated Lifecycle Policies

When the system moves to cloud storage (S3), implement automatic lifecycle
rules:

| Data age         | Storage tier     | Cost/GB/month |
|------------------|-----------------|---------------|
| 0-30 days        | S3 Standard     | $0.023        |
| 30-90 days       | S3 Infrequent   | $0.0125       |
| 90 days - 1 year | S3 Glacier      | $0.004        |
| > 1 year         | Delete (unless flagged as important) | $0 |

### Pothole Entity Data (Future)

Once pothole clustering is implemented:
- **Pothole entities are kept indefinitely.** They are small (one record per
  pothole, not per hit).
- A pothole entity stores: position, severity score, hit count, first/last hit
  date, city acknowledgement status.
- Raw hits can be deleted after they've been processed into pothole entities.
- The pothole database (SQLite/PostgreSQL) is the long-term source of truth.

### Summary

| Data type          | Retention            | Notes                  |
|--------------------|---------------------|------------------------|
| On-device hits     | Until uploaded       | Deleted after server ACK |
| On-device buffer   | 7 days max           | FIFO if buffer full    |
| Server raw hits    | Keep all (dev purge) | Date/hour directories  |
| Server JSON index  | Same as raw hits     | Rebuilt on purge       |
| Pothole entities   | Indefinite           | Small, aggregated data |
| Usage counters     | Phone lifetime       | Monthly reset          |
