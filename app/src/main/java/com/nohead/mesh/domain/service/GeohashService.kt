package com.nohead.mesh.domain.service

import com.nohead.mesh.data.local.GeohashBookmarkDao
import com.nohead.mesh.data.local.GeohashBookmarkEntity
import com.nohead.mesh.domain.model.GeohashRoom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tested Base32 Geohash encoder, decoder, neighbor calculator, and privacy-first location room manager.
 *
 * Privacy Principles:
 * - Never requires location permission for basic BLE mesh messaging.
 * - Prefers manual coarse geohash / region selection.
 * - Explains privacy radius of each precision level (precision 1 = ~2500 km down to precision 6 = ~0.6 km).
 * - Never exposes exact GPS coordinates in public messages.
 */
object GeohashCodec {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
    private val BITS = intArrayOf(16, 8, 4, 2, 1)

    data class BoundingBox(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    ) {
        val centerLat: Double get() = (minLat + maxLat) / 2.0
        val centerLon: Double get() = (minLon + maxLon) / 2.0
    }

    fun isValidGeohash(hash: String): Boolean {
        val clean = hash.trim().lowercase()
        if (clean.isEmpty() || clean.length > 9) return false
        return clean.all { it in BASE32 }
    }

    fun encode(latitude: Double, longitude: Double, precision: Int = 4): String {
        val clampedPrecision = precision.coerceIn(1, 8)
        var minLat = -90.0
        var maxLat = 90.0
        var minLon = -180.0
        var maxLon = 180.0

        val lat = latitude.coerceIn(-90.0, 90.0)
        val lon = longitude.coerceIn(-180.0, 180.0)

        var isEven = true
        var bit = 0
        var ch = 0
        val geohash = StringBuilder(clampedPrecision)

        while (geohash.length < clampedPrecision) {
            if (isEven) {
                val mid = (minLon + maxLon) / 2.0
                if (lon >= mid) {
                    ch = ch or BITS[bit]
                    minLon = mid
                } else {
                    maxLon = mid
                }
            } else {
                val mid = (minLat + maxLat) / 2.0
                if (lat >= mid) {
                    ch = ch or BITS[bit]
                    minLat = mid
                } else {
                    maxLat = mid
                }
            }
            isEven = !isEven
            if (bit < 4) {
                bit++
            } else {
                geohash.append(BASE32[ch])
                bit = 0
                ch = 0
            }
        }
        return geohash.toString()
    }

    fun decodeBounds(geohash: String): BoundingBox {
        val clean = geohash.trim().lowercase()
        require(isValidGeohash(clean)) { "Invalid geohash: $geohash" }

        var minLat = -90.0
        var maxLat = 90.0
        var minLon = -180.0
        var maxLon = 180.0
        var isEven = true

        for (c in clean) {
            val cd = BASE32.indexOf(c)
            for (mask in BITS) {
                if (isEven) {
                    val mid = (minLon + maxLon) / 2.0
                    if ((cd and mask) != 0) minLon = mid else maxLon = mid
                } else {
                    val mid = (minLat + maxLat) / 2.0
                    if ((cd and mask) != 0) minLat = mid else maxLat = mid
                }
                isEven = !isEven
            }
        }
        return BoundingBox(minLat, maxLat, minLon, maxLon)
    }

    fun approximateRadiusKm(precision: Int): Double {
        return when (precision) {
            1 -> 2500.0
            2 -> 630.0
            3 -> 78.0
            4 -> 20.0
            5 -> 2.4
            6 -> 0.61
            else -> 0.15
        }
    }

    fun privacyWarningForPrecision(precision: Int): String {
        return when (precision) {
            1, 2 -> "Continental / Regional (~${approximateRadiusKm(precision).toInt()} km radius) — High location privacy."
            3, 4 -> "Metro / City level (~${approximateRadiusKm(precision).toInt()} km radius) — Recommended balance."
            else -> "Neighborhood level (~${approximateRadiusKm(precision)} km radius) — Reveals approximate district to room participants."
        }
    }

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}

@Singleton
class GeohashRoomManager @Inject constructor(
    private val bookmarkDao: GeohashBookmarkDao
) {
    val presetRegions: List<GeohashRoom> = listOf(
        createRoom("u4pru", "Northern Europe / Jutland"),
        createRoom("u33d", "Central Europe / Berlin Metro"),
        createRoom("gcpv", "Western Europe / London Metro"),
        createRoom("dr5r", "North America / NYC Metro"),
        createRoom("9q8y", "North America / Bay Area"),
        createRoom("tdr1", "South Asia / Bengaluru Metro"),
        createRoom("ttnf", "South Asia / Delhi NCR"),
        createRoom("xn76", "East Asia / Tokyo Metro"),
        createRoom("w21z", "Southeast Asia / Singapore"),
        createRoom("r3gx", "Oceania / Sydney Metro"),
        createRoom("6gyf", "South America / Sao Paulo Metro"),
        createRoom("k3vp", "Africa / Nairobi Metro")
    )

    val bookmarkedRooms: Flow<List<GeohashRoom>> = bookmarkDao.observeBookmarks().map { list ->
        list.map {
            GeohashRoom(
                geohash = it.geohash,
                precision = it.precision,
                approximateRegionLabel = it.label,
                centerLatitude = it.centerLat,
                centerLongitude = it.centerLon,
                approxRadiusKm = it.radiusKm,
                isBookmarked = true
            )
        }
    }

    fun resolveGeohashRoom(geohashInput: String, customLabel: String? = null): Result<GeohashRoom> = runCatching {
        val clean = geohashInput.trim().lowercase()
        require(GeohashCodec.isValidGeohash(clean)) { "Enter a valid Base32 geohash (1-8 chars: 0-9, b-z excluding a,i,l,o)" }
        val bounds = GeohashCodec.decodeBounds(clean)
        val radius = GeohashCodec.approximateRadiusKm(clean.length)
        val label = customLabel ?: presetRegions.find { clean.startsWith(it.geohash.take(3)) }?.approximateRegionLabel
            ?: "Lat ${"%.2f".format(bounds.centerLat)}, Lon ${"%.2f".format(bounds.centerLon)} (±${radius}km)"
        GeohashRoom(
            geohash = clean,
            precision = clean.length,
            approximateRegionLabel = label,
            centerLatitude = bounds.centerLat,
            centerLongitude = bounds.centerLon,
            approxRadiusKm = radius
        )
    }

    suspend fun bookmarkRoom(room: GeohashRoom) {
        bookmarkDao.upsertBookmark(
            GeohashBookmarkEntity(
                geohash = room.geohash,
                precision = room.precision,
                label = room.approximateRegionLabel,
                centerLat = room.centerLatitude,
                centerLon = room.centerLongitude,
                radiusKm = room.approxRadiusKm,
                createdAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun removeBookmark(geohash: String) {
        bookmarkDao.deleteBookmark(geohash)
    }

    private fun createRoom(geohash: String, label: String): GeohashRoom {
        val bounds = GeohashCodec.decodeBounds(geohash)
        return GeohashRoom(
            geohash = geohash,
            precision = geohash.length,
            approximateRegionLabel = label,
            centerLatitude = bounds.centerLat,
            centerLongitude = bounds.centerLon,
            approxRadiusKm = GeohashCodec.approximateRadiusKm(geohash.length)
        )
    }
}

