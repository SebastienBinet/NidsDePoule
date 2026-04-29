# Vehicle Physics Simulation Agent

Expert agent for the quarter-car vehicle dynamics model used to generate
realistic pothole impact signals for the NidsDePoule sensor capture analysis.

## Domain

- Quarter-car model (2-DOF: sprung mass + unsprung mass)
- Suspension dynamics (spring + damper)
- Tire dynamics (spring + damper)
- Road profile generation (pothole shapes, speed-dependent time domain)
- Accelerometer signal prediction on the vehicle body

## Key files

- `data_capture_tools/analysis/capture_analysis/vehicle_model.py` — Quarter-car ODE solver
- `data_capture_tools/analysis/capture_analysis/synthetic.py` — Uses vehicle_model to generate sensor data

## Physics model

The quarter-car model treats one corner of the vehicle as two masses
connected by springs and dampers:

```
          ┌──────────────┐
          │  Sprung mass │  (car body, ~300 kg per wheel)
          │   m_s        │
          └──────┬───────┘
                 │ k_s (suspension spring) + c_s (damper)
          ┌──────┴───────┐
          │ Unsprung mass│  (wheel + axle, ~40 kg)
          │   m_u        │
          └──────┬───────┘
                 │ k_t (tire stiffness) + c_t (tire damping)
          ───────┴──────── Road surface z_r(t)
```

State vector: x = [z_s, dz_s/dt, z_u, dz_u/dt]
Input: z_r(t) = road profile (pothole depth vs time)
Output: dz_s²/dt² = vertical acceleration of sprung mass (what the phone measures)

## Pothole road profile

A pothole is a depression of depth `d` and length `L`. At vehicle speed `v`,
the wheel traverses it in time `T = L/v`. The profile shape is typically:
- Half-sine entrance/exit (smooth transitions)
- The wheel drops by `d` over the entry, stays at depth, then rises over exit

## Key parameters to tune

- Pothole depth (2-15 cm typical)
- Pothole length (20-100 cm typical)
- Vehicle speed (10-80 km/h)
- Suspension stiffness (soft sedan vs stiff sport car)
- Tire pressure (affects tire stiffness)

## Commands

```bash
cd data_capture_tools/analysis
python -c "from capture_analysis.vehicle_model import QuarterCarModel; m = QuarterCarModel(); print(m)"
python -m pytest tests/ -v  # if tests exist
```

## Design principles

- Use scipy.integrate.solve_ivp for ODE solving
- All parameters in SI units
- Output must be compatible with synthetic.py's world-frame acceleration format
- Must handle pothole during turns (lateral + vertical coupling)
- Energy conservation: integral of acceleration over the event ≈ 0 (velocity returns to normal)
