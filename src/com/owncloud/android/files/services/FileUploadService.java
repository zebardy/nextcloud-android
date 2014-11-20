/* ownCloud Android client application
 *   Copyright (C) 2012 Bartek Przybylski
 *   Copyright (C) 2012-2013 ownCloud Inc.
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License version 2,
 *   as published by the Free Software Foundation.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.owncloud.android.files.services;

import java.io.File;
import java.io.IOException;
import java.util.AbstractList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountsException;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.support.v4.app.NotificationCompat;
import android.webkit.MimeTypeMap;

import com.owncloud.android.R;
import com.owncloud.android.authentication.AccountUtils;
import com.owncloud.android.authentication.AuthenticatorActivity;
import com.owncloud.android.datamodel.FileDataStorageManager;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.db.UploadDbHandler;
import com.owncloud.android.db.UploadDbHandler.UploadStatus;
import com.owncloud.android.db.UploadDbObject;
import com.owncloud.android.files.InstantUploadBroadcastReceiver;
import com.owncloud.android.lib.common.OwnCloudAccount;
import com.owncloud.android.lib.common.OwnCloudClient;
import com.owncloud.android.lib.common.OwnCloudClientManagerFactory;
import com.owncloud.android.lib.common.accounts.AccountUtils.Constants;
import com.owncloud.android.lib.common.network.OnDatatransferProgressListener;
import com.owncloud.android.lib.common.operations.RemoteOperation;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.common.operations.RemoteOperationResult.ResultCode;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.lib.resources.files.ExistenceCheckRemoteOperation;
import com.owncloud.android.lib.resources.files.ReadRemoteFileOperation;
import com.owncloud.android.lib.resources.files.RemoteFile;
import com.owncloud.android.lib.resources.status.OwnCloudVersion;
import com.owncloud.android.notifications.NotificationBuilderWithProgressBar;
import com.owncloud.android.notifications.NotificationDelayer;
import com.owncloud.android.operations.CreateFolderOperation;
import com.owncloud.android.operations.UploadFileOperation;
import com.owncloud.android.operations.common.SyncOperation;
import com.owncloud.android.ui.activity.FileActivity;
import com.owncloud.android.ui.activity.FileDisplayActivity;
import com.owncloud.android.utils.ErrorMessageAdapter;
import com.owncloud.android.utils.UriUtils;

/**
 * Service for uploading files. Invoke using context.startService(...). This
 * service retries until upload succeeded. Files to be uploaded are stored
 * persistent using {@link UploadDbHandler}.
 * 
 * @author LukeOwncloud
 * 
 */
@SuppressWarnings("unused")
public class FileUploadService extends Service {

    private static final String UPLOAD_FINISH_MESSAGE = "UPLOAD_FINISH";
    public static final String EXTRA_UPLOAD_RESULT = "RESULT";
    public static final String EXTRA_REMOTE_PATH = "REMOTE_PATH";
    public static final String EXTRA_OLD_REMOTE_PATH = "OLD_REMOTE_PATH";
    public static final String EXTRA_OLD_FILE_PATH = "OLD_FILE_PATH";
    public static final String ACCOUNT_NAME = "ACCOUNT_NAME";

    public static final String KEY_FILE = "FILE";
    public static final String KEY_LOCAL_FILE = "LOCAL_FILE";
    public static final String KEY_REMOTE_FILE = "REMOTE_FILE";
    public static final String KEY_MIME_TYPE = "MIME_TYPE";

    public static final String KEY_ACCOUNT = "ACCOUNT";

    public static final String KEY_UPLOAD_TYPE = "UPLOAD_TYPE";
    public static final String KEY_FORCE_OVERWRITE = "KEY_FORCE_OVERWRITE";
    public static final String KEY_CREATE_REMOTE_FOLDER = "CREATE_REMOTE_FOLDER";
    public static final String KEY_WIFI_ONLY = "WIFI_ONLY";
    public static final String KEY_LOCAL_BEHAVIOUR = "BEHAVIOUR";

    /**
     * Describes local behavior for upload.
     */
    public enum LocalBehaviour {
        /**
         * Creates a copy of file and stores it in tmp folder inside owncloud
         * folder on sd-card. After upload it is moved to local owncloud
         * storage. Original file stays untouched.
         */
        LOCAL_BEHAVIOUR_COPY(0),
        /**
         * Upload file from current storage. Afterwards original file is move to
         * local owncloud storage.
         */
        LOCAL_BEHAVIOUR_MOVE(1),
        /**
         * Just uploads file and leaves it where it is. Original file stays
         * untouched.
         */
        LOCAL_BEHAVIOUR_FORGET(2);
        private final int value;

        private LocalBehaviour(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }
    
