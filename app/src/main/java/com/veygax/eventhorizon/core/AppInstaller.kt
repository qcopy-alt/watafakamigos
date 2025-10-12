package com.veygax.eventhorizon.core

import android.content.Context
import android.net.Uri
import com.veygax.eventhorizon.utils.RootUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL

object AppInstaller {

    suspend fun downloadAndInstall(
        context: Context,
        owner: String,
        repo: String,
        onStatusUpdate: (String) -> Unit
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Step 1: Find the latest APK download URL from GitHub API
                onStatusUpdate("Finding latest release...")
                val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"
                val jsonText = URL(apiUrl).readText()
                val json = JSONObject(jsonText)
                val assets = json.getJSONArray("assets")
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val fileName = asset.getString("name")
                    // Added "-signed" to prioritize the correct Shizuku APK
                    if (fileName.endsWith(".apk") && fileName.contains("-signed")) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }

                // Fallback for releases without a "-signed" apk
                if (apkUrl == null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.getString("name").endsWith(".apk")) {
                            apkUrl = asset.getString("browser_download_url")
                            break
                        }
                    }
                }

                if (apkUrl == null) {
                    onStatusUpdate("Error: No APK found in the latest release.")
                    return@withContext false
                }

                // Step 2: Download the APK
                onStatusUpdate("Downloading $repo...")
                val apkFile = File(context.cacheDir, "$repo.apk")
                URL(apkUrl).openStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // Step 3: Install the APK using root
                onStatusUpdate("Installing...")
                val result = RootUtils.runAsRoot("pm install -r \"${apkFile.absolutePath}\"")

                // Step 4: Cleanup
                apkFile.delete()

                if (result.contains("Success")) {
                    onStatusUpdate("$repo installed successfully!")
                    return@withContext true
                } else {
                    onStatusUpdate("Installation failed.")
                    return@withContext false
                }

            } catch (e: Exception) {
                onStatusUpdate("An error occurred: ${e.message}")
                e.printStackTrace()
                return@withContext false
            }
        }
    }

    // --- Function for direct APK links ---
    suspend fun downloadAndInstallFromUrl(
        context: Context,
        apkUrl: String,
        appName: String,
        onStatusUpdate: (String) -> Unit
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                onStatusUpdate("Downloading $appName...")
                val apkFile = File(context.cacheDir, "$appName.apk")
                URL(apkUrl).openStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        input.copyTo(output)
                    }
                }

                onStatusUpdate("Installing...")
                val result = RootUtils.runAsRoot("pm install -r \"${apkFile.absolutePath}\"")
                apkFile.delete()

                if (result.contains("Success")) {
                    onStatusUpdate("$appName installed successfully!")
                    return@withContext true
                } else {
                    onStatusUpdate("Installation failed.")
                    return@withContext false
                }
            } catch (e: Exception) {
                onStatusUpdate("An error occurred: ${e.message}")
                e.printStackTrace()
                return@withContext false
            }
        }
    }
    
    suspend fun installFromUri(
        context: Context,
        apkUri: Uri,
        appName: String,
        onStatusUpdate: (String) -> Unit
    ): Boolean {
        return withContext(Dispatchers.IO) {
            var apkFile: File? = null
            try {
                onStatusUpdate("Preparing file for installation...")
                apkFile = File(context.cacheDir, "${appName}_temp.apk")
                context.contentResolver.openInputStream(apkUri)?.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        input.copyTo(output)
                    }
                }

                if (apkFile == null || !apkFile.exists()) {
                    onStatusUpdate("Error: Could not read selected file.")
                    return@withContext false
                }

                onStatusUpdate("Installing...")
                val result = RootUtils.runAsRoot("pm install -r \"${apkFile.absolutePath}\"")
                apkFile.delete()

                if (result.contains("Success")) {
                    onStatusUpdate("$appName installed successfully!")
                    return@withContext true
                } else {
                    onStatusUpdate("Installation failed. Output: ${result.take(500)}...")
                    return@withContext false
                }
            } catch (e: Exception) {
                onStatusUpdate("An error occurred: ${e.message}")
                e.printStackTrace()
                apkFile?.delete()
                return@withContext false
            }
        }
    }
}