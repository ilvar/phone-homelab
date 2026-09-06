package dev.phoneport.core

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

fun jsonBody(value: JsonElement) = value.toString().toRequestBody("application/json".toMediaType())
fun privateHost(host: String): Boolean {
    if (host == "localhost" || host == "::1") return true
    val bytes = host.split('.').map { it.toIntOrNull() ?: return false }
    if (bytes.size != 4 || bytes.any { it !in 0..255 }) return false
    return bytes[0] == 127 || bytes[0] == 10 || (bytes[0] == 172 && bytes[1] in 16..31) || (bytes[0] == 192 && bytes[1] == 168)
}
fun normalizeBaseUrl(raw: String): HttpUrl {
    val url = (raw.trim().trimEnd('/') + "/").toHttpUrl()
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "URL must not contain credentials, query or fragment" }
    require(url.isHttps || privateHost(url.host)) { "HTTP is allowed only for localhost and private IPv4 addresses" }
    return url
}
interface Credentials {
    var apiKey: String
    var jwt: String
    var username: String
    var password: String
}
object Network {
    fun publicClient() = OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    fun portainer(base: HttpUrl, trustSelfSigned: Boolean, credentials: Credentials): OkHttpClient {
        val builder = publicClient().newBuilder().retryOnConnectionFailure(false)
        if (trustSelfSigned) {
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            builder.sslSocketFactory(ssl.socketFactory, trust).hostnameVerifier { _, _ -> true }
        }
        builder.addInterceptor { chain ->
            val request = chain.request()
            if (request.url.scheme != base.scheme || request.url.host != base.host || request.url.port != base.port)
                throw IOException("Blocked request outside configured Portainer origin")
            val next = request.newBuilder()
            if (!request.url.encodedPath.endsWith("/api/auth")) {
                if (credentials.apiKey.isNotBlank()) next.header("X-API-Key", credentials.apiKey)
                else if (credentials.jwt.isNotBlank()) next.header("Authorization", "Bearer ${credentials.jwt}")
            }
            chain.proceed(next.build())
        }
        // Separate client avoids recursive authentication. Same origin policy and TLS choice.
        val authClient = builder.build()
        val lock = Any()
        builder.authenticator { _, response ->
            if (response.priorResponse != null || credentials.apiKey.isNotBlank() || credentials.username.isBlank() || credentials.password.isBlank()) null
            else synchronized(lock) {
                val old = response.request.header("Authorization")
                if (old == "Bearer ${credentials.jwt}" || credentials.jwt.isBlank()) {
                    val body = buildJsonObject { put("username", credentials.username); put("password", credentials.password) }
                    val token = authClient.newCall(Request.Builder().url(base.resolve("api/auth")!!).post(jsonBody(body)).build()).execute().use { r ->
                        if (r.isSuccessful) runCatching { (catalogJson.parseToJsonElement(r.body!!.string()) as JsonObject).str("jwt") }.getOrNull() else null
                    }
                    if (token.isNullOrBlank()) return@synchronized null
                    credentials.jwt = token
                }
                response.request.newBuilder().header("Authorization", "Bearer ${credentials.jwt}").build()
            }
        }
        return builder.build()
    }
}
class PortainerException(message: String) : IOException(message)
fun retrofit2.Response<ResponseBody>.requireBody(): ResponseBody {
    if (isSuccessful) return body() ?: "".toResponseBody()
    val raw = errorBody()?.string().orEmpty()
    val message = runCatching { (catalogJson.parseToJsonElement(raw) as JsonObject).str("message").ifBlank { raw } }.getOrDefault(raw)
    throw PortainerException(message.ifBlank { "HTTP ${code()}" })
}