    public enum UploadSingleMulti {
        UPLOAD_SINGLE_FILE(0), UPLOAD_MULTIPLE_FILES(1);
        private final int value;

        private UploadSingleMulti(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    };

    // public static final int UPLOAD_SINGLE_FILE = 0;
    // public static final int UPLOAD_MULTIPLE_FILES = 1;

    private static final String TAG = FileUploadService.class.getSimpleName();

    private Looper mServiceLooper;
    private ServiceHandler mServiceHandler;
    private IBinder mBinder;
    private ConnectivityChangeReceiver mConnectivityChangeReceiver;
    private OwnCloudClient mUploadClient = null;
    private Account mLastAccount = null;
    private FileDataStorageManager mStorageManager;
    //since there can be only one instance of an Android service, there also just one db connection.
    private UploadDbHandler mDb = null;

    /**
     * List of uploads that currently in progress. Maps from remotePath to where file
     * is being uploaded to {@link UploadFileOperation}.
     */
    private ConcurrentMap<String, UploadFileOperation> mActiveUploads = new ConcurrentHashMap<String, UploadFileOperation>();
    
    private NotificationManager mNotificationManager;
    private NotificationCompat.Builder mNotificationBuilder;

    private static final String MIME_TYPE_PDF = "application/pdf";
    private static final String FILE_EXTENSION_PDF = ".pdf";

    public static String getUploadFinishMessage() {
        return FileUploadService.class.getName().toString() + UPLOAD_FINISH_MESSAGE;
    }

    /**
     * Builds a key for mPendingUploads from the account and file to upload
     * 
     * @param account Account where the file to upload is stored
     * @param file File to upload
     */
    private String buildRemoteName(Account account, OCFile file) {
        return account.name + file.getRemotePath();
    }

    
    private String buildRemoteName(Account account, String remotePath) {
        return account.name + remotePath;
    }

    /**
     * Checks if an ownCloud server version should support chunked uploads.
     * 
     * @param version OwnCloud version instance corresponding to an ownCloud
     *            server.
     * @return 'True' if the ownCloud server with version supports chunked
     *         uploads.
     */
    private static boolean chunkedUploadIsSupported(OwnCloudVersion version) {
        return (version != null && version.compareTo(OwnCloudVersion.owncloud_v4_5) >= 0);
    }

