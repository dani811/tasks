package org.tasks.data

import android.content.Context
import android.database.Cursor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dmfs.tasks.contract.TaskContract
import org.json.JSONObject
import org.tasks.R
import org.tasks.preferences.PermissionChecker
import javax.inject.Inject

class OpenTaskDao @Inject constructor(
        @ApplicationContext context: Context,
        permissionChecker: PermissionChecker) {

    private val cr = context.contentResolver
    val authority = if (permissionChecker.canAccessOpenTasks()) {
        "org.dmfs.tasks"
    } else {
        context.getString(R.string.opentasks_authority)
    }

    suspend fun accountCount(): Int = getLists().map { it.account!! }.toHashSet().size

    suspend fun getLists(): List<CaldavCalendar> = withContext(Dispatchers.IO) {
        val calendars = ArrayList<CaldavCalendar>()
        cr.query(
                TaskContract.TaskLists.getContentUri(authority),
                null,
                "${TaskContract.TaskListColumns.SYNC_ENABLED}=1 AND (${TaskContract.ACCOUNT_TYPE} = 'bitfire.at.davdroid' OR ${TaskContract.ACCOUNT_TYPE} = 'com.etesync.syncadapter')",
                null,
                null)?.use {
            while (it.moveToNext()) {
                val accountType = it.getString(TaskContract.TaskLists.ACCOUNT_TYPE)
                val accountName = it.getString(TaskContract.TaskLists.ACCOUNT_NAME)
                calendars.add(CaldavCalendar().apply {
                    id = it.getLong(TaskContract.TaskLists._ID)
                    account = "$accountType:$accountName"
                    name = it.getString(TaskContract.TaskLists.LIST_NAME)
                    color = it.getInt(TaskContract.TaskLists.LIST_COLOR)
                    url = it.getString(TaskContract.CommonSyncColumns._SYNC_ID)
                    ctag = it.getString(TaskContract.TaskLists.SYNC_VERSION)
                            ?.let(::JSONObject)
                            ?.getString("value")
                })
            }
        }
        calendars
    }

    suspend fun getEtags(listId: Long): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val items = ArrayList<Pair<String, String>>()
        cr.query(
                TaskContract.Tasks.getContentUri(authority),
                arrayOf(TaskContract.Tasks._SYNC_ID, TaskContract.Tasks.SYNC1),
                "${TaskContract.Tasks.LIST_ID} = $listId",
                null,
                null)?.use {
            while (it.moveToNext()) {
                Pair(it.getString(TaskContract.Tasks._SYNC_ID)!!, it.getString(TaskContract.Tasks.SYNC1)!!).let(items::add)
            }
        }
        items
    }

    suspend fun delete(listId: Long, item: String): Int = withContext(Dispatchers.IO) {
        cr.delete(
                TaskContract.Tasks.getContentUri(authority),
                "${TaskContract.Tasks.LIST_ID} = $listId AND ${TaskContract.Tasks._SYNC_ID} = '$item'",
                null)
    }

    companion object {
        fun Cursor.getString(columnName: String): String? =
                getString(getColumnIndex(columnName))

        fun Cursor.getInt(columnName: String): Int =
                getInt(getColumnIndex(columnName))

        fun Cursor.getLong(columnName: String): Long =
                getLong(getColumnIndex(columnName))
    }
}