@file:Suppress("unused", "MemberVisibilityCanBePrivate", "SpellCheckingInspection", "TooManyFunctions")

package net.moriafly.ncm

import android.app.Application
import android.content.Context
import timber.log.Timber

/**
 * 网易云音乐本地 SDK 门面（单例）—— Android App 唯一调用入口
 *
 * ✅ 0 外部依赖：
 *    - javax.crypto（AES/RSA）  →  JDK 自带
 *    - java.net.HttpURLConnection  →  JDK 自带
 *    - timber.log.Timber         →  大多数项目已有；没有的话把 Timber 调用删/换为 android.util.Log
 *    - SharedPreferences          →  Android SDK
 *
 * 🚀 接入方式（3 行）：
 * ```kotlin
 * // 1. 在 Application.onCreate：
 * NcmApi.install(this)
 *
 * // 2. 登录后即可调：
 * lifecycleScope.launch {
 *     val r = NcmApi.search("周杰伦", limit = 10).getOrThrow()
 *     val songs = (r["result"] as? Map<*, *>)?.get("songs") as? List<*> ?: return@launch
 * }
 * ```
 */
object NcmApi {

    // ============================================================
    // 初始化
    // ============================================================

    /**
     * App 启动时调用一次（建议在 Application.onCreate）
     * @param appContext  Application context
     * @param os          加密平台："pc"（默认）/ "android" / "ios" —— 决定 UA + appver
     * @param realIp      可选：注入 X-Real-IP（对海外 IP 限制的场景有用）
     * @param proxy       可选：HTTP 代理 "http://127.0.0.1:7890"
     */
    fun install(
        appContext: Context,
        os: String = "pc",
        realIp: String? = null,
        proxy: String? = null,
    ) {
        val ctx = appContext.applicationContext
        val session = NcmSession.install(ctx)
        session.os = os
        session.realIp = realIp
        session.proxy = proxy
        if (installed) return
        installed = true
        Timber.d("[NcmApi] installed os=$os realIp=$realIp proxy=$proxy")
    }

    /**
     * Java 友好的 Application.onCreate 快捷封装
     */
    fun install(app: Application) = install(app.applicationContext)

    @Volatile
    private var installed: Boolean = false

    private fun ensureInstalled() {
        if (!installed && NcmSession.INSTANCE == null) {
            error("NcmApi 未安装！请先在 Application.onCreate 调用 NcmApi.install(this)")
        }
    }

    val isLogin: Boolean get() = NcmSession.INSTANCE?.isLogin == true
    val userId: Long? get() = NcmSession.INSTANCE?.userId

    /** 运行期切换 os（不同接口对 os=pc 比较友好） */
    fun setOs(os: String) {
        NcmSession.INSTANCE?.os = os
    }

    fun setRealIp(ip: String?) { NcmSession.INSTANCE?.realIp = ip }
    fun setProxy(proxy: String?) { NcmSession.INSTANCE?.proxy = proxy }

    // ============================================================
    // 搜索
    // ============================================================

    suspend fun searchDefault() = runSafely("searchDefault") {
        ensureInstalled()
        NcmModules.searchDefault()
    }

    /**
     * @param type 1=单曲 / 10=专辑 / 100=歌手 / 1000=歌单 / 1004=MV / 1009=电台 / 1014=视频 / 1018=综合
     */
    suspend fun search(
        keyword: String,
        type: Int = 1,
        limit: Int = 30,
        offset: Int = 0,
    ) = runSafely("search") {
        ensureInstalled()
        NcmModules.search(keyword, type, limit, offset)
    }

    // ============================================================
    // 歌曲播放
    // ============================================================

    /**
     * @param level standard / higher / exhigh / lossless / hires / jyeffect / jymaster / sky
     * @param encodeType flac / mp3 / aac
     */
    suspend fun songUrlV1(
        ids: List<String>,
        level: String = "standard",
        encodeType: String = "flac",
    ) = runSafely("songUrlV1") {
        ensureInstalled()
        NcmModules.songUrlV1(ids, level, encodeType)
    }

    suspend fun songUrl(id: String, br: Int = 320_000) = songUrlV1(listOf(id),
        level = when (br) {
            in 0..128_000 -> "standard"
            in 128_001..192_000 -> "higher"
            in 192_001..320_000 -> "exhigh"
            in 320_001..1_411_000 -> "lossless"
            else -> "hires"
        },
        encodeType = if (br > 1_500_000) "flac" else "mp3",
    )

