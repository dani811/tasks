package org.tasks.opentasks

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.todoroo.andlib.utility.DateUtilities
import com.todoroo.astrid.dao.TaskDao
import com.todoroo.astrid.data.Task
import com.todoroo.astrid.data.Task.Companion.URGENCY_SPECIFIC_DAY
import com.todoroo.astrid.data.Task.Companion.URGENCY_SPECIFIC_DAY_TIME
import com.todoroo.astrid.data.Task.Companion.sanitizeRRule
import com.todoroo.astrid.helper.UUIDHelper
import com.todoroo.astrid.service.TaskCreator
import com.todoroo.astrid.service.TaskDeleter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.fortuna.ical4j.model.Date
import net.fortuna.ical4j.model.property.Geo
import net.fortuna.ical4j.model.property.RRule
import org.dmfs.tasks.contract.TaskContract.Tasks
import org.tasks.LocalBroadcastManager
import org.tasks.analytics.Firebase
import org.tasks.caldav.CaldavConverter
import org.tasks.caldav.CaldavConverter.toRemote
import org.tasks.caldav.iCalendar
import org.tasks.data.*
import org.tasks.data.OpenTaskDao.Companion.getInt
import org.tasks.data.OpenTaskDao.Companion.getLong
import org.tasks.data.OpenTaskDao.Companion.getString
import org.tasks.date.DateTimeUtils.newDateTime
import org.tasks.time.DateTime
import org.tasks.time.DateTimeUtils.currentTimeMillis
import timber.log.Timber
import java.util.*
import javax.inject.Inject

