# Wheel-Pothole Contact Geometry

## Overview

When a wheel (circle of radius R) passes through a rectangular pothole
(depth d, length L) at speed v, several contact phases occur. The timing
of these phases produces characteristic acceleration signatures that the
detection algorithm must recognize.

## Parameters

| Symbol | Description | Typical value |
|--------|-------------|---------------|
| R | Wheel radius (205/55R16 tire) | 0.315 m |
| d | Pothole depth | 0.02 – 0.15 m |
| L | Pothole length (along travel) | 0.15 – 1.00 m |
| v | Vehicle speed | 5 – 25 m/s (18-90 km/h) |

## Pothole cross-section

```
          Road surface                    Road surface
    ──────────┐                     ┌──────────
              │← entry edge         │← exit edge
              │                     │
         d    │     pothole         │    d
              │     bottom          │
              └─────────────────────┘
              ←──────── L ─────────→
```

## Key geometric threshold

The wheel's maximum bridge span at depth d:

```
L_bridge = 2 × √(2Rd - d²)
```

If **L < L_bridge**: the wheel bridges the pothole (never touches the bottom).
If **L ≥ L_bridge**: the wheel descends to the bottom.

### Bridge span examples (R = 0.315 m)

| Depth d | L_bridge | Meaning |
|---------|----------|---------|
| 2 cm | 22.3 cm | Potholes shorter than 22cm are bridged |
| 3 cm | 27.2 cm | |
| 5 cm | 35.0 cm | |
| 8 cm | 43.8 cm | |
| 10 cm | 48.5 cm | |
| 15 cm | 57.4 cm | |

## Contact phases

### Case A: Deep pothole (L ≥ L_bridge) — wheel touches bottom

```
Phase 1        Phase 2        Phase 3        Phase 4        Phase 5        Phase 6
On road        Entry tip       Free fall /    On bottom      Exit tip        On road
                               descent

    O              O              O             O              O              O
   /|\            /|              |             |              |\            /|\
  ─────┐        ──┐              │             │           ┌──         ┌─────
       │          ●              │   ●         │     ●     ●  │       │
       │          │              │   │         │     │     │  │       │
       └──────    └──────  ──────┘   └───  ────┘     └─────┘  └────   └──────

● = contact point
```

**Phase 1: On road surface**
- Wheel center at height R above road
- Contact point directly below center
- Ends when: center x = entry edge (x_entry)

**Phase 2: Entry tipping**
- Wheel pivots on entry corner
- Center traces a circular arc: y_center = √(R² - (x - x_entry)²)
- Center drops from height R toward R-d
- Contact point: entry corner
- Duration: until wheel touches bottom or exit edge

**Phase 3: Descent to bottom** (if deep enough)
- Contact transitions from entry corner to pothole bottom
- x_contact_bottom = √(2Rd - d²) from entry edge
- Wheel center height = R - d (measured from road) = R above pothole bottom

**Phase 4: On pothole bottom**
- Wheel rolls on the bottom surface
- Contact point directly below center, on the bottom
- Center at constant height R - d below road surface
- Duration: from x_contact_bottom to L - x_contact_exit (mirror distance from exit)

**Phase 5: Exit tipping**
- Wheel reaches the exit edge
- The wheel's perimeter contacts the exit corner
- The contact point on the wheel where it hits the 90° exit edge:
  - The wheel is at the bottom level (center at y = -(d - R) from road)
  - The exit corner is at (L, 0) 
  - The contact angle θ on the wheel is: θ = arctan(d / x_remaining)
  - where x_remaining is the horizontal distance from wheel center to exit edge
  - The wheel hits the exit wall at angle θ = arcsin(d / R) from the bottom

**Phase 6: Climbing out**
- Wheel pivots on exit corner
- Mirror of Phase 2
- Center rises back to road level

### Case B: Shallow pothole (L < L_bridge) — wheel bridges over

```
Phase 1        Phase 2        Phase 3        Phase 4
On road        Entry tip       Bridge/Exit    On road

    O              O              O              O
   /|\            /|             /|\            /|\
  ─────┐        ──┐           ┌──          ┌─────
       │          ●           ●  │         │
       │          │    gap    │  │         │
       └──────    └───────────┘  └────     └──────

The wheel never touches the bottom.
```

**Phase 2: Entry tipping**
- Same as Case A
- Wheel pivots on entry corner
- Center descends along circular arc

