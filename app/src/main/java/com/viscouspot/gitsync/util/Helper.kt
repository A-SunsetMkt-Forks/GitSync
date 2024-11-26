package com.viscouspot.gitsync.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.viscouspot.gitsync.MainActivity
import com.viscouspot.gitsync.R
import com.viscouspot.gitsync.util.Logger.log
import com.viscouspot.gitsync.util.provider.GitProviderManager
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.StringWriter
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64
import kotlin.random.Random


object Helper {
    const val CONFLICT_NOTIFICATION_ID = 1756

    fun extractConflictSections(context: Context, file: File, add: (text: String) -> Unit) {
        val conflictBuilder = StringBuilder()
        var inConflict = false

        BufferedReader(InputStreamReader(FileInputStream(file))).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                when {
                    line!!.startsWith(context.getString(R.string.conflict_end)) -> {
                        conflictBuilder.append(line)
                        add(conflictBuilder.toString().trim())
                        conflictBuilder.clear()
                        inConflict = false
                    }
                    inConflict -> {
                        conflictBuilder.append(line).append("\n")
                    }
                    line!!.startsWith(context.getString(R.string.conflict_start)) -> {
                        inConflict = true
                        conflictBuilder.append(line).append("\n")
                    }
                    else -> {
                        add(line.toString().trim())
                    }
                }
            }

