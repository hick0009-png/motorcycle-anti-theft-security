package com.example.motorcycleantitheftsensor.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

fun interface LocationLabelResolver {
    suspend fun resolve(fix: TrackedLocationFix): String?
}

fun interface GeocoderGateway {
    suspend fun reverse(latitude: Double, longitude: Double): List<Address>
}

class AndroidGeocoderGateway(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val locale: Locale = Locale.forLanguageTag("th-TH"),
) : GeocoderGateway {

    private val legacyIoDispatcher = Dispatchers.IO.limitedParallelism(1)

    override suspend fun reverse(latitude: Double, longitude: Double): List<Address> {
        if (!Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(context, locale)

        return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            Api33Geocoder.reverse(geocoder, latitude, longitude)
        } else {
            withContext(legacyIoDispatcher) {
                try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(latitude, longitude, 1) ?: emptyList()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object Api33Geocoder {
    suspend fun reverse(
        geocoder: Geocoder,
        latitude: Double,
        longitude: Double,
    ): List<Address> = suspendCancellableCoroutine { cont ->
        try {
            geocoder.getFromLocation(
                latitude,
                longitude,
                1,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (cont.isActive) {
                            cont.resume(addresses)
                        }
                    }

                    override fun onError(errorMessage: String?) {
                        if (cont.isActive) {
                            cont.resume(emptyList())
                        }
                    }
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (cont.isActive) {
                cont.resume(emptyList())
            }
        }
    }
}

class AndroidLocationLabelResolver(
    private val gateway: GeocoderGateway,
    private val timeoutMs: Long = 1_500L,
) : LocationLabelResolver {

    constructor(context: Context) : this(AndroidGeocoderGateway(context))

    override suspend fun resolve(fix: TrackedLocationFix): String? {
        return withTimeoutOrNull(timeoutMs) {
            try {
                val addresses = gateway.reverse(fix.latitude, fix.longitude)
                val address = addresses.firstOrNull() ?: return@withTimeoutOrNull null
                formatAddress(address)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    companion object {
        fun formatAddress(address: Address): String? {
            val parts = mutableListOf<String>()
            address.featureName?.takeIf { it.isNotBlank() }?.let { parts += it }
            address.thoroughfare?.takeIf { it.isNotBlank() && it != address.featureName }?.let { parts += it }
            address.subLocality?.takeIf { it.isNotBlank() && !parts.contains(it) }?.let { parts += it }
            address.locality?.takeIf { it.isNotBlank() && !parts.contains(it) }?.let { parts += it }
            address.adminArea?.takeIf { it.isNotBlank() && !parts.contains(it) }?.let { parts += it }

            if (parts.isEmpty()) return null

            val label = parts.joinToString(", ")
            return if (label.length > 160) label.take(160) else label
        }
    }
}