    /**
     * Service initialization
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Log_OC.i(TAG, "mPendingUploads size:" + mActiveUploads.size());
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        HandlerThread thread = new HandlerThread("FileUploaderThread", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        mServiceLooper = thread.getLooper();
        mServiceHandler = new ServiceHandler(mServiceLooper, this);
        mBinder = new FileUploaderBinder();
        mConnectivityChangeReceiver = new ConnectivityChangeReceiver();
        registerReceiver(mConnectivityChangeReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        mDb = UploadDbHandler.getInstance(this.getBaseContext());
        mDb.recreateDb(); //for testing only
    }

    public class ConnectivityChangeReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context arg0, Intent arg1) {
            if(InstantUploadBroadcastReceiver.isOnline(getApplicationContext()))
            {
                // upload pending wifi only files.
                onStartCommand(null, 0, 0);
            }
        }

    }

    @Override
    public void onDestroy() {
        mDb.close();
        unregisterReceiver(mConnectivityChangeReceiver);
        super.onDestroy();
    }

    /**
     * Entry point to add one or several files to the queue of uploads.
     * 
     * New uploads are added calling to startService(), resulting in a call to
     * this method. This ensures the service will keep on working although the
     * caller activity goes away.
     * 
     * First, onStartCommand() stores all information associated with the upload
     * in a {@link UploadDbObject} which is stored persistently using
     * {@link UploadDbHandler}. Then, {@link ServiceHandler} is invoked which
     * performs the upload and updates the DB entry (upload success, failure,
     * retry, ...)
     * 
     * TODO: correct return values. should not always be NOT_STICKY.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AbstractList<UploadDbObject> requestedUploads = new Vector<UploadDbObject>();
        if (intent == null) {
            // service was restarted by OS (after return START_STICKY and kill
            // service) or connectivity change was detected. ==> check persistent upload
            // list.
            //
            List<UploadDbObject> list = mDb.getAllPendingUploads();
            for (UploadDbObject uploadDbObject : list) {
                uploadDbObject.setUploadStatus(UploadStatus.UPLOAD_LATER);
                uploadDbObject.setLastResult(null);
                mDb.updateUpload(uploadDbObject);
            }
            requestedUploads.addAll(list);
        } else {

            UploadSingleMulti uploadType = (UploadSingleMulti) intent.getSerializableExtra(KEY_UPLOAD_TYPE);
            if (uploadType == null) {
                Log_OC.e(TAG, "Incorrect or no upload type provided");
                return Service.START_NOT_STICKY;
            }

            Account account = intent.getParcelableExtra(KEY_ACCOUNT);
            if (!AccountUtils.exists(account, getApplicationContext())) {
                Log_OC.e(TAG, "KEY_ACCOUNT no set or provided KEY_ACCOUNT does not exist");
                return Service.START_NOT_STICKY;
            }

            OCFile[] files = null;
            // if KEY_FILE given, use it
            if (intent.hasExtra(KEY_FILE)) {
                if (uploadType == UploadSingleMulti.UPLOAD_SINGLE_FILE) {
                    files = new OCFile[] { intent.getParcelableExtra(KEY_FILE) };
                } else {
                    files = (OCFile[]) intent.getParcelableArrayExtra(KEY_FILE);
                }

            } else { // else use KEY_LOCAL_FILE and KEY_REMOTE_FILE

                if (!intent.hasExtra(KEY_LOCAL_FILE) || !intent.hasExtra(KEY_REMOTE_FILE)) {
                    Log_OC.e(TAG, "Not enough information provided in intent");
                    return Service.START_NOT_STICKY;
                }

                String[] localPaths;
                String[] remotePaths;
                String[] mimeTypes;
                if (uploadType == UploadSingleMulti.UPLOAD_SINGLE_FILE) {
                    localPaths = new String[] { intent.getStringExtra(KEY_LOCAL_FILE) };
                    remotePaths = new String[] { intent.getStringExtra(KEY_REMOTE_FILE) };
                    mimeTypes = new String[] { intent.getStringExtra(KEY_MIME_TYPE) };
                } else {
                    localPaths = intent.getStringArrayExtra(KEY_LOCAL_FILE);
                    remotePaths = intent.getStringArrayExtra(KEY_REMOTE_FILE);
                    mimeTypes = intent.getStringArrayExtra(KEY_MIME_TYPE);
                }
                if (localPaths.length != remotePaths.length) {
                    Log_OC.e(TAG, "Different number of remote paths and local paths!");
                    return Service.START_NOT_STICKY;
                }

                files = new OCFile[localPaths.length];

                for (int i = 0; i < localPaths.length; i++) {
                    files[i] = obtainNewOCFileToUpload(remotePaths[i], localPaths[i],
                            ((mimeTypes != null) ? mimeTypes[i] : (String) null));
                    if (files[i] == null) {
                        Log_OC.e(TAG, "obtainNewOCFileToUpload() returned null for remotePaths[i]:" + remotePaths[i]
                                + " and localPaths[i]:" + localPaths[i]);
                        return Service.START_NOT_STICKY;
                    }
                }
            }

            // at this point variable "OCFile[] files" is loaded correctly.

            boolean forceOverwrite = intent.getBooleanExtra(KEY_FORCE_OVERWRITE, false);
            boolean isCreateRemoteFolder = intent.getBooleanExtra(KEY_CREATE_REMOTE_FOLDER, false);
            boolean isUseWifiOnly = intent.getBooleanExtra(KEY_WIFI_ONLY, true);
            LocalBehaviour localAction = (LocalBehaviour) intent.getSerializableExtra(KEY_LOCAL_BEHAVIOUR);
            if (localAction == null)
                localAction = LocalBehaviour.LOCAL_BEHAVIOUR_COPY;

            // save always persistently path of upload, so it can be retried if
            // failed.
            for (int i = 0; i < files.length; i++) {
                UploadDbObject uploadObject = new UploadDbObject();
                uploadObject.setRemotePath(files[i].getRemotePath());
                uploadObject.setLocalPath(files[i].getStoragePath());
                uploadObject.setMimeType(files[i].getMimetype());
                uploadObject.setAccountName(account.name);
                uploadObject.setForceOverwrite(forceOverwrite);
                uploadObject.setCreateRemoteFolder(isCreateRemoteFolder);
                uploadObject.setLocalAction(localAction);
                uploadObject.setUseWifiOnly(isUseWifiOnly);
                uploadObject.setUploadStatus(UploadStatus.UPLOAD_LATER);
                boolean success = mDb.storeUpload(uploadObject);
                if(!success) {
                    Log_OC.e(TAG, "Could not add upload to database. It is probably a duplicate. Ignore.");
                } else {
                    requestedUploads.add(uploadObject);
                }
            }
            
            
            // TODO check if would be clever to read entries from
            // UploadDbHandler and add to requestedUploads at this point

        }
        Log_OC.i(TAG, "mPendingUploads size:" + mActiveUploads.size());
        if (requestedUploads.size() > 0) {
            Message msg = mServiceHandler.obtainMessage();
            msg.arg1 = startId;
            msg.obj = requestedUploads;
            mServiceHandler.sendMessage(msg);
            return Service.START_STICKY; // there is work to do. If killed this
                                         // service should be restarted
                                         // eventually. 
        }        
        return Service.START_NOT_STICKY; //nothing to do. do not restart.
    }

    /**
     * Provides a binder object that clients can use to perform operations on
     * the queue of uploads, excepting the addition of new files.
     * 
     * Implemented to perform cancellation, pause and resume of existing
     * uploads.
     */
    @Override
    public IBinder onBind(Intent arg0) {
        return mBinder;
    }