**Phase 3: Direct to exit**
- Before touching the bottom, the wheel reaches a point where
  the exit corner becomes the highest contact point
- The wheel transitions from pivoting on entry corner to pivoting
  on exit corner
- The transition happens when the wheel is tangent to BOTH corners
  simultaneously (the bridging moment)
- Maximum descent = R - √(R² - (L/2)²)

**Phase 4: Exit climb**
- Wheel pivots on exit corner
- Mirror of entry

### Case C: Very deep pothole (d > R) — wheel drops into gap

If the pothole is deeper than the wheel radius, the wheel falls
completely into the pothole. This is rare for car tires but possible
for severe road damage.

- The wheel free-falls until it contacts the bottom
- Impact is more severe due to the free-fall component

### Case D: Wheel hits exit wall (not just corner)

When the wheel is on the pothole bottom and the exit wall is vertical,
the wheel doesn't just "tip" over the exit corner — it first contacts
the vertical wall face.

```
                     ┌──── road
              O      │
             /|\     │
            / | \    │
    ────────  │  ●   │   ← wheel contacts vertical wall
              │      │
    ──────────┘      └──── 
```

The contact point on the vertical wall is at height:
```
h_contact = R - √(R² - x_gap²)
```
where x_gap is the horizontal distance from wheel center to the wall.

The wheel contacts the wall when:
```
x_center = L - √(R² - (R - d)²) = L - √(2Rd - d²)
```

The contact angle on the wheel perimeter (measured from bottom):
```
θ_wall = arccos((R - d) / R) = arccos(1 - d/R)
```

For d = 5cm, R = 31.5cm: θ_wall = arccos(0.841) = 32.7°

## Timing calculations

All times are relative to t_entry (when wheel center is at the entry edge).

### Time to reach pothole bottom

```
t_bottom = x_bottom / v
x_bottom = √(2Rd - d²)      (if L ≥ L_bridge)
```

For R=0.315, d=0.05, v=13.9 m/s:
x_bottom = √(2 × 0.315 × 0.05 - 0.05²) = √(0.029) = 0.170 m
t_bottom = 0.170 / 13.9 = 12.2 ms

### Time when wheel is on the bottom

```
t_on_bottom = (L - 2 × x_bottom) / v    (if L ≥ L_bridge)
```

### Time to reach exit edge

```
t_exit = L / v
```

For L=0.40, v=13.9: t_exit = 28.8 ms

### Airborne period

The wheel is not touching the road surface from t=0 (entry edge) to
t_back_on_road (when the exit tipping is complete).

The exit tipping is complete when the wheel center has risen back to
height R above the road. The exit mirror of the entry tipping gives:

```
t_back_on_road = (L + x_bottom_mirror) / v
```

So the total "disturbed" period (not on normal road) is:
```
T_disturbed = (L + 2 × x_exit_recovery) / v
```

where x_exit_recovery ≈ x_bottom (symmetric for entry and exit).

## Descent profile (height of wheel center vs position)

**Over entry corner** (x measured from entry edge):
```
y_center(x) = √(R² - x²)     for 0 ≤ x ≤ x_bottom
```

**On bottom** (if applicable):
```
y_center(x) = R - d            for x_bottom ≤ x ≤ L - x_bottom
```

**Over exit corner** (x measured from entry edge):
```
y_center(x) = √(R² - (L - x)²)   for L - x_bottom ≤ x ≤ L
```

All heights measured from road surface level (positive up).
The road surface height is R (wheel center at height R when on road).
So the descent is: Δy = R - y_center(x).

## Practical impact on accelerometer signal

The quarter-car model already produces the correct oscillation. The
contact geometry adds information about:

1. **When the initial dip starts** (entry edge → first loss of road contact)
2. **When the main impact occurs** (bottom contact or exit edge impact)
3. **Duration of the "airborne" phase** (wheel not on road surface)

These timings help correlate the accelerometer peaks with the physical
events, which is crucial for understanding what the detection algorithm
needs to look for.

## Visual markers in the drill-down

| Marker | Color | Meaning |
|--------|-------|---------|
| Red dashed line | Red | Event time (entry edge) |
| Orange dashed line | Orange | Wheel contacts bottom (if applicable) |
| Blue dashed line | Blue | Wheel contacts exit edge |
| Red horizontal bar | Red | Period when wheel is not on road surface |
