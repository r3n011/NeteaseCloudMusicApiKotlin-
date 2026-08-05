@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package net.moriafly.ncm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.net.ssl.HttpsURLConnection

/**
 * 请求引擎：对应 util/request.js 的 100 行主流程
 *
 * 支持：
 *  - 3 种加密路由: weapi / eapi / linuxapi（调用 NcmCrypto）
 *  - os=pc / ios / android 的不同 UA、appver、Host
 *  - MUSIC_U / __csrf / MUSIC_A / os 自动拼 cookie
 *  - realIp 注入（X-Real-IP / X-Forwarded-For，对应 option.js realIp）
 *  - proxy (http://127.0.0.1:7890) 简易 HTTP 代理
 *  - 301/302 手动跟随，且把 Set-Cookie 全部合并到 NcmSession
 *  - /api/song/url 返回 302 Location 时直接作为 data[0].url 返回
 *
 * 依赖：JDK HttpURLConnection（不需要 OkHttp / Retrofit）
 */
object NcmRequest {

    // ============================================================
    // 常量（对应 util/request.js 顶部 UA / appver / Host）
    // ============================================================

    enum class OS(val ua: String, val appver: String) {
        PC(
            ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            appver = "3.51.1",
        ),
        ANDROID(
            ua = "NeteaseMusic/9.1.99.999420(640903650);Dalvik/2.1.0 (Linux; U; Android 14; Pixel 9 Pro XL Build/UQ1A.240205.002)",
            appver = "9.1.99",
        ),
        IOS(
            ua = "NeteaseMusicIM/1.0.0 (iPhone; iOS 17.5; Scale/3.00)",
            appver = "11.1.60",
        ),
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** weapi 接口基础地址（官方 PC Host） */
    const val WY_YX_BASE_URL = "https://music.163.com"
    const val WY_INTERFACE_BASE_URL = "https://interface3.music.163.com"
    const val WY_INTERFACE_EAPI_BASE_URL = "https://interface3.music.163.com"
    const val WY_LINUXAPI_BASE_URL = "https://music.163.com"

    // ============================================================
    // 公开 API —— 3 种加密路由
    // ============================================================

    /**
     * weapi POST（对应 `crypto: weapi` 的所有 module）。
     * 结果返回统一格式 { code, message?, data } 的 Map。
     */
    suspend fun weapi(
        path: String,
        params: Map<String, Any?>,
        realIp: String? = null,
        url: String? = null,
    ): Result<NcmResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val sess = NcmSession.INSTANCE
            val osValue = sess?.os?.takeIf { it.isNotBlank() } ?: "pc"
            val osEnum = when (osValue.lowercase()) {
                "android" -> OS.ANDROID
                "ios" -> OS.IOS
                else -> OS.PC
            }

            // 1. 拼入 __csrf / e_r / eparams（对应 util/request.js 开头 if(option.cookie) 逻辑）
            val csrf = sess?.cookies?.get("__csrf").orEmpty()
            val enriched = LinkedHashMap(params)
            if (csrf.isNotBlank()) enriched["csrf_token"] = csrf

            // 2. 加密
            val encrypted = NcmCrypto.weapi(enriched)

            // 3. 选择目标 Host（官方 weapi 走 music.163.com，可选自定义 url 参数）
            //    对齐原版 util/request.js：'/api/xxx' → 'https://music.163.com/weapi/xxx'
            val target = url ?: buildWeapiUrl(path)

            // 4. POST
            val body = encrypted.toFormBody().toByteArray(Charsets.UTF_8)
            val headers = buildHeaders(
                host = URL(target).host,
                referer = WY_YX_BASE_URL + "/",
                osEnum = osEnum,
                session = sess,
                extraCookie = "os=$osValue",
                realIp = realIp ?: sess?.realIp,
                contentType = "application/x-www-form-urlencoded",
                accept = "*/*",
            )