    /**
     * Called when ALL the bound clients were onbound.
     */
    @Override
    public boolean onUnbind(Intent intent) {
        ((FileUploaderBinder) mBinder).clearListeners();
        return false; // not accepting rebinding (default behaviour)
    }

    /**
     * Binder to let client components to perform operations on the queue of
     * uploads.
     * 
     * It provides by itself the available operations.
     */
    public class FileUploaderBinder extends Binder implements OnDatatransferProgressListener {

        /**
         * Map of listeners that will be reported about progress of uploads from
         * a {@link FileUploaderBinder} instance
         */
        private Map<String, OnDatatransferProgressListener> mBoundListeners = new HashMap<String, OnDatatransferProgressListener>();

        /**
         * Cancels a pending or current upload of a remote file.
         * 
         * @param account Owncloud account where the remote file will be stored.
         * @param file A file in the queue of pending uploads
         */
        public void cancel(Account account, OCFile file) {
            UploadFileOperation upload = null;
            synchronized (mActiveUploads) {
                upload = mActiveUploads.remove(buildRemoteName(account, file));
            }
            if (upload != null) {
                upload.cancel();
            }
        }

        public void clearListeners() {
            mBoundListeners.clear();
        }

        /**
         * Returns True when the file described by 'file' is being uploaded to
         * the ownCloud account 'account' or waiting for it
         * 
         * If 'file' is a directory, returns 'true' if some of its descendant
         * files is uploading or waiting to upload.
         * 
         * @param account Owncloud account where the remote file will be stored.
         * @param file A file that could be in the queue of pending uploads
         */
        public boolean isUploading(Account account, OCFile file) {
            if (account == null || file == null)
                return false;
            String targetKey = buildRemoteName(account, file);
            synchronized (mActiveUploads) {
                if (file.isFolder()) {
                    // this can be slow if there are many uploads :(
                    Iterator<String> it = mActiveUploads.keySet().iterator();
                    boolean found = false;
                    while (it.hasNext() && !found) {
                        found = it.next().startsWith(targetKey);
                    }
                    return found;
                } else {
                    return (mActiveUploads.containsKey(targetKey));
                }
            }
        }

        /**
         * Adds a listener interested in the progress of the upload for a
         * concrete file.
         * 
         * @param listener Object to notify about progress of transfer.
         * @param account ownCloud account holding the file of interest.
         * @param file {@link OCfile} of interest for listener.
         */
        public void addDatatransferProgressListener(OnDatatransferProgressListener listener, Account account,
                OCFile file) {
            if (account == null || file == null || listener == null)
                return;
            String targetKey = buildRemoteName(account, file);
            mBoundListeners.put(targetKey, listener);
        }

        /**
         * Removes a listener interested in the progress of the upload for a
         * concrete file.
         * 
         * @param listener Object to notify about progress of transfer.
         * @param account ownCloud account holding the file of interest.
         * @param file {@link OCfile} of interest for listener.
         */
        public void removeDatatransferProgressListener(OnDatatransferProgressListener listener, Account account,
                OCFile file) {
            if (account == null || file == null || listener == null)
                return;
            String targetKey = buildRemoteName(account, file);
            if (mBoundListeners.get(targetKey) == listener) {
                mBoundListeners.remove(targetKey);
            }
        }

        @Override
        public void onTransferProgress(long progressRate, long totalTransferredSoFar, long totalToTransfer,
                String localFileName) {
            Set<Entry<String, UploadFileOperation>> uploads = mActiveUploads.entrySet();
            UploadFileOperation currentUpload = null;
            //unfortunately we do not have the remote upload path here, so search through all uploads.
            //however, this may lead to problems, if user uploads same file twice to different destinations.
            //this can only be fixed by replacing localFileName with remote path.
            for (Entry<String, UploadFileOperation> entry : uploads) {
                if(entry.getValue().getStoragePath().equals(localFileName)) {
                    if(currentUpload != null) {
                        Log_OC.e(TAG, "Found two current uploads with same remote path " + localFileName + ". Ignore.");
                        return;
                    }
                    currentUpload = entry.getValue();
                }
            }
            if (currentUpload == null) {
                Log_OC.e(TAG, "Found no current upload with remote path " + localFileName + ". Ignore.");
                return;
            }
            String key = buildRemoteName(currentUpload.getAccount(), currentUpload.getFile());
            OnDatatransferProgressListener boundListener = mBoundListeners.get(key);
            if (boundListener != null) {
                boundListener.onTransferProgress(progressRate, totalTransferredSoFar, totalToTransfer, localFileName);
            }
        }
    }