            if (conflictBuilder.isNotEmpty()) {
                add(conflictBuilder.toString().trim())
            }
        }
    }

    fun isNetworkAvailable(context: Context, toastMessage: String = "Network unavailable!\nRetry when reconnected"): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nw = connectivityManager.activeNetwork ?: return false
        val actNw = connectivityManager.getNetworkCapabilities(nw) ?: return false
        return when {
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> true
            else -> {
                Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    fun sendCheckoutConflictNotification(context: Context) {
        val channelId = "git_sync_bug_channel"
        val channel = NotificationChannel(
            channelId,
            "Git Sync Bug",
            NotificationManager.IMPORTANCE_HIGH
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

        val intent = Intent(context, MainActivity::class.java)
        val buttonPendingIntent = PendingIntent.getActivity(context, Random.nextInt(0, 100), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.merge_conflict)
            .setContentTitle("<Merge Conflict> Tap to fix")
            .setContentText("There is an irreconcilable difference between the local and remote changes")
            .setContentIntent(buttonPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        with(NotificationManagerCompat.from(context)) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(context, "${context.getString(R.string.report_bug)} ${context.getString(
                    R.string.enable_notifications)}", Toast.LENGTH_SHORT).show()
                return
            }

            notify(CONFLICT_NOTIFICATION_ID, builder.build())
        }
    }

    fun getDirSelectionLauncher(activityResultLauncher: ActivityResultCaller, context: Context, callback: ((dirUri: Uri?) -> Unit)): ActivityResultLauncher<Uri?> {
        return activityResultLauncher.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                val uriPath = getPathFromUri(context, it)
                val directory = File(uriPath)

                if (!directory.exists() || !directory.isDirectory) {
                    callback.invoke(null)
                    return@let
                }

                try {
                    val testFile = File(directory, "test${System.currentTimeMillis()}.txt")
                    testFile.createNewFile()
                    testFile.delete()
                } catch (e: IOException) {
                    e.printStackTrace()
                    callback.invoke(null)
                    return@let
                }

                try {
                    val configFile = File(directory, context.getString(R.string.git_config_path))
                    if (configFile.exists()) {
                        configFile.readText()
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                    callback.invoke(null)
                    return@let
                }

                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)

                callback.invoke(uri)
            }
        }
    }
    fun getPathFromUri(context: Context, uri: Uri): String {
        val docUriTree = DocumentsContract.buildDocumentUriUsingTree(
            uri,
            DocumentsContract.getTreeDocumentId(uri)
        )

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && DocumentsContract.isDocumentUri(context, docUriTree) -> {
                when {
                    isExternalStorageDocument(docUriTree) -> {
                        val docId = DocumentsContract.getDocumentId(docUriTree)
                        val split = docId.split(":")
                        val type = split[0]

                        if ("primary".equals(type, ignoreCase = true)) {
                            return "${Environment.getExternalStorageDirectory()}/${split[1]}"
                        } else {
                            val externalStorageVolumes = context.getExternalFilesDirs(null)
                            for (externalFile in externalStorageVolumes) {
                                val path = externalFile.absolutePath
                                if (path.contains(type)) {
                                    val subPath = path.substringBefore("/Android")
                                    return "$subPath/${split[1]}"
                                }
                            }
                        }
                    }
                    isDownloadsDocument(docUriTree) -> {
                        val id = DocumentsContract.getDocumentId(docUriTree)
                        val contentUri = ContentUris.withAppendedId(
                            Uri.parse("content://downloads/public_downloads"), id.toLong()
                        )

                        return getDataColumn(context, contentUri, null, null)
                    }
                    isMediaDocument(docUriTree) -> {
                        val docId = DocumentsContract.getDocumentId(docUriTree)
                        val split = docId.split(":")
                        val type = split[0]

                        var contentUri: Uri? = null
                        when (type) {
                            "image" -> contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            "video" -> contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            "audio" -> contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        }

                        val selection = "_id=?"
                        val selectionArgs = arrayOf(split[1])

                        return getDataColumn(context, contentUri, selection, selectionArgs)
                    }
                }
            }
            "content".equals(docUriTree.scheme, ignoreCase = true) -> {
                when {
                    isGooglePhotosUri(docUriTree) -> return uri.lastPathSegment ?: ""
                    else -> return getDataColumn(context, docUriTree, null, null)
                }
            }
            "file".equals(docUriTree.scheme, ignoreCase = true) -> {
                return docUriTree.path ?: ""
            }
        }

        return ""
    }

    private fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    private fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    private fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }

    private fun isGooglePhotosUri(uri: Uri): Boolean {
        return "com.google.android.apps.photos.content" == uri.authority
    }

    private fun getDataColumn(
        context: Context,
        uri: Uri?,
        selection: String?,
        selectionArgs: Array<String>?
    ): String {
        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(column)

        try {
            cursor = context.contentResolver.query(uri!!, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(index)
            }
        } finally {
            cursor?.close()
        }

        return ""
    }

    fun isValidGitRepo(settingsManager: SettingsManager, url: String): String? {
        return null
        val validDomains = mutableListOf(settingsManager.getGitDomain())
        validDomains.addAll(GitProviderManager.defaultDomainMap.values)
        val regex = Regex("^https?://([a-zA-Z0-9.-]+)/(\\S+)/(\\S+)\$")

        return when {
            !regex.matches(url) -> "URL must be an HTTP or HTTPS URL and follow the format 'https://domain/user/repo'"
            !validDomains.any { url.startsWith("https://$it") || url.startsWith("http://$it") } -> "URL domain is not allowed"
            else -> null
        }
    }

    fun generateSSHKeyPair(): Pair<String, String> {
        val keyPairGenerator = Ed25519KeyPairGenerator()
        keyPairGenerator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = keyPairGenerator.generateKeyPair()

        val privateKeyParams = keyPair.private as Ed25519PrivateKeyParameters
        val publicKeyParams = keyPair.public as Ed25519PublicKeyParameters

        fun encode(input: ByteArray): String = Base64.getEncoder().encodeToString(input)

        val privateKey = "-----BEGIN PRIVATE KEY-----\n${encode(OpenSSHPrivateKeyUtil.encodePrivateKey(privateKeyParams))}\n-----END PRIVATE KEY-----"
        val publicKey = "ssh-ed25519 " + encode(OpenSSHPublicKeyUtil.encodePublicKey(publicKeyParams))

        return Pair(privateKey, publicKey)
    }
}

fun EditText.rightDrawable(@DrawableRes id: Int? = 0) {
    val drawable = if (id !=null) ContextCompat.getDrawable(context, id) else null
    val size = resources.getDimensionPixelSize(R.dimen.text_size_lg)
    drawable?.setBounds(0, 0, size, size)
    this.compoundDrawableTintList
    this.setCompoundDrawables(null, null, drawable, null)
}

