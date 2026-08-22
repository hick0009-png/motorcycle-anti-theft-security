package com.example.motorcycleantitheftsensor.network

import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.util.concurrent.TimeUnit

/**
 * SEC-03: TlsPinningClient
 * OkHttpClient instance configured with Certificate Pinning for api.telegram.org.
 * Enforces TLS 1.3/1.2 minimum and blocks Man-In-The-Middle (MITM) attacks.
 */
object TlsPinningClient {

    private val modernTlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
        .build()

    private val certificatePinner = CertificatePinner.Builder()
        .add("api.telegram.org", "sha256/AgyCmTysFOI6aQCSyQJ+QIXpnGn0v7n+D+mv6jWAtQc=")
        .add("api.telegram.org", "sha256/8Rw90Ej3Ttt8RRkrg+WYDS9n7IS03bk5bjP/UXPtaY8=")
        .build()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .certificatePinner(certificatePinner)
            .connectionSpecs(listOf(modernTlsSpec))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
