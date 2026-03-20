package fr.nidsdepoule.app.store

import kotlin.math.*

/**
 * Converts EPSG:2950 (NAD83(CSRS) / MTM zone 8) projected coordinates
 * to WGS84 latitude/longitude.
 *
 * NAD83 and WGS84 are practically identical for our precision needs
 * (sub-metre difference), so we treat them as the same datum.
 */
object MtmProjection {

    // GRS80 ellipsoid (used by NAD83)
    private const val A = 6378137.0              // semi-major axis (m)
    private const val F = 1.0 / 298.257222101    // flattening
    private val B = A * (1.0 - F)                // semi-minor axis
    private val E2 = 2.0 * F - F * F             // eccentricity squared
    private val E = sqrt(E2)
    private val EP2 = E2 / (1.0 - E2)            // second eccentricity squared

    // MTM zone 8 projection parameters
    private const val LON0 = -73.5               // central meridian (degrees)
    private const val K0 = 0.9999                // scale factor
    private const val FALSE_EASTING = 304800.0   // false easting (m)
    private const val FALSE_NORTHING = 0.0       // false northing (m)

    // Precomputed coefficients for meridional arc
    private val E1 = (1.0 - sqrt(1.0 - E2)) / (1.0 + sqrt(1.0 - E2))

    /**
     * Convert MTM zone 8 easting/northing to WGS84 lat/lon.
     * @return Pair(latitudeDegrees, longitudeDegrees)
     */
    fun toLatLon(easting: Double, northing: Double): Pair<Double, Double> {
        val x = easting - FALSE_EASTING
        val y = northing - FALSE_NORTHING

        // Footpoint latitude
        val m = y / K0
        val mu = m / (A * (1.0 - E2 / 4.0 - 3.0 * E2 * E2 / 64.0 - 5.0 * E2 * E2 * E2 / 256.0))

        val phi1 = mu +
            (3.0 * E1 / 2.0 - 27.0 * E1 * E1 * E1 / 32.0) * sin(2.0 * mu) +
            (21.0 * E1 * E1 / 16.0 - 55.0 * E1 * E1 * E1 * E1 / 32.0) * sin(4.0 * mu) +
            (151.0 * E1 * E1 * E1 / 96.0) * sin(6.0 * mu) +
            (1097.0 * E1 * E1 * E1 * E1 / 512.0) * sin(8.0 * mu)

        val sinPhi1 = sin(phi1)
        val cosPhi1 = cos(phi1)
        val tanPhi1 = tan(phi1)

        val n1 = A / sqrt(1.0 - E2 * sinPhi1 * sinPhi1)
        val t1 = tanPhi1 * tanPhi1
        val c1 = EP2 * cosPhi1 * cosPhi1
        val r1 = A * (1.0 - E2) / (1.0 - E2 * sinPhi1 * sinPhi1).pow(1.5)
        val d = x / (n1 * K0)

        val lat = phi1 -
            (n1 * tanPhi1 / r1) * (
                d * d / 2.0 -
                (5.0 + 3.0 * t1 + 10.0 * c1 - 4.0 * c1 * c1 - 9.0 * EP2) * d * d * d * d / 24.0 +
                (61.0 + 90.0 * t1 + 298.0 * c1 + 45.0 * t1 * t1 - 252.0 * EP2 - 3.0 * c1 * c1) * d.pow(6) / 720.0
            )

        val lon = (
            d -
            (1.0 + 2.0 * t1 + c1) * d * d * d / 6.0 +
            (5.0 - 2.0 * c1 + 28.0 * t1 - 3.0 * c1 * c1 + 8.0 * EP2 + 24.0 * t1 * t1) * d.pow(5) / 120.0
        ) / cosPhi1

        return Pair(Math.toDegrees(lat), LON0 + Math.toDegrees(lon))
    }
}
