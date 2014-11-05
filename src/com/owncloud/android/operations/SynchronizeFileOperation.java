/* ownCloud Android client application
 *   Copyright (C) 2012 Bartek Przybylski
 *   Copyright (C) 2012-2014 ownCloud Inc.
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

package com.owncloud.android.operations;

import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.files.services.FileDownloader;
import com.owncloud.android.files.services.FileUploadService;
import com.owncloud.android.lib.common.OwnCloudClient;
import com.owncloud.android.lib.resources.files.RemoteFile;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.common.operations.RemoteOperationResult.ResultCode;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.lib.resources.files.ReadRemoteFileOperation;
import com.owncloud.android.operations.common.SyncOperation;
import com.owncloud.android.utils.FileStorageUtils;

import android.accounts.Account;
import android.content.Context;
import android.content.Intent;

/**
 * Remote operation performing the read of remote file in the ownCloud server.
 * 
 * @author David A. Velasco
 * @author masensio
 */

public class SynchronizeFileOperation extends SyncOperation {

    private String TAG = SynchronizeFileOperation.class.getSimpleName();
    
    private OCFile mLocalFile;
    private String mRemotePath;
    private OCFile mServerFile;
    private Account mAccount;
    private boolean mSyncFileContents;
    private Context mContext;
    
    private boolean mTransferWasRequested = false;

    
    /**
     * Constructor.
     * 
     * Uses remotePath to retrieve all the data in local cache and remote server when the operation
     * is executed, instead of reusing {@link OCFile} instances.
     * 
     * @param 
     * @param account               ownCloud account holding the file.
     * @param syncFileContents      When 'true', transference of data will be started by the 
     *                              operation if needed and no conflict is detected.
     * @param context               Android context; needed to start transfers.
     */
    public SynchronizeFileOperation(
            String remotePath,  
            Account account, 
            boolean syncFileContents,
            Context context) {
        
        mRemotePath = remotePath;
        mLocalFile = null;
        mServerFile = null;
        mAccount = account;
        mSyncFileContents = syncFileContents;
        mContext = context;
    }

    
    /**
     * Constructor allowing to reuse {@link OCFile} instances just queried from cache or network.
     * 
     * Useful for folder / account synchronizations.
     * 
     * @param localFile             Data of file currently hold in device cache. MUSTN't be null.
     * @param serverFile            Data of file just retrieved from network. If null, will be
     *                              retrieved from network by the operation when executed.
     * @param account               ownCloud account holding the file.
     * @param syncFileContents      When 'true', transference of data will be started by the 
     *                              operation if needed and no conflict is detected.
     * @param context               Android context; needed to start transfers.
     */
    public SynchronizeFileOperation(
            OCFile localFile,
            OCFile serverFile, 
            Account account, 
            boolean syncFileContents,
            Context context) {
        
        mLocalFile = localFile;
        mServerFile = serverFile;
        mRemotePath = localFile.getRemotePath();
        mAccount = account;
        mSyncFileContents = syncFileContents;
        mContext = context;
    }
    

