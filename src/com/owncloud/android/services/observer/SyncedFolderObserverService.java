/**
 * Nextcloud Android client application
 *
 * @author Mario Danic
 * At least some parts are Copyright (C) 2017 Mario Danic
 * Those parts are under the following licence:
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

package com.owncloud.android.services.observer;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import com.owncloud.android.MainApp;
import com.owncloud.android.datamodel.SyncedFolder;
import com.owncloud.android.datamodel.SyncedFolderProvider;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.services.FileAlterationMagicListener;

import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;

import java.io.File;
import java.io.FileFilter;
import java.util.HashMap;

public class SyncedFolderObserverService extends Service {
    private static final String TAG = "SyncedFolderObserverService";
    private SyncedFolderProvider mProvider;
    private HashMap<String, FileAlterationObserver> syncedFolderMap = new HashMap<>();
    private final IBinder mBinder = new SyncedFolderObserverBinder();
    private FileAlterationMonitor monitor;
    private FileFilter fileFilter;

    @Override
    public void onCreate() {
        mProvider = new SyncedFolderProvider(MainApp.getAppContext().getContentResolver());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        monitor = new FileAlterationMonitor();
        fileFilter = new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                if (pathname.getName().startsWith(".")) {
                    return false;
                }

                return true;
            }
        };
        Log_OC.d(TAG, "start");
        for (SyncedFolder syncedFolder : mProvider.getSyncedFolders()) {
            if (syncedFolder.isEnabled() && !syncedFolderMap.containsKey(syncedFolder.getLocalPath())) {
                Log_OC.d(TAG, "start observer: " + syncedFolder.getLocalPath());

                FileAlterationObserver observer = new FileAlterationObserver(syncedFolder.getLocalPath(), fileFilter);
                observer.addListener(new FileAlterationMagicListener(syncedFolder));
                monitor.addObserver(observer);
                try {
                    monitor.start();
                    syncedFolderMap.put(syncedFolder.getLocalPath(), observer);
                } catch (Exception e) {
                    Log_OC.d(TAG, "Something went very wrong at onStartCommand");
                }

            }
        }

        return Service.START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        for (FileAlterationObserver observer : syncedFolderMap.values()) {
            monitor.removeObserver(observer);
            try {
                observer.destroy();
            } catch (Exception e) {
                Log_OC.d(TAG, "Something went very wrong at onDestroy");
            }
            syncedFolderMap.remove(observer);
        }
    }

    /**
     * Restart oberver if it is enabled
     * If syncedFolder exists already, use it, otherwise create new observer
     *
     * @param syncedFolder
     */

    public void restartObserver(SyncedFolder syncedFolder) {
        FileAlterationObserver fileAlterationObserver;
        if (syncedFolderMap.containsKey(syncedFolder.getLocalPath())) {
            Log_OC.d(TAG, "stop observer: " + syncedFolder.getLocalPath());
            fileAlterationObserver = syncedFolderMap.get(syncedFolder.getLocalPath());
            monitor.removeObserver(fileAlterationObserver);
            try {
                fileAlterationObserver.destroy();
            } catch (Exception e) {
                Log_OC.d(TAG, "Something went very wrong at onDestroy");
            }
            syncedFolderMap.remove(syncedFolder.getLocalPath());
        }

        if (syncedFolder.isEnabled()) {
            Log_OC.d(TAG, "start observer: " + syncedFolder.getLocalPath());
            if (syncedFolderMap.containsKey(syncedFolder.getLocalPath())) {
                fileAlterationObserver = syncedFolderMap.get(syncedFolder.getLocalPath());
                if (fileAlterationObserver.getListeners() == null) {
                    fileAlterationObserver.addListener(new FileAlterationMagicListener(syncedFolder));
                }
                monitor.addObserver(fileAlterationObserver);
            } else {
                fileAlterationObserver = new FileAlterationObserver(syncedFolder.getLocalPath(), fileFilter);
                fileAlterationObserver.addListener(new FileAlterationMagicListener(syncedFolder));
                monitor.addObserver(fileAlterationObserver);
                try {
                    syncedFolderMap.put(syncedFolder.getLocalPath(), fileAlterationObserver);
                } catch (Exception e) {
                    Log_OC.d(TAG, "Something went very wrong on RestartObserver");
                }

            }
        }
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return mBinder;
    }

    public class SyncedFolderObserverBinder extends Binder {
        public SyncedFolderObserverService getService() {
            return SyncedFolderObserverService.this;
        }
    }

}