    /** 判断歌曲是否可播放（VIP 灰歌、下架） */
    suspend fun checkMusicPlayable(id: String, br: Int = 320_000) = runSafely("checkMusic") {
        ensureInstalled()
        NcmModules.checkMusicPlayable(id, br)
    }

    suspend fun songDetail(ids: List<String>) = runSafely("songDetail") {
        ensureInstalled()
        NcmModules.songDetail(ids)
    }

    // ============================================================
    // 登录（手机号/验证码/密码）
    // ============================================================

    /** 发送短信验证码 */
    suspend fun sentSmsCaptcha(phone: String, ctcode: String = "86") = runSafely("sentSmsCaptcha") {
        ensureInstalled()
        NcmModules.sentSmsCaptcha(phone, ctcode)
    }

    /** 校验短信验证码 */
    suspend fun captchaVerify(phone: String, captcha: String, ctcode: String = "86") =
        runSafely("captchaVerify") {
            ensureInstalled()
            NcmModules.captchaVerify(phone, captcha, ctcode)
        }

    /** 手机号登录：captcha 或 password 二选一 */
    suspend fun loginCellphone(
        phone: String,
        captcha: String? = null,
        password: String? = null,
        ctcode: String = "86",
        countrycode: String? = null,
    ) = runSafely("loginCellphone") {
        ensureInstalled()
        NcmModules.loginCellphone(phone, captcha, password, ctcode, countrycode)
    }

    /** cookie 续期 / 登录态检查（App 启动时调用一次，若返回 200 说明登录有效） */
    suspend fun loginRefresh() = runSafely("loginRefresh") {
        ensureInstalled()
        NcmModules.loginRefresh()
    }

    suspend fun logout() = runSafely("logout") {
        ensureInstalled()
        NcmModules.logout()
    }

    // ============================================================
    // 二维码登录（推荐用这个 —— 不需要账号密码 / 短信）
    // ============================================================

    data class QrLoginResult(
        val key: String,
        /** Base64 PNG 图片，直接 setImageBitmap(BitmapFactory.decodeByteArray) */
        val qrPngBase64: String,
    )

    /** Step 1 + 2：一步到位返回 key + Base64 PNG 二维码图 */
    suspend fun qrLoginPrepare(): Result<QrLoginResult> = runSafely("qrLoginPrepare") {
        ensureInstalled()
        val k = NcmModules.qrLoginKey().getOrThrow()
        val img = NcmModules.qrLoginImage(k.key).getOrThrow()
        Result.success(QrLoginResult(k.key, img))
    }

    /**
     * Step 3：轮询扫码状态（120s 超时）
     * @param onStatus code: 801=等待扫码 / 802=已扫未确认 / 803=登录成功 / 800=过期
     */
    suspend fun qrLoginAwait(
        key: String,
        onStatus: suspend (code: Int, raw: Map<String, Any?>) -> Unit = { _, _ -> },
    ) = runSafely("qrLoginAwait") {
        ensureInstalled()
        NcmModules.qrLoginAwait(key, onStatus)
    }

    // ============================================================
    // 首页 / 歌单 / 用户
    // ============================================================

    suspend fun banner(type: Int = 1) = runSafely("banner") {
        ensureInstalled()
        NcmModules.banner(type)
    }

    suspend fun playlistDetail(id: String) = runSafely("playlistDetail") {
        ensureInstalled()
        NcmModules.playlistDetail(id)
    }

    suspend fun userAccount() = runSafely("userAccount") {
        ensureInstalled()
        NcmModules.userAccount()
    }

    // ============================================================
    // 兜底异常包装（把 Kotlin 异常统一包成 Result.failure，便于上层统一 .getOrThrow / .onFailure）
    // ============================================================

    private inline fun <T> runSafely(
        tag: String,
        crossinline block: suspend () -> Result<T>,
    ): Result<T> = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
        try {
            block()
        } catch (t: Throwable) {
            Timber.w(t, "[NcmApi.$tag] failed")
            Result.failure(t as? Exception ?: Exception(t.message ?: "unknown", t))
        }
    }

    // ============================================================
    // Session 管理（调试/调试期有用）
    // ============================================================

    fun dumpSession(): String {
        val sess = NcmSession.INSTANCE ?: return "(not installed)"
        val c = sess.cookies
        val keys = c.keys.joinToString()
        return "os=${sess.os}, realIp=${sess.realIp}, proxy=${sess.proxy}, isLogin=${sess.isLogin}, uid=${sess.userId}, cookies=[$keys]"
    }
}