            val raw = http("POST", target, headers, body, sess?.proxy)
            handleRawResponse(raw, path, sess, isEapi = false)
        }
    }

    /**
     * eapi POST（对应 `crypto: eapi` 的 module）
     */
    suspend fun eapi(
        path: String,
        params: Map<String, Any?>,
        realIp: String? = null,
        url: String? = null,
    ): Result<NcmResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val sess = NcmSession.INSTANCE
            val osEnum = OS.ANDROID
            // 对齐原版 util/request.js：'/api/xxx' → 'https://interface3.music.163.com/eapi/xxx'
            // 注意：加密摘要仍使用原始 '/api/xxx' 路径（NcmCrypto.eapi 的第一个参数）
            val fullUrl = url ?: buildEapiUrl(path)

            val encrypted = NcmCrypto.eapi(path, params)
            val body = encrypted.toFormBody().toByteArray(Charsets.UTF_8)
            val headers = buildHeaders(
                host = URL(fullUrl).host,
                referer = null,
                osEnum = osEnum,
                session = sess,
                realIp = realIp ?: sess?.realIp,
                contentType = "application/x-www-form-urlencoded",
                accept = "*/*",
            )
            val raw = http("POST", fullUrl, headers, body, sess?.proxy)
            handleRawResponse(raw, path, sess, isEapi = true)
        }
    }

    /**
     * linuxapi POST（对应 `crypto: linuxapi` 的 module）
     */
    suspend fun linuxapi(
        path: String,
        params: Map<String, Any?>,
        url: String? = null,
    ): Result<NcmResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val sess = NcmSession.INSTANCE
            val target = url ?: (WY_LINUXAPI_BASE_URL + path)
            val encrypted = NcmCrypto.linuxapi(params)
            val body = encrypted.toFormBody().toByteArray(Charsets.UTF_8)
            val headers = buildHeaders(
                host = URL(target).host,
                referer = null,
                osEnum = OS.PC,
                session = sess,
                contentType = "application/x-www-form-urlencoded",
                accept = "*/*",
            )
            val raw = http("POST", target, headers, body, sess?.proxy)
            handleRawResponse(raw, path, sess, isEapi = false)
        }
    }

    // ============================================================
    // 内部：响应处理
    // ============================================================

    internal data class Raw(
        val code: Int,
        val bodyBytes: ByteArray,
        val location: String?,
        val setCookies: List<String>,
    )

    private fun handleRawResponse(
        raw: Raw,
        path: String,
        sess: NcmSession?,
        isEapi: Boolean,
    ): NcmResponse {
        // 1) Set-Cookie 合并进 session
        if (raw.setCookies.isNotEmpty()) sess?.merge(raw.setCookies)

        // 2) 301/302 — 两种处理：
        //    - /api/song/enhance/player/url 系列 → 把 Location 当 data[0].url
        //    - 其他情况 → 手动跟随
        if (raw.code in 300..399) {
            val loc = raw.location
                ?: return NcmResponse(raw.code, emptyMap(), "3xx 无 Location")

            // 特殊：/song/url / /song/enhance/player/url 系列，返回 302 -> data[0].url = loc
            if (path.contains("/song/url") || path.contains("player/url")) {
                val fake = mapOf(
                    "code" to raw.code,
                    "data" to listOf(mapOf("url" to loc))
                )
                return NcmResponse(raw.code, fake, null)
            }

            // 跟随一次
            Timber.d("3xx follow: $path -> $loc")
            val followHeaders = buildHeaders(
                host = URL(loc).host,
                referer = null,
                osEnum = OS.PC,
                session = sess,
                accept = "*/*",
            )
            val raw2 = http("GET", loc, followHeaders, null, sess?.proxy)
            if (raw2.setCookies.isNotEmpty()) sess?.merge(raw2.setCookies)
            return parseBody(raw2.code, raw2.bodyBytes, path, isEapi = false)
        }

        return parseBody(raw.code, raw.bodyBytes, path, isEapi)
    }

    private fun parseBody(code: Int, bodyBytes: ByteArray, path: String, isEapi: Boolean): NcmResponse {
        val body = try {
            String(decompressGzipIfNeeded(bodyBytes), Charsets.UTF_8)
        } catch (t: Throwable) {
            String(bodyBytes, Charsets.ISO_8859_1)
        }
        val parsed: Any? = when {
            body.isBlank() -> null
            isEapi -> runCatching { NcmCrypto.eapiResDecrypt(body) }.getOrNull()
                ?: runCatching { NcmJson.parseAny(body) }.getOrNull()
            else -> runCatching { NcmJson.parseAny(body) }.getOrNull()
        }
        val map = parsed as? Map<String, Any?> ?: emptyMap()
        val respCode = map["code"] as? Int ?: code
        val msg = when {
            map.contains("message") -> map["message"]?.toString()
            map.contains("msg") -> map["msg"]?.toString()
            else -> null
        }
        Timber.d("NCM $path -> code=$respCode bodyLen=${body.length}")
        Timber.d("NCM $path -> body=${body.take(1200)}")
        return NcmResponse(respCode, map, msg)
    }

    private fun decompressGzipIfNeeded(data: ByteArray): ByteArray {
        if (data.size < 2) return data
        // gzip (1F 8B)
        if (data[0] == 0x1F.toByte() && data[1] == 0x8B.toByte()) {
            return GZIPInputStream(data.inputStream()).use { it.readBytesCompat() }
        }
        // zlib/deflate (0x78 xx)
        if (data[0] == 0x78.toByte()) {
            return try {
                InflaterInputStream(data.inputStream()).use { it.readBytesCompat() }
            } catch (t: Throwable) {
                data
            }
        }
        return data
    }

    private fun InputStream.readBytesCompat(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var n: Int
        while (true) {
            n = this.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ============================================================
    // URL 构建（对齐原版 util/request.js 的路径转换）
    //   weapi: '/api/xxx' → 'https://music.163.com/weapi/xxx'
    //   eapi : '/api/xxx' → 'https://interface3.music.163.com/eapi/xxx'
    // ============================================================

    private fun buildWeapiUrl(path: String): String = when {
        path.startsWith("/api/") -> WY_YX_BASE_URL + "/weapi/" + path.removePrefix("/api/")
        path.startsWith("/weapi/") || path.startsWith("/eapi/") -> WY_YX_BASE_URL + path
        else -> WY_YX_BASE_URL + path
    }

    private fun buildEapiUrl(path: String): String = when {
        path.startsWith("/api/") -> WY_INTERFACE_EAPI_BASE_URL + "/eapi/" + path.removePrefix("/api/")
        path.startsWith("/eapi/") -> WY_INTERFACE_EAPI_BASE_URL + path
        else -> WY_INTERFACE_EAPI_BASE_URL + path
    }

    // ============================================================
    // Header 构建
    // ============================================================

    @Suppress("LongParameterList")
    private fun buildHeaders(
        host: String,
        referer: String?,
        osEnum: OS,
        session: NcmSession?,
        extraCookie: String? = null,
        realIp: String? = null,
        contentType: String? = null,
        accept: String? = null,
    ): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["Host"] = host
        headers["User-Agent"] = osEnum.ua
        if (accept != null) headers["Accept"] = accept
        if (contentType != null) headers["Content-Type"] = contentType
        headers["Accept-Encoding"] = "gzip"
        headers["Accept-Language"] = "zh-CN,zh;q=0.9,en;q=0.8"
        if (referer != null) headers["Referer"] = referer
        headers["Connection"] = "keep-alive"

        // X-Real-IP / X-Forwarded-For（部分接口对 IP 地理位有要求）
        if (realIp != null) {
            headers["X-Real-IP"] = realIp
            headers["X-Forwarded-For"] = realIp
        }

        // Cookie
        val cookieSb = StringBuilder()
        session?.toCookieHeader()?.takeIf { it.isNotBlank() }?.let {
            cookieSb.append(it)
        }
        if (extraCookie != null) {
            if (cookieSb.isNotEmpty()) cookieSb.append("; ")
            cookieSb.append(extraCookie)
        }
        // eapi 附加 appver
        if (osEnum == OS.ANDROID) {
            if (cookieSb.isNotEmpty()) cookieSb.append("; ")
            cookieSb.append("appver=${osEnum.appver}; versioncode=${osEnum.appver.replace(".", "")}")
        }
        if (cookieSb.isNotBlank()) headers["Cookie"] = cookieSb.toString()

        return headers
    }

    // ============================================================
    // 底层 HTTP
    // ============================================================

    private fun http(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        proxy: String?,
    ): Raw {
        val u = URL(url)
        val conn = if (proxy.isNullOrBlank()) u.openConnection() else u.openConnection(parseProxy(proxy))

        conn as HttpURLConnection
        if (conn is HttpsURLConnection) {
            // 保持默认系统 TLS，不走自定义
        }
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = false    // 手动处理 3xx
        conn.doInput = true
        conn.doOutput = body != null
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        if (body != null) {
            conn.outputStream.use { it.write(body) }
        }

        return try {
            val code = conn.responseCode
            val setCookies = extractSetCookies(conn)
            val loc = conn.getHeaderField("Location")
            val stream = runCatching {
                if (code in 200..299) conn.inputStream else conn.errorStream
            }.getOrNull()
            val bytes = stream?.use { it.readBytesCompat() } ?: ByteArray(0)
            Raw(code, bytes, loc, setCookies)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun extractSetCookies(conn: HttpURLConnection): List<String> {
        val out = ArrayList<String>(2)
        var i = 0
        while (true) {
            val k = conn.getHeaderFieldKey(i) ?: break
            if (k.equals("Set-Cookie", ignoreCase = true)) {
                conn.getHeaderField(i)?.let(out::add)
            }
            i++
        }
        return out
    }

    private fun parseProxy(s: String): Proxy {
        val noScheme = s.removePrefix("http://").removePrefix("https://").removeSuffix("/")
        val parts = noScheme.split(':', limit = 2)
        val host = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 80
        return Proxy(Proxy.Type.HTTP, java.net.InetSocketAddress.createUnresolved(host, port))
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())
}

/**
 * NCM 响应通用结构
 *
 * @property code HTTP 层 / body code 合并后的最终 code（200 一般为成功）
 * @property body 解析后的 JSON Map；当 /api/song/url 302 时返回合成对象 { code, data:[{url}] }
 * @property message 错误信息（如果有）
 */
data class NcmResponse(
    val code: Int,
    val body: Map<String, Any?>,
    val message: String?,
) {
    val isSuccess: Boolean get() = code == 200

    inline fun <T> map(block: (Map<String, Any?>) -> T): Result<T> =
        if (isSuccess) runCatching { block(body) }
        else Result.failure(IllegalStateException("NCM code=$code msg=$message"))
}
