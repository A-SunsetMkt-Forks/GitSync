package com.viscouspot.gitsync

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.viscouspot.gitsync.util.GitManager
import com.viscouspot.gitsync.util.Helper
import com.viscouspot.gitsync.util.Helper.CONFLICT_NOTIFICATION_ID
import com.viscouspot.gitsync.util.Helper.debounced
import com.viscouspot.gitsync.util.Helper.makeToast
import com.viscouspot.gitsync.util.LogType
import com.viscouspot.gitsync.util.Logger.log
import com.viscouspot.gitsync.util.NetworkWorker
import com.viscouspot.gitsync.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class GitSyncService : Service() {
    private lateinit var gitManager: GitManager
    private lateinit var settingsManager: SettingsManager

    private var isScheduled: Boolean = false
    private var isSyncing: Boolean = false
    private val debouncePeriod: Long = 10 * 1000

    private val debouncedSyncFn = debounced<Boolean>(1000) { forced ->
        sync(forced)
    }

    companion object {
        const val MERGE = "MERGE"
        const val FORCE_SYNC = "FORCE_SYNC"
        const val APPLICATION_SYNC = "APPLICATION_SYNC"
        const val INTENT_SYNC = "INTENT_SYNC"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == null) {
            return START_STICKY
        }

        when (intent.action) {
            MERGE -> {
                log(LogType.ToServiceCommand, "Merge")
                merge()
            }
            FORCE_SYNC -> {
                log(LogType.ToServiceCommand, "Force Sync")
                debouncedSync(forced = true)
            }
            APPLICATION_SYNC -> {
                log(LogType.ToServiceCommand, "AccessibilityService Sync")
                debouncedSync()
            }
            INTENT_SYNC -> {
                log(LogType.ToServiceCommand, "Intent Sync")
                debouncedSync()
            }
        }

        return START_STICKY
    }


    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(this)
        gitManager = GitManager(this, settingsManager)

    }

    private fun scheduleNetworkSync() {
        log(LogType.Sync, "Scheduling sync for network regained")
        val constraints: Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val workRequest = OneTimeWorkRequest.Builder(NetworkWorker::class.java)
            .setConstraints(constraints).build()

        WorkManager.getInstance(this).enqueueUniqueWork("networkScheduledSync", ExistingWorkPolicy.KEEP, workRequest)
        return
    }

    private fun debouncedSync(forced: Boolean = false) {
        if (!Helper.isNetworkAvailable(this, getString(R.string.network_unavailable))) {
            scheduleNetworkSync()
        }
        if (isScheduled) {
            return
        } else {
            if (isSyncing) {
                isScheduled = true
                log(LogType.Sync, "Sync Scheduled")
                return
            } else {
                debouncedSyncFn(forced)
            }
        }
    }

    private fun sync(forced: Boolean = false) {
        if (gitManager.getConflicting(settingsManager.getGitDirUri()).isNotEmpty()) {
            Handler(Looper.getMainLooper()).post {
                makeToast(
                    applicationContext,
                    "Ongoing merge conflict"
                )
            }
            return
        }

        log(LogType.Sync, "Start Sync")
        isSyncing = true

        val job = CoroutineScope(Dispatchers.Default).launch {
            val gitDirUri = settingsManager.getGitDirUri()

            if (gitDirUri == null) {
                withContext(Dispatchers.Main) {
                    log(LogType.Sync, "Repository Not Found")
                    makeToast(
                        applicationContext,
                        getString(R.string.repository_not_found),
                        Toast.LENGTH_LONG
                    )
                }
                return@launch
            }

            var synced = false

            log(LogType.Sync, "Start Pull Repo")
            val pullResult = gitManager.downloadChanges(
                gitDirUri,
                ::scheduleNetworkSync,
            ) {
                synced = true
                displaySyncMessage(getString(R.string.sync_start_pull))
            }

            when (pullResult) {
                null -> {
                    log(LogType.Sync, "Pull Repo Failed")
                    return@launch
                }
                true -> log(LogType.Sync, "Pull Complete")
                false -> log(LogType.Sync, "Pull Not Required")
            }

            while (File(Helper.getPathFromUri(applicationContext, gitDirUri),
                    getString(R.string.git_lock_path)).exists()) {
                delay(1000)
            }

            log(LogType.Sync, "Start Push Repo")
            val pushResult = gitManager.uploadChanges(
                gitDirUri,
                ::scheduleNetworkSync,
            ) {
                if (!synced) {
                    displaySyncMessage(getString(R.string.sync_start_push))
                }
            }

            when (pushResult) {
                null -> {
                    log(LogType.Sync, "Push Repo Failed")
                    return@launch
                }
                true -> log(LogType.Sync, "Push Complete")
                false -> log(LogType.Sync, "Push Not Required")
            }

            while (File(Helper.getPathFromUri(applicationContext, gitDirUri), getString(R.string.git_lock_path)).exists()) {
                delay(1000)
            }

            if (!(pushResult || pullResult)) {
                if (forced) {
                    displaySyncMessage(getString(R.string.sync_not_required))
                }
                return@launch
            } else {
                displaySyncMessage(getString(R.string.sync_complete))
            }

        }

        job.invokeOnCompletion {
            log(LogType.Sync, "Sync Complete")

            val intent = Intent(MainActivity.REFRESH)
            LocalBroadcastManager.getInstance(this@GitSyncService).sendBroadcast(intent)

            isSyncing = false
            if (isScheduled) {
                CoroutineScope(Dispatchers.Default).launch {
                    delay(debouncePeriod)
                    log(LogType.Sync, "Scheduled Sync Starting")
                    isScheduled = false
                    sync()
                }
            } else {
                stopSelf()
            }
        }
    }

    private fun merge() {
        CoroutineScope(Dispatchers.Default).launch {
            val authCredentials = settingsManager.getGitAuthCredentials()
            if (settingsManager.getGitDirUri() == null || ((authCredentials.first == "" || authCredentials.second == "") && settingsManager.getGitSshPrivateKey() == "")) return@launch

            val pushResult = gitManager.uploadChanges(
                settingsManager.getGitDirUri()!!,
                ::scheduleNetworkSync,
            ) {
                Handler(Looper.getMainLooper()).post {
                    makeToast(
                        applicationContext,
                        getString(R.string.resolving_merge),
                    )
                }
            }

            when (pushResult) {
                null -> {
                    log(LogType.Sync, "Merge Failed")
                    return@launch
                }

                true -> log(LogType.Sync, "Merge Complete")
                false -> log(LogType.Sync, "Merge Not Required")
            }

            debouncedSync(forced = true)

            val intent = Intent(MainActivity.MERGE_COMPLETE)
            LocalBroadcastManager.getInstance(this@GitSyncService).sendBroadcast(intent)

            NotificationManagerCompat.from(applicationContext).cancel(CONFLICT_NOTIFICATION_ID)
        }
    }

    private fun displaySyncMessage(msg: String) {
        if (settingsManager.getSyncMessageEnabled()) {
            Handler(Looper.getMainLooper()).post {
                makeToast(applicationContext, msg)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}