    /**
     * Upload worker. Performs the pending uploads in the order they were
     * requested.
     * 
     * Created with the Looper of a new thread, started in
     * {@link FileUploadService#onCreate()}.
     */
    private static class ServiceHandler extends Handler {
        // don't make it a final class, and don't remove the static ; lint will
        // warn about a possible memory leak
        FileUploadService mService;

        public ServiceHandler(Looper looper, FileUploadService service) {
            super(looper);
            if (service == null)
                throw new IllegalArgumentException("Received invalid NULL in parameter 'service'");
            mService = service;
        }

        @Override
        public void handleMessage(Message msg) {
            @SuppressWarnings("unchecked")
            AbstractList<UploadDbObject> requestedUploads = (AbstractList<UploadDbObject>) msg.obj;
            if (msg.obj != null) {
                Iterator<UploadDbObject> it = requestedUploads.iterator();
                while (it.hasNext()) {
                    UploadDbObject uploadObject = it.next();
                    mService.uploadFile(uploadObject);
                }
            }
            mService.stopSelf(msg.arg1);
        }
    }

    /**
     * Core upload method: sends the file(s) to upload. This function blocks until upload succeeded or failed.
     * 
     * @param uploadDbObject Key to access the upload to perform, contained in
     *            mPendingUploads
     */
    private void uploadFile(UploadDbObject uploadDbObject) {
        
        if(uploadDbObject.getUploadStatus() == UploadStatus.UPLOAD_SUCCEEDED) {
            Log_OC.w(TAG, "Already succeeded uploadObject was again scheduled for upload. Fix that!");
            return;
        }

        UploadFileOperation currentUpload = null;
        synchronized (mActiveUploads) {
            //How does this work? Is it thread-safe to set mCurrentUpload here?
            //What happens if other mCurrentUpload is currently in progress?
            //
            //It seems that upload does work, however the upload state is not set
            //back of the first upload when a second upload starts while first is
            //in progress (yellow up-arrow does not disappear of first upload)
            currentUpload = mActiveUploads.get(uploadDbObject.getRemotePath());
            
            //if upload not in progress, start it now
            if(currentUpload == null) {
                if (uploadDbObject.isUseWifiOnly()
                        && !InstantUploadBroadcastReceiver.isConnectedViaWiFi(getApplicationContext())) {
                    Log_OC.d(TAG, "Do not start upload because it is wifi-only.");
                    return;
                }
                
                if (!new File(uploadDbObject.getLocalPath()).exists()) {
                    mDb.updateUpload(uploadDbObject.getLocalPath(), UploadStatus.UPLOAD_FAILED_GIVE_UP,
                            new RemoteOperationResult(ResultCode.FILE_NOT_FOUND));
                    Log_OC.d(TAG, "Do not start upload because local file does not exist.");
                    return;
                }

                AccountManager aMgr = AccountManager.get(this);
                Account account = uploadDbObject.getAccount(getApplicationContext());
                String version = aMgr.getUserData(account, Constants.KEY_OC_VERSION);
                OwnCloudVersion ocv = new OwnCloudVersion(version);

                boolean chunked = FileUploadService.chunkedUploadIsSupported(ocv);
                String uploadKey = null;
                
                uploadKey = buildRemoteName(account, uploadDbObject.getRemotePath());
                OCFile file = obtainNewOCFileToUpload(uploadDbObject.getRemotePath(), uploadDbObject.getLocalPath(),
                        uploadDbObject.getMimeType());
                currentUpload = new UploadFileOperation(account, file, chunked, uploadDbObject.isForceOverwrite(),
                        uploadDbObject.getLocalAction(), getApplicationContext());
                if (uploadDbObject.isCreateRemoteFolder()) {
                    currentUpload.setRemoteFolderToBeCreated();
                }
                mActiveUploads.putIfAbsent(uploadKey, currentUpload); // Grants that
                // the file only upload once time

                currentUpload.addDatatransferProgressListener((FileUploaderBinder) mBinder);
            }
            
        }

        if (currentUpload != null) {

            notifyUploadStart(currentUpload);

            RemoteOperationResult uploadResult = null, grantResult = null;
            try {
                // / prepare client object to send requests to the ownCloud
                // server
                if (mUploadClient == null || !mLastAccount.equals(currentUpload.getAccount())) {
                    mLastAccount = currentUpload.getAccount();
                    mStorageManager = new FileDataStorageManager(mLastAccount, getContentResolver());
                    OwnCloudAccount ocAccount = new OwnCloudAccount(mLastAccount, this);
                    mUploadClient = OwnCloudClientManagerFactory.getDefaultSingleton().getClientFor(ocAccount, this);
                }

                // / check the existence of the parent folder for the file to
                // upload
                String remoteParentPath = new File(currentUpload.getRemotePath()).getParent();
                remoteParentPath = remoteParentPath.endsWith(OCFile.PATH_SEPARATOR) ? remoteParentPath
                        : remoteParentPath + OCFile.PATH_SEPARATOR;
                grantResult = grantFolderExistence(currentUpload, remoteParentPath);

                // / perform the upload
                if (grantResult.isSuccess()) {
                    OCFile parent = mStorageManager.getFileByPath(remoteParentPath);
                    currentUpload.getFile().setParentId(parent.getFileId());
                    uploadResult = currentUpload.execute(mUploadClient);
                    if (uploadResult.isSuccess()) {
                        saveUploadedFile(currentUpload);
                    }
                } else {
                    uploadResult = grantResult;
                }

            } catch (AccountsException e) {
                Log_OC.e(TAG, "Error while trying to get autorization for " + mLastAccount.name, e);
                uploadResult = new RemoteOperationResult(e);

            } catch (IOException e) {
                Log_OC.e(TAG, "Error while trying to get autorization for " + mLastAccount.name, e);
                uploadResult = new RemoteOperationResult(e);

            } finally {
                synchronized (mActiveUploads) {
                    mActiveUploads.remove(uploadDbObject);
                    Log_OC.i(TAG, "Remove CurrentUploadItem from pending upload Item Map.");
                }
                if (uploadResult.isException()) {
                    // enforce the creation of a new client object for next
                    // uploads; this grant that a new socket will
                    // be created in the future if the current exception is due
                    // to an abrupt lose of network connection
                    mUploadClient = null;
                }
            }

            // notify result
            notifyUploadResult(uploadResult, currentUpload);
            sendFinalBroadcast(currentUpload, uploadResult);            

        }

    }

