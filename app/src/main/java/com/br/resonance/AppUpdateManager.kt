package com.br.resonance

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/**
 * ReleaseInfo holds metadata for a GitHub release.
 */
data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val versionCode: Int,
    val name: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val apkDownloadUrl: String?
)

/**
 * Result of an update check.
 */
sealed class UpdateResult {
    data class UpdateAvailable(
        val release: ReleaseInfo,
        val currentVersionCode: Int,
        val currentVersionName: String
    ) : UpdateResult()

    data class UpToDate(
        val currentVersionCode: Int,
        val currentVersionName: String
    ) : UpdateResult()

    data class CheckFailed(
        val errorMessage: String,
        val isRateLimit: Boolean = false,
        val isOffline: Boolean = false
    ) : UpdateResult()
}

/**
 * AppUpdateManager: Simple, reliable, login-free update detection engine for Resonance.
 * Fetches latest stable release from public GitHub Releases API without requiring user accounts or Play Store.
 */
object AppUpdateManager {

    private const val PREFS_NAME = "resonance_update_prefs"
    private const val KEY_LAST_CHECK_TIME = "last_check_time"
    private const val KEY_CACHED_RELEASE_JSON = "cached_release_json"

    /**
     * Session-level dismissal flag ("Later" action).
     * Prevents recurring update popups during the same app session.
     */
    @Volatile
    var hasDismissedUpdateThisSession: Boolean = false

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Retrieves the installed app's versionCode and versionName.
     */
    fun getCurrentVersion(context: Context): Pair<Int, String> {
        return try {
            val pInfo: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }

            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                (pInfo.longVersionCode and 0x7FFFFFFF).toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
            val name = pInfo.versionName ?: "1.0.0"
            Pair(code, name)
        } catch (_: Exception) {
            Pair(1, "1.0.0")
        }
    }

    /**
     * Asynchronously checks for updates.
     * @param context Context
     * @param isManual True if initiated by the user via "Check for updates"; false on automatic startup check
     * @param onResult Callback executed on the Main thread
     */
    fun checkForUpdates(
        context: Context,
        isManual: Boolean,
        onResult: (UpdateResult) -> Unit
    ) {
        val appContext = context.applicationContext
        val (currentVersionCode, currentVersionName) = getCurrentVersion(appContext)

        // Session suppression: do not show repeatedly on startup if user already chose "Later"
        if (!isManual && hasDismissedUpdateThisSession) {
            return
        }

        // Cache evaluation for background startup checks
        if (!isManual) {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastCheck = prefs.getLong(KEY_LAST_CHECK_TIME, 0L)
            val now = System.currentTimeMillis()
            if (now - lastCheck < UpdateConfig.CACHE_EXPIRATION_MS) {
                val cachedJson = prefs.getString(KEY_CACHED_RELEASE_JSON, null)
                if (cachedJson != null) {
                    val cachedRelease = parseReleaseJson(cachedJson)
                    if (cachedRelease != null && isNewerVersion(cachedRelease, currentVersionCode, currentVersionName)) {
                        mainHandler.post {
                            onResult(UpdateResult.UpdateAvailable(cachedRelease, currentVersionCode, currentVersionName))
                        }
                        return
                    }
                }
                // Cache indicates app was recently checked and up to date; avoid spamming GitHub API
                return
            }
        }

        kotlin.concurrent.thread(name = "ResonanceUpdateCheckThread") {
            try {
                val apiUrl = UpdateConfig.getLatestReleaseApiUrl()
                val url = URL(apiUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "Resonance-Android/$currentVersionName")
                    setRequestProperty("Accept", "application/vnd.github+json")
                }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val body = reader.readText()
                    reader.close()

                    // Cache response
                    val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis())
                        .putString(KEY_CACHED_RELEASE_JSON, body)
                        .apply()

                    val release = parseReleaseJson(body)
                    if (release == null) {
                        mainHandler.post {
                            onResult(UpdateResult.CheckFailed("Could not parse release information from GitHub."))
                        }
                        return@thread
                    }

                    if (isNewerVersion(release, currentVersionCode, currentVersionName)) {
                        mainHandler.post {
                            onResult(UpdateResult.UpdateAvailable(release, currentVersionCode, currentVersionName))
                        }
                    } else {
                        mainHandler.post {
                            onResult(UpdateResult.UpToDate(currentVersionCode, currentVersionName))
                        }
                    }
                } else if (responseCode == 404) {
                    // No release published yet on this repository
                    mainHandler.post {
                        onResult(UpdateResult.UpToDate(currentVersionCode, currentVersionName))
                    }
                } else if (responseCode == 403 || responseCode == 429) {
                    mainHandler.post {
                        onResult(UpdateResult.CheckFailed("GitHub API rate limit reached. Please try again later.", isRateLimit = true))
                    }
                } else {
                    mainHandler.post {
                        onResult(UpdateResult.CheckFailed("GitHub check returned HTTP $responseCode."))
                    }
                }
            } catch (e: java.net.UnknownHostException) {
                mainHandler.post {
                    onResult(UpdateResult.CheckFailed("No internet connection.", isOffline = true))
                }
            } catch (e: java.net.SocketTimeoutException) {
                mainHandler.post {
                    onResult(UpdateResult.CheckFailed("Update check timed out. Please try again."))
                }
            } catch (e: Exception) {
                mainHandler.post {
                    onResult(UpdateResult.CheckFailed(e.localizedMessage ?: "Unknown update error."))
                }
            }
        }
    }

    /**
     * Parses the GitHub release JSON object into a [ReleaseInfo] instance.
     * Skips draft releases and prereleases.
     */
    fun parseReleaseJson(jsonStr: String): ReleaseInfo? {
        return try {
            val json = JSONObject(jsonStr)
            val isDraft = json.optBoolean("draft", false)
            val isPrerelease = json.optBoolean("prerelease", false)
            if (isDraft || isPrerelease) {
                return null
            }

            val tagName = json.optString("tag_name", "").trim()
            val name = json.optString("name", tagName).trim()
            val htmlUrl = json.optString("html_url", UpdateConfig.getWebReleasesUrl())
            val body = json.optString("body", "").trim()

            // Find APK asset URL if present
            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i)
                    val assetName = asset?.optString("name", "") ?: ""
                    if (assetName.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url").ifBlank { null }
                        break
                    }
                }
            }

            val cleanVersionName = tagName.removePrefix("v").removePrefix("V").trim()
            val parsedCode = extractVersionCode(body)

            ReleaseInfo(
                tagName = tagName,
                versionName = if (cleanVersionName.isNotEmpty()) cleanVersionName else name,
                versionCode = parsedCode,
                name = name.ifEmpty { "Resonance $tagName" },
                releaseNotes = body,
                htmlUrl = htmlUrl,
                apkDownloadUrl = apkUrl
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts an explicit versionCode from release notes body if published (e.g. "versionCode: 2").
     */
    private fun extractVersionCode(notes: String): Int {
        val pattern = Pattern.compile("(?i)(?:versioncode|version_code|build)\\s*[:=]\\s*(\\d+)")
        val matcher = pattern.matcher(notes)
        if (matcher.find()) {
            return matcher.group(1)?.toIntOrNull() ?: 0
        }
        return 0
    }

    /**
     * Compares the remote release against the installed app version.
     * Returns true if remote release is newer.
     */
    fun isNewerVersion(
        release: ReleaseInfo,
        currentVersionCode: Int,
        currentVersionName: String
    ): Boolean {
        // 1. Explicit versionCode comparison if available
        if (release.versionCode > 0 && currentVersionCode > 0) {
            if (release.versionCode > currentVersionCode) return true
            if (release.versionCode < currentVersionCode) return false
        }

        // 2. Semantic version comparison fallback
        return compareSemanticVersions(release.versionName, currentVersionName) > 0
    }

    /**
     * Semantic version comparator (e.g. "1.0.1" > "1.0.0").
     * Returns > 0 if v1 > v2, 0 if equal, < 0 if v1 < v2.
     */
    fun compareSemanticVersions(v1: String, v2: String): Int {
        val clean1 = v1.removePrefix("v").removePrefix("V")
        val clean2 = v2.removePrefix("v").removePrefix("V")

        val parts1 = clean1.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
        val parts2 = clean2.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) {
                return p1.compareTo(p2)
            }
        }
        return 0
    }
}