    @Override
    protected RemoteOperationResult run(OwnCloudClient client) {

        RemoteOperationResult result = null;
        mTransferWasRequested = false;
        
        if (mLocalFile == null) {
            // Get local file from the DB
            mLocalFile = getStorageManager().getFileByPath(mRemotePath);
        }
        
        if (!mLocalFile.isDown()) {
            /// easy decision
            requestForDownload(mLocalFile);
            result = new RemoteOperationResult(ResultCode.OK);

        } else {
            /// local copy in the device -> need to think a bit more before do anything

            if (mServerFile == null) {
                ReadRemoteFileOperation operation = new ReadRemoteFileOperation(mRemotePath);
                result = operation.execute(client);
                if (result.isSuccess()){
                    mServerFile = FileStorageUtils.fillOCFile((RemoteFile) result.getData().get(0));
                    mServerFile.setLastSyncDateForProperties(System.currentTimeMillis());
                }
            }

            if (mServerFile != null) {   

                /// check changes in server and local file
                boolean serverChanged = false;
                /* time for eTag is coming, but not yet
                    if (mServerFile.getEtag() != null) {
                        serverChanged = (!mServerFile.getEtag().equals(mLocalFile.getEtag()));   // TODO could this be dangerous when the user upgrades the server from non-tagged to tagged?
                    } else { */
                // server without etags
                serverChanged = (mServerFile.getModificationTimestamp() != mLocalFile.getModificationTimestampAtLastSyncForData());
                //}
                boolean localChanged = (mLocalFile.getLocalModificationTimestamp() > mLocalFile.getLastSyncDateForData());
                // TODO this will be always true after the app is upgraded to database version 2; will result in unnecessary uploads

                /// decide action to perform depending upon changes
                //if (!mLocalFile.getEtag().isEmpty() && localChanged && serverChanged) {
                if (localChanged && serverChanged) {
                    result = new RemoteOperationResult(ResultCode.SYNC_CONFLICT);

                } else if (localChanged) {
                    if (mSyncFileContents) {
                        requestForUpload(mLocalFile);
                        // the local update of file properties will be done by the FileUploader service when the upload finishes
                    } else {
                        // NOTHING TO DO HERE: updating the properties of the file in the server without uploading the contents would be stupid; 
                        // So, an instance of SynchronizeFileOperation created with syncFileContents == false is completely useless when we suspect
                        // that an upload is necessary (for instance, in FileObserverService).
                    }
                    result = new RemoteOperationResult(ResultCode.OK);

                } else if (serverChanged) {
                    mLocalFile.setRemoteId(mServerFile.getRemoteId());
                    
                    if (mSyncFileContents) {
                        requestForDownload(mLocalFile); // local, not server; we won't to keep the value of keepInSync!
                        // the update of local data will be done later by the FileUploader service when the upload finishes
                    } else {
                        // TODO CHECK: is this really useful in some point in the code?
                        mServerFile.setKeepInSync(mLocalFile.keepInSync());
                        mServerFile.setLastSyncDateForData(mLocalFile.getLastSyncDateForData());
                        mServerFile.setStoragePath(mLocalFile.getStoragePath());
                        mServerFile.setParentId(mLocalFile.getParentId());
                        getStorageManager().saveFile(mServerFile);

                    }
                    result = new RemoteOperationResult(ResultCode.OK);

                } else {
                    // nothing changed, nothing to do
                    result = new RemoteOperationResult(ResultCode.OK);
                }

            } 

        }

        Log_OC.i(TAG, "Synchronizing " + mAccount.name + ", file " + mLocalFile.getRemotePath() + ": " + result.getLogMessage());

        return result;
    }

    
    /**
     * Requests for an upload to the FileUploader service
     * 
     * @param file     OCFile object representing the file to upload
     */
    private void requestForUpload(OCFile file) {
        Intent i = new Intent(mContext, FileUploadService.class);
        i.putExtra(FileUploadService.KEY_ACCOUNT, mAccount);
        i.putExtra(FileUploadService.KEY_FILE, file);
        /*i.putExtra(FileUploader.KEY_REMOTE_FILE, mRemotePath);    // doing this we would lose the value of keepInSync in the road, and maybe it's not updated in the database when the FileUploader service gets it!  
        i.putExtra(FileUploader.KEY_LOCAL_FILE, localFile.getStoragePath());*/
        i.putExtra(FileUploadService.KEY_UPLOAD_TYPE, FileUploadService.UploadSingleMulti.UPLOAD_SINGLE_FILE);
        i.putExtra(FileUploadService.KEY_FORCE_OVERWRITE, true);
        mContext.startService(i);
        mTransferWasRequested = true;
    }


    /**
     * Requests for a download to the FileDownloader service
     * 
     * @param file     OCFile object representing the file to download
     */
    private void requestForDownload(OCFile file) {
        Intent i = new Intent(mContext, FileDownloader.class);
        i.putExtra(FileDownloader.EXTRA_ACCOUNT, mAccount);
        i.putExtra(FileDownloader.EXTRA_FILE, file);
        mContext.startService(i);
        mTransferWasRequested = true;
    }


    public boolean transferWasRequested() {
        return mTransferWasRequested;
    }


    public OCFile getLocalFile() {
        return mLocalFile;
    }

}