    /**
     * Checks the existence of the folder where the current file will be
     * uploaded both in the remote server and in the local database.
     * 
     * If the upload is set to enforce the creation of the folder, the method
     * tries to create it both remote and locally.
     * 
     * @param pathToGrant Full remote path whose existence will be granted.
     * @return An {@link OCFile} instance corresponding to the folder where the
     *         file will be uploaded.
     */
    private RemoteOperationResult grantFolderExistence(UploadFileOperation currentUpload, String pathToGrant) {
        RemoteOperation operation = new ExistenceCheckRemoteOperation(pathToGrant, this, false);
        RemoteOperationResult result = operation.execute(mUploadClient);
        if (!result.isSuccess() && result.getCode() == ResultCode.FILE_NOT_FOUND
                && currentUpload.isRemoteFolderToBeCreated()) {
            SyncOperation syncOp = new CreateFolderOperation(pathToGrant, true);
            result = syncOp.execute(mUploadClient, mStorageManager);
        }
        if (result.isSuccess()) {
            OCFile parentDir = mStorageManager.getFileByPath(pathToGrant);
            if (parentDir == null) {
                parentDir = createLocalFolder(pathToGrant);
            }
            if (parentDir != null) {
                result = new RemoteOperationResult(ResultCode.OK);
            } else {
                result = new RemoteOperationResult(ResultCode.UNKNOWN_ERROR);
            }
        }
        return result;
    }

    private OCFile createLocalFolder(String remotePath) {
        String parentPath = new File(remotePath).getParent();
        parentPath = parentPath.endsWith(OCFile.PATH_SEPARATOR) ? parentPath : parentPath + OCFile.PATH_SEPARATOR;
        OCFile parent = mStorageManager.getFileByPath(parentPath);
        if (parent == null) {
            parent = createLocalFolder(parentPath);
        }
        if (parent != null) {
            OCFile createdFolder = new OCFile(remotePath);
            createdFolder.setMimetype("DIR");
            createdFolder.setParentId(parent.getFileId());
            mStorageManager.saveFile(createdFolder);
            return createdFolder;
        }
        return null;
    }

