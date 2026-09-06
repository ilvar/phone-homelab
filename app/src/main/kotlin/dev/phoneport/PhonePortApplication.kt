package dev.phoneport

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.phoneport.core.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@HiltAndroidApp
class PhonePortApplication : Application(), ImageLoaderFactory {
    @Inject lateinit var resources: ResourceNetwork
    override fun newImageLoader() = ImageLoader.Builder(this).okHttpClient(resources.client).build()
}
/** Public resources never share the authenticated or opt-in insecure Portainer client. */
@Singleton class ResourceNetwork @Inject constructor() {
    @Volatile var sources: List<CatalogSource> = defaultSources
    fun allowed(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (!parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        return sources.any { it.url.toHttpUrlOrNull()?.let { source -> source.host == parsed.host && source.port == parsed.port } == true }
    }
    val client: OkHttpClient = Network.publicClient().newBuilder().addInterceptor { chain ->
        val url = chain.request().url
        val catalog = sources.any { it.url.toHttpUrlOrNull() == url }
        if ((!catalog && !allowed(url.toString())) || (!url.isHttps && !privateHost(url.host))) throw IOException("This resource host is outside your catalog allowlist")
        chain.proceed(chain.request())
    }.build()
}
