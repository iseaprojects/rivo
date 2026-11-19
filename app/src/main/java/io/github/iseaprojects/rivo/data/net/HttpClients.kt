package io.github.iseaprojects.rivo.data.net

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * One process-wide OkHttp client.
 *
 * Sharing it keeps the connection pool, TLS sessions and DNS cache warm across the
 * extractor, the downloader and Media3's `OkHttpDataSource`. A handful of extractor
 * requests per screen is small traffic, but thumbnail bursts amplify the handshake cost
 * if each subsystem builds its own client.
 */
object HttpClients {

    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun newBuilder(): OkHttpClient.Builder = shared.newBuilder()
}