class OpenTasksSynchronizer @Inject constructor(
        @ApplicationContext private val context: Context,
        private val caldavDao: CaldavDao,
        private val taskDeleter: TaskDeleter,
        private val localBroadcastManager: LocalBroadcastManager,
        private val taskCreator: TaskCreator,
        private val taskDao: TaskDao,
        private val firebase: Firebase,
        private val iCalendar: iCalendar,
        private val locationDao: LocationDao,
        private val openTaskDao: OpenTaskDao) {

    private val cr = context.contentResolver

    suspend fun sync() {
        val accountMap = openTaskDao.getLists().groupBy { it.account!! }
        caldavDao
                .findDeletedAccounts(accountMap.keys.toList())
                .forEach { taskDeleter.delete(it) }
        accountMap.forEach { sync(it.key, it.value) }
    }

    private suspend fun sync(account: String, lists: List<CaldavCalendar>) {
        val caldavAccount = caldavDao.getAccountByUuid(account) ?: CaldavAccount().apply {
            uuid = account
            name = account.split(":")[1]
            accountType = CaldavAccount.TYPE_OPENTASKS
            id = caldavDao.insert(this)
        }
        caldavDao
                .findDeletedCalendars(account, lists.mapNotNull { it.url })
                .forEach { taskDeleter.delete(it) }
        lists.forEach {
            val calendar = caldavDao.getCalendarByUrl(account, it.url!!) ?: CaldavCalendar().apply {
                uuid = UUIDHelper.newUUID()
                url = it.url
                this.account = account
                caldavDao.insert(this)
            }
            if (calendar.name != it.name || calendar.color != it.color) {
                calendar.color = it.color
                calendar.name = it.name
                caldavDao.update(calendar)
                localBroadcastManager.broadcastRefreshList()
            }
            sync(calendar, it.ctag, it.id)
        }
        setError(caldavAccount, null)
    }

    private suspend fun sync(calendar: CaldavCalendar, ctag: String?, listId: Long) {
        Timber.d("SYNC $calendar")

        caldavDao.getDeleted(calendar.uuid!!).forEach {
            openTaskDao.delete(listId, it.`object`!!)
            caldavDao.delete(it)
        }

        taskDao.getCaldavTasksToPush(calendar.uuid!!).forEach {
            push(it, listId)
        }

        ctag?.let {
            if (ctag == calendar.ctag) {
                Timber.d("UP TO DATE: $calendar")
                return@sync
            }
        }

        val etags = openTaskDao.getEtags(listId)
        etags.forEach {
            val caldavTask = caldavDao.getTask(calendar.uuid!!, it.first)
            if (caldavTask == null || caldavTask.etag != it.second) {
                applyChanges(calendar, listId, it.first, it.second, caldavTask)
            }
        }
        caldavDao
                .getObjects(calendar.uuid!!)
                .subtract(etags.map { it.first })
                .takeIf { it.isNotEmpty() }
                ?.let {
                    Timber.d("DELETED $it")
                    taskDeleter.delete(caldavDao.getTasks(calendar.uuid!!, it.toList()))
                }

        calendar.ctag = ctag
        Timber.d("UPDATE $calendar")
        caldavDao.update(calendar)
        caldavDao.updateParents(calendar.uuid!!)
        localBroadcastManager.broadcastRefresh()
    }

    private suspend fun setError(account: CaldavAccount, message: String?) {
        account.error = message
        caldavDao.update(account)
        localBroadcastManager.broadcastRefreshList()
        if (!message.isNullOrBlank()) {
            Timber.e(message)
        }
    }

    private suspend fun push(task: Task, listId: Long) = withContext(Dispatchers.IO) {
        val caldavTask = caldavDao.getTask(task.id) ?: return@withContext
        if (task.isDeleted) {
            openTaskDao.delete(listId, caldavTask.`object`!!)
            taskDeleter.delete(task)
            return@withContext
        }
        val values = ContentValues()
        values.put(Tasks._SYNC_ID, caldavTask.`object`)
        values.put(Tasks.LIST_ID, listId)
        values.put(Tasks.TITLE, task.title)
        values.put(Tasks.DESCRIPTION, task.notes)
        values.put(Tasks.GEO, locationDao.getGeofences(task.id).toGeoString())
        values.put(Tasks.RRULE, if (task.isRecurring) {
            val rrule = RRule(task.getRecurrenceWithoutFrom()!!.replace("RRULE:", ""))
            if (task.repeatUntil > 0) {
                rrule.recur.until = DateTime(task.repeatUntil).toUTC().toDateTime()
            }
            RRule(rrule.value.sanitizeRRule()).value
        } else {
            null
        })
        values.put(Tasks.DUE, when {
            task.hasDueTime() -> {
                values.put(Tasks.IS_ALLDAY, null as Int?)
                newDateTime(task.dueDate).toDateTime().time
            }
            task.hasDueDate() -> {
                values.put(Tasks.IS_ALLDAY, 1)
                Date(task.dueDate).time
            }
            else -> null
        })
        values.put(Tasks.COMPLETED, if (task.isCompleted) task.completionDate else null)
        values.put(Tasks.STATUS, if (task.isCompleted) Tasks.STATUS_COMPLETED else null)
        values.put(Tasks.PERCENT_COMPLETE, if (task.isCompleted) 100 else null)
        values.put(Tasks.TZ, if (task.hasDueTime() || task.isCompleted) {
            TimeZone.getDefault().id
        } else {
            null
        })
        // set parent?
        // set tags
        val existing = cr.query(
                Tasks.getContentUri(openTaskDao.authority),
                arrayOf(Tasks.PRIORITY),
                "${Tasks.LIST_ID} = $listId AND ${Tasks._SYNC_ID} = '${caldavTask.`object`}'",
                null,
                null)?.use {
            if (!it.moveToFirst()) {
                return@use false
            }
            values.put(Tasks.PRIORITY, toRemote(it.getInt(Tasks.PRIORITY), task.priority))
            true
        } ?: false
        try {
            if (existing) {
                val updated = cr.update(
                        Tasks.getContentUri(openTaskDao.authority),
                        values,
                        "${Tasks.LIST_ID} = $listId AND ${Tasks._SYNC_ID} = '${caldavTask.`object`}'",
                        null)
                if (updated <= 0) {
                    throw Exception("update failed")
                }
            } else {
                values.put(Tasks.PRIORITY, toRemote(task.priority, task.priority))
                cr.insert(Tasks.getContentUri(openTaskDao.authority), values)
                        ?: throw Exception("insert returned null")
            }
        } catch (e: Exception) {
            firebase.reportException(e)
            return@withContext
        }

        caldavTask.lastSync = currentTimeMillis()
        caldavDao.update(caldavTask)
        Timber.d("SENT $caldavTask")
    }

    private suspend fun applyChanges(
            calendar: CaldavCalendar,
            listId: Long,
            item: String,
            etag: String,
            existing: CaldavTask?) {
        cr.query(
                Tasks.getContentUri(openTaskDao.authority),
                null,
                "${Tasks.LIST_ID} = $listId AND ${Tasks._SYNC_ID} = '$item'",
                null,
                null)?.use {
            if (!it.moveToFirst()) {
                return
            }
            val task: Task
            val caldavTask: CaldavTask
            if (existing == null) {
                task = taskCreator.createWithValues("")
                taskDao.createNew(task)
                val remoteId = it.getColumnIndex(Tasks._UID).let(it::getString)
                caldavTask = CaldavTask(task.id, calendar.uuid, remoteId, item)
            } else {
                task = taskDao.fetch(existing.task)!!
                caldavTask = existing
            }
            task.title = it.getString(Tasks.TITLE)
            task.priority = CaldavConverter.fromRemote(it.getInt(Tasks.PRIORITY))
            task.completionDate = it.getLong(Tasks.COMPLETED)
            task.notes = it.getString(Tasks.DESCRIPTION)
            task.modificationDate = it.getLong(Tasks.LAST_MODIFIED).toLocal()
            task.creationDate = it.getLong(Tasks.CREATED).toLocal()
            task.setDueDateAdjustingHideUntil(it.getLong(Tasks.DUE).let { due ->
                when {
                    due == 0L -> 0
                    it.getBoolean(Tasks.IS_ALLDAY) ->
                        Task.createDueDate(URGENCY_SPECIFIC_DAY, due - DateTime(due).offset)
                    else -> Task.createDueDate(URGENCY_SPECIFIC_DAY_TIME, due)
                }
            })
            iCalendar.setPlace(task.id, it.getString(Tasks.GEO).toGeo())
            task.setRecurrence(it.getString(Tasks.RRULE).toRRule())
            // apply tags
            task.suppressSync()
            task.suppressRefresh()
            taskDao.save(task)
            caldavTask.etag = etag
            caldavTask.lastSync = DateUtilities.now() + 1000L
            // update remote parent?
            if (caldavTask.id == Task.NO_ID) {
                caldavTask.id = caldavDao.insert(caldavTask)
                Timber.d("NEW $caldavTask")
            } else {
                caldavDao.update(caldavTask)
                Timber.d("UPDATE $caldavTask")
            }
        }
    }

    companion object {
        private fun Location?.toGeoString(): String? = this?.let { "$longitude,$latitude" }

        private fun String?.toGeo(): Geo? =
                this?.takeIf { it.isNotBlank() }?.split(",")?.let { Geo("${it[1]};${it[0]}") }

        private fun String?.toRRule(): RRule? =
                this?.takeIf { it.isNotBlank() }?.let(::RRule)

        private fun Cursor.getBoolean(columnName: String): Boolean =
                getInt(getColumnIndex(columnName)) != 0

        private fun Long.toLocal(): Long =
                DateTime(this).toLocal().millis
    }
}