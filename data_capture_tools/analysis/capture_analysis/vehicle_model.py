"""Quarter-car vehicle dynamics model for pothole impact simulation.

Implements a 2-DOF (sprung + unsprung mass) model that accurately
reproduces the vertical acceleration measured by a phone mounted
inside a vehicle hitting a pothole.

References:
    - Gillespie, T.D. (1992) "Fundamentals of Vehicle Dynamics", SAE
    - ISO 8608:2016 "Road surface profiles"
    - MathWorks quarter-car ADMM example
    - Thite (2012), Hindawi: refined quarter car model
"""

import numpy as np
from scipy.integrate import solve_ivp


# Default parameters for a sedan (Toyota Corolla class)
DEFAULT_PARAMS = {
    "m_s": 300,       # kg — sprung mass (quarter of body + driver)
    "m_u": 40,        # kg — unsprung mass (wheel + tire + brake + hub)
    "k_s": 20_000,    # N/m — suspension spring stiffness
    "c_s": 1_500,     # Ns/m — suspension damping coefficient
    "k_t": 180_000,   # N/m — tire vertical stiffness
    "c_t": 0,         # Ns/m — tire damping (conventionally neglected)
}

# Natural frequencies with default params:
#   Body bounce:  ~1.30 Hz
#   Wheel hop:   ~11.25 Hz
#   Damping ratio: ~0.306


class QuarterCarModel:
    """2-DOF quarter-car suspension model.

    State vector: x = [z_s, z_s_dot, z_u, z_u_dot]
      z_s = sprung mass (body) vertical displacement (m, positive up)
      z_u = unsprung mass (wheel) vertical displacement
    Input: z_r(t) = road profile (vertical displacement of road surface)
    Output: z_s_ddot = vertical acceleration of sprung mass (what the phone measures)
    """

    def __init__(self, **params):
        p = {**DEFAULT_PARAMS, **params}
        self.m_s = p["m_s"]
        self.m_u = p["m_u"]
        self.k_s = p["k_s"]
        self.c_s = p["c_s"]
        self.k_t = p["k_t"]
        self.c_t = p["c_t"]

    @property
    def body_bounce_hz(self):
        return np.sqrt(self.k_s / self.m_s) / (2 * np.pi)

    @property
    def wheel_hop_hz(self):
        return np.sqrt((self.k_s + self.k_t) / self.m_u) / (2 * np.pi)

    @property
    def damping_ratio(self):
        return self.c_s / (2 * np.sqrt(self.k_s * self.m_s))

    def simulate(self, t_eval, road_profile_func, x0=None):
        """Solve the quarter-car ODE for a given road profile.

        Args:
            t_eval: time points at which to evaluate (s), e.g. np.arange(0, 2, 1/500)
            road_profile_func: callable z_r(t) → vertical road displacement (m, negative = depression)
            x0: initial state [z_s, z_s_dot, z_u, z_u_dot], default all zeros

        Returns:
            dict with keys:
                t: time array (same as t_eval)
                z_s: sprung mass displacement (m)
                z_u: unsprung mass displacement (m)
                z_s_ddot: sprung mass acceleration (m/s², what the phone measures)
                z_r: road profile at each time point
        """
        if x0 is None:
            x0 = [0.0, 0.0, 0.0, 0.0]

        m_s, m_u = self.m_s, self.m_u
        k_s, c_s = self.k_s, self.c_s
        k_t, c_t = self.k_t, self.c_t

        def ode(t, x):
            z_s, z_s_dot, z_u, z_u_dot = x
            z_r = road_profile_func(t)
            # Estimate road velocity via small finite difference
            dt_fd = 1e-5
            z_r_dot = (road_profile_func(t + dt_fd) - road_profile_func(t - dt_fd)) / (2 * dt_fd)

            z_s_ddot = (-k_s * (z_s - z_u) - c_s * (z_s_dot - z_u_dot)) / m_s
            z_u_ddot = (k_s * (z_s - z_u) + c_s * (z_s_dot - z_u_dot)
                        - k_t * (z_u - z_r) - c_t * (z_u_dot - z_r_dot)) / m_u
            return [z_s_dot, z_s_ddot, z_u_dot, z_u_ddot]

        t_span = (t_eval[0], t_eval[-1])
        sol = solve_ivp(ode, t_span, x0, t_eval=t_eval, method='RK45',
                        max_step=1e-4, rtol=1e-8, atol=1e-10)

        z_s = sol.y[0]
        z_s_dot = sol.y[1]
        z_u = sol.y[2]
        z_u_dot = sol.y[3]

        # Sprung mass acceleration (what the accelerometer measures)
        z_s_ddot = (-k_s * (z_s - z_u) - c_s * (z_s_dot - z_u_dot)) / m_s
        z_r = np.array([road_profile_func(ti) for ti in t_eval])

        return {
            "t": t_eval,
            "z_s": z_s,
            "z_u": z_u,
            "z_s_ddot": z_s_ddot,
            "z_r": z_r,
        }


