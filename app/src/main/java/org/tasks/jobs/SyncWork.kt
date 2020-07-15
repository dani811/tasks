package org.tasks.jobs

import android.content.Context
import androidx.hilt.Assisted
import androidx.hilt.work.WorkerInject
import androidx.work.WorkerParameters
import kotlinx.coroutines.*
import org.tasks.LocalBroadcastManager
import org.tasks.analytics.Firebase
import org.tasks.caldav.CaldavSynchronizer
import org.tasks.data.CaldavAccount.Companion.TYPE_CALDAV
import org.tasks.data.CaldavAccount.Companion.TYPE_ETESYNC
import org.tasks.data.CaldavDao
import org.tasks.data.GoogleTaskListDao
import org.tasks.etesync.EteSynchronizer
import org.tasks.gtasks.GoogleTaskSynchronizer
import org.tasks.injection.BaseWorker
import org.tasks.opentasks.OpenTasksSynchronizer
import org.tasks.preferences.Preferences
import org.tasks.sync.SyncAdapters

class SyncWork @WorkerInject constructor(
        @Assisted context: Context,
        @Assisted workerParams: WorkerParameters,
        firebase: Firebase,
        private val caldavSynchronizer: CaldavSynchronizer,
        private val eteSynchronizer: EteSynchronizer,
        private val openTasksSynchronizer: OpenTasksSynchronizer,
        private val googleTaskSynchronizer: GoogleTaskSynchronizer,
        private val localBroadcastManager: LocalBroadcastManager,
        private val preferences: Preferences,
        private val caldavDao: CaldavDao,
        private val googleTaskListDao: GoogleTaskListDao,
        private val syncAdapters: SyncAdapters) : BaseWorker(context, workerParams, firebase) {
    
    override suspend fun run(): Result {
        if (!syncAdapters.isSyncEnabled()) {
            return Result.success()
        }
        synchronized(LOCK) {
            if (preferences.isSyncOngoing) {
                return Result.retry()
            }
            preferences.isSyncOngoing = true
        }
        localBroadcastManager.broadcastRefresh()
        try {
            googleTaskJobs()
                    .plus(caldavJobs())
                    .plus(davx5Job())
                    .awaitAll()
        } catch (e: Exception) {
            firebase.reportException(e)
        } finally {
            preferences.isSyncOngoing = false
            localBroadcastManager.broadcastRefresh()
        }
        return Result.success()
    }

    private suspend fun caldavJobs(): List<Deferred<Unit>> = coroutineScope {
        caldavDao
                .getAccounts()
                .filter { it.accountType == TYPE_CALDAV || it.accountType == TYPE_ETESYNC }
                .map {
                    async(Dispatchers.IO) {
                        when {
                            it.isCaldavAccount -> caldavSynchronizer.sync(it)
                            it.isEteSyncAccount -> eteSynchronizer.sync(it)
                        }
                    }
                }
    }

    @Suppress("DeferredIsResult")
    private suspend fun davx5Job(): Deferred<Unit> = coroutineScope {
        async(Dispatchers.IO) {
            openTasksSynchronizer.sync()
        }
    }

    private suspend fun googleTaskJobs(): List<Deferred<Unit>> = coroutineScope {
        googleTaskListDao
                .getAccounts()
                .mapIndexed { i, account ->
                    async(Dispatchers.IO) {
                        googleTaskSynchronizer.sync(account, i)
                    }
                }
    }

    companion object {
        private val LOCK = Any()
    }
}