/*
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */

package com.todoroo.astrid.gtasks;

import com.google.api.services.tasks.model.TaskList;
import com.todoroo.astrid.service.TaskDeleter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import org.tasks.LocalBroadcastManager;
import org.tasks.data.GoogleTaskAccount;
import org.tasks.data.GoogleTaskList;
import org.tasks.data.GoogleTaskListDaoBlocking;
import timber.log.Timber;

public class GtasksListService {

  private final GoogleTaskListDaoBlocking googleTaskListDao;
  private final TaskDeleter taskDeleter;
  private final LocalBroadcastManager localBroadcastManager;

  @Inject
  public GtasksListService(
      GoogleTaskListDaoBlocking googleTaskListDao,
      TaskDeleter taskDeleter,
      LocalBroadcastManager localBroadcastManager) {
    this.googleTaskListDao = googleTaskListDao;
    this.taskDeleter = taskDeleter;
    this.localBroadcastManager = localBroadcastManager;
  }

  /**
   * Reads in remote list information and updates local list objects.
   *
   * @param remoteLists remote information about your lists
   */
  public synchronized void updateLists(GoogleTaskAccount account, List<TaskList> remoteLists) {
    List<GoogleTaskList> lists = googleTaskListDao.getLists(account.getAccount());

    Set<Long> previousLists = new HashSet<>();
    for (GoogleTaskList list : lists) {
      previousLists.add(list.getId());
    }

    for (int i = 0; i < remoteLists.size(); i++) {
      com.google.api.services.tasks.model.TaskList remote = remoteLists.get(i);

      String id = remote.getId();
      GoogleTaskList local = null;
      for (GoogleTaskList list : lists) {
        if (list.getRemoteId().equals(id)) {
          local = list;
          break;
        }
      }

      String title = remote.getTitle();
      if (local == null) {
        GoogleTaskList byRemoteId = googleTaskListDao.findExistingList(id);
        if (byRemoteId != null) {
          byRemoteId.setAccount(account.getAccount());
          local = byRemoteId;
        } else {
          Timber.d("Adding new gtask list %s", title);
          local = new GoogleTaskList();
          local.setAccount(account.getAccount());
          local.setRemoteId(id);
        }
      }

      local.setTitle(title);
      googleTaskListDao.insertOrReplace(local);
      previousLists.remove(local.getId());
    }

    // check for lists that aren't on remote server
    for (Long listId : previousLists) {
      taskDeleter.delete(googleTaskListDao.getById(listId));
    }

    localBroadcastManager.broadcastRefreshList();
  }
}