# ── Road profile generators ─────────────────────────────────────

def pothole_profile(depth_m, length_m, speed_mps, t_enter=0.0, wheel_radius=0.315):
    """Create a geometrically accurate pothole road profile for a circular wheel.

    Models a circular wheel (radius R) rolling over a rectangular pothole
    (vertical walls, flat bottom). The effective road height is determined
    by the actual contact point on the wheel's perimeter — NOT the road
    surface directly under the hub.

    Phases:
        1. Wheel on road (before entry edge)      → z = 0
        2. Wheel tips over entry edge (arc)        → z = √(R²-x²) - R
        3. Wheel on pothole bottom (if deep enough)→ z = -d
        4. Wheel climbs exit edge (arc)            → z = √(R²-(L-x_hub)²) - R
        5. Wheel back on road (after exit edge)    → z = 0

    Here x_hub is the horizontal distance the hub has traveled past the
    entry edge. The transition between phases depends on whether the
    wheel bridges the pothole (never touches bottom) or descends to it.

    Args:
        depth_m: pothole depth (m, positive)
        length_m: pothole length along travel direction (m)
        speed_mps: vehicle speed (m/s)
        t_enter: time when the hub reaches the entry edge (s)
        wheel_radius: tire radius (m, default 0.315 for 205/55R16)

    Returns:
        callable z_r(t) → effective road height at wheel contact point (m)
    """
    R = wheel_radius
    d = depth_m
    L = length_m
    v = speed_mps

    # Distance from entry edge where wheel contacts the bottom
    # (from the geometry: wheel on entry corner, center at height R,
    # bottom of wheel at height √(R²-x²) above corner, which equals
    # d when x = √(2Rd - d²))
    if d < R:
        x_touch_bottom = np.sqrt(2 * R * d - d**2)
    else:
        x_touch_bottom = R  # wheel drops entirely

    # Does the wheel bridge? (never touches bottom)
    bridges = L < 2 * x_touch_bottom

    if bridges:
        # The wheel transitions from entry corner to exit corner.
        # Transition happens at x_hub = L/2 (symmetry point).
        # On entry corner: z = √(R² - x²) - R
        # On exit corner:  z = √(R² - (L-x)²) - R
        x_transition = L / 2.0
    else:
        x_leave_bottom = L - x_touch_bottom

    def z_r(t):
        x = (t - t_enter) * v  # hub distance past entry edge

        if x <= 0 or x >= L + x_touch_bottom:
            # Phase 1 or 5: on road surface
            return 0.0

        if x >= L:
            # Phase 5 partial: hub past exit edge, wheel still climbing
            # Wheel pivots on exit corner, hub is x-L past it
            dx = x - L
            if dx >= x_touch_bottom:
                return 0.0
            if dx < R:
                return np.sqrt(R**2 - dx**2) - R
            return -R  # shouldn't happen for dx < x_touch_bottom

        if bridges:
            if x <= x_transition:
                # Tipping on entry corner
                if x < R:
                    return np.sqrt(R**2 - x**2) - R
                return -R
            else:
                # Tipping on exit corner
                dx_from_exit = L - x
                if dx_from_exit < R:
                    return np.sqrt(R**2 - dx_from_exit**2) - R
                return -R
        else:
            if x < x_touch_bottom:
                # Phase 2: tipping on entry corner (circular arc)
                return np.sqrt(R**2 - x**2) - R
            elif x <= x_leave_bottom:
                # Phase 3: on the pothole bottom
                return -d
            else:
                # Phase 4: climbing on exit corner (circular arc)
                dx_from_exit = L - x
                if dx_from_exit > 0 and dx_from_exit < R:
                    return np.sqrt(R**2 - dx_from_exit**2) - R
                return -d

    return z_r


def multi_pothole_profile(potholes, speed_mps):
    """Create a road profile with multiple potholes.

    Args:
        potholes: list of dicts with keys:
            t_enter: time when wheel enters (s)
            depth_m: depth (m, positive)
            length_m: length along travel (m)
        speed_mps: vehicle speed (m/s) — can also be a callable speed(t)

    Returns:
        callable z_r(t) → road displacement (m)
    """
    profiles = []
    for p in potholes:
        v = speed_mps if not callable(speed_mps) else speed_mps(p["t_enter"])
        if v < 0.5:  # skip potholes when nearly stopped
            continue
        profiles.append(pothole_profile(p["depth_m"], p["length_m"], v, p["t_enter"]))

    def z_r(t):
        return sum(pf(t) for pf in profiles)

    return z_r


