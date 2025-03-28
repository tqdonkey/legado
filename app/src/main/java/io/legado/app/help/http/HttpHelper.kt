package io.legado.app.help.http

import io.legado.app.constant.AppConst
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.cronet.CronetInterceptor
import io.legado.app.help.http.cronet.CronetLoader
import okhttp3.*
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val proxyClientCache: ConcurrentHashMap<String, OkHttpClient> by lazy {
    ConcurrentHashMap()
}

val cookieJar by lazy {
    object : CookieJar {

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return emptyList()
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach {
                CookieStore.replaceCookie(url.toString(), "${it.name}=${it.value}")
            }
        }

    }
}

val okHttpClient: OkHttpClient by lazy {
    val specs = arrayListOf(
        ConnectionSpec.MODERN_TLS,
        ConnectionSpec.COMPATIBLE_TLS,
        ConnectionSpec.CLEARTEXT
    )

    val builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .cookieJar(cookieJar = cookieJar)
        .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory, SSLHelper.unsafeTrustManager)
        .retryOnConnectionFailure(true)
        .hostnameVerifier(SSLHelper.unsafeHostnameVerifier)
        .connectionSpecs(specs)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder()
            if (request.header(AppConst.UA_NAME) == null) {
                builder.addHeader(AppConst.UA_NAME, AppConfig.userAgent)
            }
            builder.addHeader("Keep-Alive", "300")
            builder.addHeader("Connection", "Keep-Alive")
            builder.addHeader("Cache-Control", "no-cache")
            chain.proceed(builder.build())
        })
    if (!AppConfig.isGooglePlay && AppConfig.isCronet && CronetLoader.install()) {
        builder.addInterceptor(CronetInterceptor(cookieJar = cookieJar))
    }
    builder.build()
}

/**
 * 缓存代理okHttp
 */
fun getProxyClient(proxy: String? = null): OkHttpClient {
    if (proxy.isNullOrBlank()) {
        return okHttpClient
    }
    proxyClientCache[proxy]?.let {
        return it
    }
    val r = Regex("(http|socks4|socks5)://(.*):(\\d{2,5})(@.*@.*)?")
    val ms = r.findAll(proxy)
    val group = ms.first()
    var username = ""       //代理服务器验证用户名
    var password = ""       //代理服务器验证密码
    val type = if (group.groupValues[1] == "http") "http" else "socks"
    val host = group.groupValues[2]
    val port = group.groupValues[3].toInt()
    if (group.groupValues[4] != "") {
        username = group.groupValues[4].split("@")[1]
        password = group.groupValues[4].split("@")[2]
    }
    if (type != "direct" && host != "") {
        val builder = okHttpClient.newBuilder()
        if (type == "http") {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
        } else {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port)))
        }
        if (username != "" && password != "") {
            builder.proxyAuthenticator { _, response -> //设置代理服务器账号密码
                val credential: String = Credentials.basic(username, password)
                response.request.newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build()
            }
        }
        val proxyClient = builder.build()
        proxyClientCache[proxy] = proxyClient
        return proxyClient
    }
    return okHttpClient
}