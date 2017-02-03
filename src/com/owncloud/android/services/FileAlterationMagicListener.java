/**
 * Nextcloud Android client application
 *
 * @author Mario Danic
 * Copyright (C) 2017 Mario Danic
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * <p>
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.owncloud.android.services;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

import com.owncloud.android.MainApp;
import com.owncloud.android.datamodel.SyncedFolder;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.utils.FileStorageUtils;

import org.apache.commons.io.monitor.FileAlterationListener;
import org.apache.commons.io.monitor.FileAlterationObserver;

import java.io.File;
import java.util.Date;

/**
 * Magical file alteration listener
 */

public class FileAlterationMagicListener implements FileAlterationListener {

    public static final String TAG = "FileAlterationMagicListener";

    private Context context;

    private SyncedFolder syncedFolder;

    public FileAlterationMagicListener(SyncedFolder syncedFolder) {
        super();

        context = MainApp.getAppContext();
        this.syncedFolder = syncedFolder;
    }

    @Override
    public void onStart(FileAlterationObserver observer) {
        // This method is intentionally empty
    }

    @Override
    public void onDirectoryCreate(File directory) {
        // This method is intentionally empty
    }

    @Override
    public void onDirectoryChange(File directory) {
        // This method is intentionally empty
    }

    @Override
    public void onDirectoryDelete(File directory) {
        // This method is intentionally empty
    }

    @Override
    public void onFileCreate(File file) {
        PersistableBundle bundle = new PersistableBundle();
        // TODO extract
        bundle.putString(SyncedFolderJobService.LOCAL_PATH, file.getAbsolutePath());
        bundle.putString(SyncedFolderJobService.REMOTE_PATH, FileStorageUtils.getInstantUploadFilePath(
                syncedFolder.getRemotePath(), file.getName(),
                new Date().getTime(),
                syncedFolder.getSubfolderByDate()));
        bundle.putString(SyncedFolderJobService.ACCOUNT, syncedFolder.getAccount());
        bundle.putInt(SyncedFolderJobService.UPLOAD_BEHAVIOUR, syncedFolder.getUploadAction());

        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);

        Long date = new Date().getTime();
        JobInfo job = new JobInfo.Builder(
                date.intValue(),
                new ComponentName(context, SyncedFolderJobService.class))
                .setRequiresCharging(syncedFolder.getChargingOnly())
                .setMinimumLatency(10000)
                .setRequiredNetworkType(syncedFolder.getWifiOnly() ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY)
                .setExtras(bundle)
                .setPersisted(true)
                .build();

        Integer result = js.schedule(job);
        if (result <= 0) {
            Log_OC.d(TAG, "Job failed to start: " + result);
        }

    }

    @Override
    public void onFileChange(File file) {
        // This method is intentionally empty
    }

    @Override
    public void onFileDelete(File file) {
        // This method is intentionally empty
    }

    @Override
    public void onStop(FileAlterationObserver observer) {
        // This method is intentionally empty
    }
}