def simulate_pothole_impact(depth_m=0.05, length_m=0.40, speed_mps=13.9,
                            duration_s=1.5, fs=500, wheel_radius=0.315,
                            **car_params):
    """Convenience function: simulate a single pothole impact.

    Args:
        depth_m: pothole depth (default 5 cm)
        length_m: pothole length (default 40 cm)
        speed_mps: vehicle speed (default 50 km/h = 13.9 m/s)
        duration_s: simulation duration (default 1.5s)
        fs: sample rate (default 500 Hz)
        wheel_radius: tire radius in meters (default 0.315 for 205/55R16)
        **car_params: override QuarterCarModel parameters

    Returns:
        dict with t, z_s_ddot (sprung mass acceleration), z_r (road profile),
        plus contact_timeline and metadata
    """
    model = QuarterCarModel(**car_params)

    # Pothole at t=0.2s (small lead-in for baseline)
    t_enter = 0.2
    T_cross = length_m / speed_mps
    profile = pothole_profile(depth_m, length_m, speed_mps, t_enter)

    t = np.arange(0, duration_s, 1.0 / fs)
    result = model.simulate(t, profile)
    result["T_cross"] = T_cross
    result["depth_m"] = depth_m
    result["length_m"] = length_m
    result["speed_mps"] = speed_mps
    result["body_bounce_hz"] = model.body_bounce_hz
    result["wheel_hop_hz"] = model.wheel_hop_hz
    result["damping_ratio"] = model.damping_ratio
    result["contact_timeline"] = compute_contact_timeline(
        depth_m, length_m, speed_mps, wheel_radius, t_enter
    )

    return result


# ── Wheel-pothole contact geometry ──────────────────────────────

def compute_contact_timeline(depth_m, length_m, speed_mps, wheel_radius=0.315, t_entry=0.0):
    """Compute the timing of wheel-pothole contact phases.

    Returns a dict with:
        t_entry:       wheel center reaches entry edge
        t_bottom:      wheel first contacts pothole bottom (None if bridging)
        t_leave_bottom: wheel leaves pothole bottom (None if bridging)
        t_exit_wall:   wheel contacts exit wall/corner
        t_back_on_road: wheel fully back on road surface
        bridges:       True if wheel never touches bottom
        airborne_start: time when wheel loses road surface contact
        airborne_end:  time when wheel regains road surface contact
        bottom_contact_angle_deg: angle on wheel perimeter where it hits exit wall
    """
    R = wheel_radius
    d = depth_m
    L = length_m
    v = speed_mps

    if v < 0.1:
        return {"t_entry": t_entry, "bridges": True}

    # Bridge span: max gap the wheel can cross at this depth
    if d < R:
        L_bridge = 2 * np.sqrt(2 * R * d - d**2)
    else:
        L_bridge = 0  # wheel always falls in if d >= R

    bridges = L < L_bridge

    # Distance from entry edge to where wheel contacts bottom
    if d < R:
        x_bottom = np.sqrt(2 * R * d - d**2)
    else:
        x_bottom = R  # free fall case

    # Timing (all relative to t_entry)
    result = {
        "t_entry": t_entry,
        "bridges": bridges,
        "L_bridge": L_bridge,
        "x_bottom": x_bottom,
    }

    if bridges:
        # Wheel transitions from entry corner to exit corner
        # Maximum descent = R - sqrt(R² - (L/2)²)
        max_descent = R - np.sqrt(R**2 - (L / 2)**2) if L < 2 * R else R
        result["max_descent_m"] = max_descent
        # Airborne from entry edge to exit recovery
        # The exit recovery mirror distance
        result["t_exit_wall"] = t_entry + L / v
        result["t_back_on_road"] = t_entry + (L + x_bottom) / v
        result["airborne_start"] = t_entry
        result["airborne_end"] = result["t_back_on_road"]
        result["t_bottom"] = None
        result["t_leave_bottom"] = None
    else:
        # Wheel touches bottom
        result["t_bottom"] = t_entry + x_bottom / v
        result["t_leave_bottom"] = t_entry + (L - x_bottom) / v
        result["t_exit_wall"] = t_entry + L / v

        # Contact angle on wheel where it hits the exit wall
        if d < R:
            contact_angle = np.degrees(np.arccos(1 - d / R))
        else:
            contact_angle = 180.0
        result["bottom_contact_angle_deg"] = contact_angle

        # Exit recovery: mirror of entry descent
        x_exit_recovery = x_bottom
        result["t_back_on_road"] = t_entry + (L + x_exit_recovery) / v

        # Airborne = entire period from entry to back on road
        result["airborne_start"] = t_entry
        result["airborne_end"] = result["t_back_on_road"]

    return result