    /**
     * Saves a OC File after a successful upload.
     * 
     * A PROPFIND is necessary to keep the props in the local database
     * synchronized with the server, specially the modification time and Etag
     * (where available)
     * 
     * TODO refactor this ugly thing
     */
    private void saveUploadedFile(UploadFileOperation currentUpload) {
        OCFile file = currentUpload.getFile();
        if (file.fileExists()) {
            file = mStorageManager.getFileById(file.getFileId());
        }
        long syncDate = System.currentTimeMillis();
        file.setLastSyncDateForData(syncDate);

        // new PROPFIND to keep data consistent with server
        // in theory, should return the same we already have
        ReadRemoteFileOperation operation = new ReadRemoteFileOperation(currentUpload.getRemotePath());
        RemoteOperationResult result = operation.execute(mUploadClient);
        if (result.isSuccess()) {
            updateOCFile(file, (RemoteFile) result.getData().get(0));
            file.setLastSyncDateForProperties(syncDate);
        }

        // / maybe this would be better as part of UploadFileOperation... or
        // maybe all this method
        if (currentUpload.wasRenamed()) {
            OCFile oldFile = currentUpload.getOldFile();
            if (oldFile.fileExists()) {
                oldFile.setStoragePath(null);
                mStorageManager.saveFile(oldFile);

            } // else: it was just an automatic renaming due to a name
              // coincidence; nothing else is needed, the storagePath is right
              // in the instance returned by mCurrentUpload.getFile()
        }
        file.setNeedsUpdateThumbnail(true);
        mStorageManager.saveFile(file);
    }

    private void updateOCFile(OCFile file, RemoteFile remoteFile) {
        file.setCreationTimestamp(remoteFile.getCreationTimestamp());
        file.setFileLength(remoteFile.getLength());
        file.setMimetype(remoteFile.getMimeType());
        file.setModificationTimestamp(remoteFile.getModifiedTimestamp());
        file.setModificationTimestampAtLastSyncForData(remoteFile.getModifiedTimestamp());
        // file.setEtag(remoteFile.getEtag()); // TODO Etag, where available
        file.setRemoteId(remoteFile.getRemoteId());
    }


    private OCFile obtainNewOCFileToUpload(String remotePath, String localPath, String mimeType) {

        // MIME type
        if (mimeType == null || mimeType.length() <= 0) {
            try {
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        remotePath.substring(remotePath.lastIndexOf('.') + 1));
            } catch (IndexOutOfBoundsException e) {
                Log_OC.e(TAG, "Trying to find out MIME type of a file without extension: " + remotePath);
            }
        }
        if (mimeType == null) {
            mimeType = "application/octet-stream";
        }

        if (isPdfFileFromContentProviderWithoutExtension(localPath, mimeType)){
            remotePath += FILE_EXTENSION_PDF;
        }

        OCFile newFile = new OCFile(remotePath);
        newFile.setStoragePath(localPath);
        newFile.setLastSyncDateForProperties(0);
        newFile.setLastSyncDateForData(0);

        // size
        if (localPath != null && localPath.length() > 0) {
            File localFile = new File(localPath);
            newFile.setFileLength(localFile.length());
            newFile.setLastSyncDateForData(localFile.lastModified());
        } // don't worry about not assigning size, the problems with localPath
          // are checked when the UploadFileOperation instance is created


        newFile.setMimetype(mimeType);

        return newFile;
    }

    /**
     * Creates a status notification to show the upload progress
     * 
     * @param upload Upload operation starting.
     */
    private void notifyUploadStart(UploadFileOperation upload) {
        // / create status notification with a progress bar
        mNotificationBuilder = NotificationBuilderWithProgressBar.newNotificationBuilderWithProgressBar(this);
        mNotificationBuilder
                .setOngoing(true)
                .setSmallIcon(R.drawable.notification_icon)
                .setTicker(getString(R.string.uploader_upload_in_progress_ticker))
                .setContentTitle(getString(R.string.uploader_upload_in_progress_ticker))
                .setProgress(100, 0, false)
                .setContentText(
                        String.format(getString(R.string.uploader_upload_in_progress_content), 0, upload.getFileName()));

        // / includes a pending intent in the notification showing the details
        // view of the file
        Intent showDetailsIntent = new Intent(this, FileDisplayActivity.class);
        showDetailsIntent.putExtra(FileActivity.EXTRA_FILE, upload.getFile());
        showDetailsIntent.putExtra(FileActivity.EXTRA_ACCOUNT, upload.getAccount());
        showDetailsIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mNotificationBuilder.setContentIntent(PendingIntent.getActivity(this, (int) System.currentTimeMillis(),
                showDetailsIntent, 0));

        mNotificationManager.notify(R.string.uploader_upload_in_progress_ticker, mNotificationBuilder.build());
        
        mDb.updateUpload(upload.getOriginalStoragePath(), UploadStatus.UPLOAD_IN_PROGRESS, null);
    }

