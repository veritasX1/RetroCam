package com.retrocam.app.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * A single, one-shot location read using the plain platform LocationManager
 * - deliberately not Google Play Services' FusedLocationProvider, so the
 * app has no background location tracking machinery and no extra
 * proprietary dependency: it asks the OS for a fix only at the moment a
 * photo is taken (and only if the user turned the setting on), and nothing
 * else. See SettingsScreen "Standort" section.
 */
object LocationProvider {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Returns the freshest available fix (last-known from GPS or network
     * provider, whichever is more recent) without waiting for a brand-new
     * fix - good enough for tagging a just-taken photo, and much faster
     * than requesting a live update. Returns null if no permission, no
     * provider enabled, or no fix has ever been recorded. */
    suspend fun lastKnownLocation(context: Context): Location? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        return providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
    }

    /** Reverse-geocodes to a postal code. Unlike [lastKnownLocation], this
     * goes through Android's Geocoder, which on most phones (any with
     * Google Play services) makes a network request to resolve the
     * address - only called when the user has explicitly chosen "Postal
     * code" for the burned-in location stamp, never for plain coordinates. */
    suspend fun postalCode(context: Context, latitude: Double, longitude: Double): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context)
        val address: Address? = if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(latitude, longitude, 1) { results ->
                    cont.resume(results.firstOrNull())
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull() }.getOrNull()
            }
        }
        return address?.postalCode
    }
}
