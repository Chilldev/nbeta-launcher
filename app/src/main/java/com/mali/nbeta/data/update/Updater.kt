package com.mali.nbeta.data.update

import com.mali.nbeta.system.DiagLog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Immutable
import com.mali.nbeta.BuildConfig
import com.mali.nbeta.system.UpdateReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

@Immutable
data class Release(val versionName: String, val versionCode: Int, val apkUrl: String, val notes: String?, val pageUrl: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Updates from GitHub Releases. A release carries an asset named `nbeta-<versionCode>.apk`; the APK is only handed
 * to the system installer if it's signed with the same certificate as the running app.
 */
class Updater(private val context: Context, httpProvider: () -> OkHttpClient) {
    private val http by lazy(httpProvider)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private val prefs get() = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    val lastCheck: Long get() = prefs.getLong("last_check", 0)

    fun canInstall() = context.packageManager.canRequestPackageInstalls()

    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            val release = http.newCall(req).execute().use { r ->
                if (r.code == 404) return@use null
                if (!r.isSuccessful) throw IllegalStateException("GitHub HTTP ${r.code}")
                parse(r.body.string())
            }
            prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
            val newer = release?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
            _state.value = if (newer != null) UpdateState.Available(newer) else UpdateState.UpToDate
            newer
        } catch (e: Exception) {
            DiagLog.w(TAG, "Update check failed", e)
            _state.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName)
            null
        }
    }

    suspend fun install(release: Release) = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val apk = File(dir, "nbeta-${release.versionCode}.apk")
            _state.value = UpdateState.Downloading(release, 0f)
            http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("Download failed (HTTP ${r.code})")
                val total = r.body.contentLength().takeIf { it > 0 } ?: -1L
                r.body.byteStream().use { input ->
                    apk.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) _state.value = UpdateState.Downloading(release, done.toFloat() / total)
                        }
                    }
                }
            }
            if (!sameSigner(apk)) throw SecurityException("The downloaded APK isn't signed with Nbeta's key")
            _state.value = UpdateState.Installing(release)
            commit(apk)
        } catch (e: Exception) {
            DiagLog.w(TAG, "Update install failed", e)
            _state.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun failed(message: String) {
        _state.value = UpdateState.Failed(message)
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val intent = Intent(context, UpdateReceiver::class.java)
            val pi = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pi.intentSender)
        }
    }

    private fun signers(info: PackageInfo?): Set<String> {
        val si = info?.signingInfo ?: return emptySet()
        val certs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        return certs.orEmpty().map { c -> MessageDigest.getInstance("SHA-256").digest(c.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private fun sameSigner(apk: File): Boolean {
        val pm = context.packageManager
        val mine = signers(pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES))
        val theirs = signers(pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES))
        return mine.isNotEmpty() && theirs.isNotEmpty() && mine.intersect(theirs).isNotEmpty()
    }

    companion object {
        private const val TAG = "Updater"
        private val assetName = Regex("^nbeta-(\\d+)\\.apk$")

        /** GitHub "latest release" JSON -> Release (pure; unit-tested). */
        fun parse(json: String): Release? {
            val o = Json.parseToJsonElement(json).jsonObject
            fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val apk = assets.firstNotNullOfOrNull { a ->
                val name = a.str("name") ?: return@firstNotNullOfOrNull null
                val code = assetName.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: return@firstNotNullOfOrNull null
                code to (a.str("browser_download_url") ?: return@firstNotNullOfOrNull null)
            } ?: return null
            return Release(
                versionName = o.str("tag_name")?.removePrefix("v") ?: apk.first.toString(),
                versionCode = apk.first,
                apkUrl = apk.second,
                notes = o.str("body")?.takeIf { it.isNotBlank() },
                pageUrl = o.str("html_url") ?: "https://github.com/${BuildConfig.UPDATE_REPO}/releases",
            )
        }
    }
}