    /**
     * Updates the status notification with the result of an upload operation.
     * 
     * @param uploadResult Result of the upload operation.
     * @param upload Finished upload operation
     */
    private void notifyUploadResult(RemoteOperationResult uploadResult, UploadFileOperation upload) {
        Log_OC.d(TAG, "NotifyUploadResult with resultCode: " + uploadResult.getCode());
        // / cancelled operation or success -> silent removal of progress
        // notification
        mNotificationManager.cancel(R.string.uploader_upload_in_progress_ticker);

        // Show the result: success or fail notification
        if (!uploadResult.isCancelled()) {
            int tickerId = (uploadResult.isSuccess()) ? R.string.uploader_upload_succeeded_ticker
                    : R.string.uploader_upload_failed_ticker;

            String content = null;

            // check credentials error
            boolean needsToUpdateCredentials = (uploadResult.getCode() == ResultCode.UNAUTHORIZED || uploadResult
                    .isIdPRedirection());
            tickerId = (needsToUpdateCredentials) ? R.string.uploader_upload_failed_credentials_error : tickerId;

            mNotificationBuilder.setTicker(getString(tickerId)).setContentTitle(getString(tickerId))
                    .setAutoCancel(true).setOngoing(false).setProgress(0, 0, false);

            content = ErrorMessageAdapter.getErrorCauseMessage(uploadResult, upload, getResources());

            if (needsToUpdateCredentials) {
                // let the user update credentials with one click
                Intent updateAccountCredentials = new Intent(this, AuthenticatorActivity.class);
                updateAccountCredentials.putExtra(AuthenticatorActivity.EXTRA_ACCOUNT, upload.getAccount());
                updateAccountCredentials.putExtra(AuthenticatorActivity.EXTRA_ACTION,
                        AuthenticatorActivity.ACTION_UPDATE_EXPIRED_TOKEN);
                updateAccountCredentials.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                updateAccountCredentials.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                updateAccountCredentials.addFlags(Intent.FLAG_FROM_BACKGROUND);
                mNotificationBuilder.setContentIntent(PendingIntent.getActivity(this, (int) System.currentTimeMillis(),
                        updateAccountCredentials, PendingIntent.FLAG_ONE_SHOT));

                mUploadClient = null;
                // grant that future retries on the same account will get the
                // fresh credentials
                
            }

            mNotificationBuilder.setContentText(content);
            mNotificationManager.notify(tickerId, mNotificationBuilder.build());

            if (uploadResult.isSuccess()) {

               mDb.updateUpload(upload.getOriginalStoragePath(), UploadStatus.UPLOAD_SUCCEEDED, uploadResult);
               
                // remove success notification, with a delay of 2 seconds
                NotificationDelayer.cancelWithDelay(mNotificationManager, R.string.uploader_upload_succeeded_ticker,
                        2000);
            } else {
                // TODO: add other cases in which upload attempt is to be
                // abandoned.
                if (uploadResult.getCode() == ResultCode.QUOTA_EXCEEDED) {
                    mDb.updateUpload(upload.getOriginalStoragePath(),
                            UploadDbHandler.UploadStatus.UPLOAD_FAILED_GIVE_UP, uploadResult);
                } else {
                    mDb.updateUpload(upload.getOriginalStoragePath(), UploadStatus.UPLOAD_FAILED, uploadResult);
                }
            }
        } else {
            mDb.updateUpload(upload.getOriginalStoragePath(), UploadStatus.UPLOAD_FAILED, uploadResult);
        }
    }

    /**
     * Sends a broadcast in order to the interested activities can update their
     * view
     * 
     * @param upload Finished upload operation
     * @param uploadResult Result of the upload operation
     */
    private void sendFinalBroadcast(UploadFileOperation upload, RemoteOperationResult uploadResult) {
        Intent end = new Intent(getUploadFinishMessage());
        end.putExtra(EXTRA_REMOTE_PATH, upload.getRemotePath()); // real remote
                                                                 // path, after
                                                                 // possible
                                                                 // automatic
                                                                 // renaming
        if (upload.wasRenamed()) {
            end.putExtra(EXTRA_OLD_REMOTE_PATH, upload.getOldFile().getRemotePath());
        }
        end.putExtra(EXTRA_OLD_FILE_PATH, upload.getOriginalStoragePath());
        end.putExtra(ACCOUNT_NAME, upload.getAccount().name);
        end.putExtra(EXTRA_UPLOAD_RESULT, uploadResult.isSuccess());
        sendStickyBroadcast(end);
    }

    /**
     * Checks if content provider, using the content:// scheme, returns a file with mime-type 
     * 'application/pdf' but file has not extension
     * @param localPath
     * @param mimeType
     * @return true if is needed to add the pdf file extension to the file
     */
    private boolean isPdfFileFromContentProviderWithoutExtension(String localPath, String mimeType) {
        return localPath.startsWith(UriUtils.URI_CONTENT_SCHEME) && 
                mimeType.equals(MIME_TYPE_PDF) && 
                !localPath.endsWith(FILE_EXTENSION_PDF);
    }

}
