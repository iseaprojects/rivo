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

    /**
     * The same client without the whole-call deadline, for transfers that are expected to
     * take minutes.
     *
     * `callTimeout` bounds one call end to end, response body included: a 400 MB video over
     * a slow link fails 45 seconds in, and the retry budget spends itself on the same wall.
     * Per-read and per-connect timeouts stay, so a server that stops sending still fails
     * instead of hanging. The connection pool and TLS sessions are shared with [shared].
     */
    val transfers: OkHttpClient by lazy {
        shared.newBuilder().callTimeout(0, TimeUnit.SECONDS).build()
    }
}
