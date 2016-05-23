/**
 *   ownCloud Android client application
 *
 *   Copyright (C) 2016 ownCloud Inc.
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
package com.owncloud.android.ui.helpers;

import android.accounts.Account;
import android.app.Activity;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Parcelable;

import com.owncloud.android.R;
import com.owncloud.android.db.PreferenceManager;
import com.owncloud.android.files.services.FileUploader;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.operations.UploadFileOperation;
import com.owncloud.android.ui.activity.ReceiveExternalFilesActivity;
import com.owncloud.android.ui.asynctasks.CopyAndUploadContentUrisTask;
import com.owncloud.android.utils.DisplayUtils;
import com.owncloud.android.utils.UriUtils;

import java.util.ArrayList;
import java.util.List;


public class UriUploader implements
        CopyAndUploadContentUrisTask.OnCopyTmpFilesTaskListener  {

    private final String TAG = UriUploader.class.getSimpleName();

    private Activity mActivity;
    private ArrayList<Parcelable> mUrisToUpload;

    private int mBehaviour;

    private String mUploadPath;
    private Account mAccount;
    private ContentResolver mContentResolver;

    private UriUploadCode mCode = UriUploadCode.OK;

    public enum UriUploadCode {
        OK,
        ERROR_UNKNOWN,
        ERROR_NO_FILE_TO_UPLOAD,
        ERROR_READ_PERMISSION_NOT_GRANTED
    }

    public UriUploader(
            Activity context,
            ArrayList<Parcelable> uris,
            String uploadPath,
            Account account,
            ContentResolver contentResolver,
            int behaviour
    ) {
        mActivity = context;
        mUrisToUpload = uris;
        mUploadPath = uploadPath;
        mAccount = account;
        mContentResolver = contentResolver;
        mBehaviour = behaviour;
    }

    public void setBehaviour(int behaviour) {
        this.mBehaviour = behaviour;
    }

    public UriUploadCode uploadUris() {

        try {

            List<Uri> contentUris = new ArrayList<>();
            List<String> contentRemotePaths = new ArrayList<>();

            int schemeFileCounter = 0;

            for (Parcelable sourceStream : mUrisToUpload) {
                Uri sourceUri = (Uri) sourceStream;
                if (sourceUri != null) {
                    String displayName = UriUtils.getDisplayNameForUri(sourceUri, mActivity);
                    if (displayName == null) {
                        displayName = generateDiplayName();
                    }
                    String remotePath = mUploadPath + displayName;

                    if (ContentResolver.SCHEME_CONTENT.equals(sourceUri.getScheme())) {
                        contentUris.add(sourceUri);
                        contentRemotePaths.add(remotePath);

                    } else if (ContentResolver.SCHEME_FILE.equals(sourceUri.getScheme())) {
                        /// file: uris should point to a local file, should be safe let FileUploader handle them
                        requestUpload(sourceUri.getPath(), remotePath);
                        schemeFileCounter++;
                    }
                }
            }

            if (!contentUris.isEmpty()) {
                /// content: uris will be copied to temporary files before calling {@link FileUploader}
                copyThenUpload(contentUris.toArray(new Uri[contentUris.size()]),
                        contentRemotePaths.toArray(new String[contentRemotePaths.size()]));

            } else if (schemeFileCounter == 0) {
                mCode = UriUploadCode.ERROR_NO_FILE_TO_UPLOAD;

            }

        } catch (SecurityException e) {
            mCode = UriUploadCode.ERROR_READ_PERMISSION_NOT_GRANTED;
            Log_OC.e(TAG, "Permissions fail", e);

        } catch (Exception e) {
            mCode = UriUploadCode.ERROR_UNKNOWN;
            Log_OC.e(TAG, "Unexpted error", e);

        } finally {
            // Save the path to shared preferences; even if upload is not possible, user chose the folder
            PreferenceManager.setLastUploadPath(mUploadPath, mActivity);
        }
        return mCode;
    }

    private String generateDiplayName() {
        return mActivity.getString(R.string.common_unknown) +
                "-" + DisplayUtils.unixTimeToHumanReadable(System.currentTimeMillis());
    }

    /**
     * Requests the upload of a file in the local file system to {@link FileUploader} service.
     *
     * The original file will be left in its original location, and will not be duplicated.
     * As a side effect, the user will see the file as not uploaded when accesses to the OC app.
     * This is considered as acceptable, since when a file is shared from another app to OC,
     * the usual workflow will go back to the original app.
     *
     * @param localPath     Absolute path in the local file system to the file to upload.
     * @param remotePath    Absolute path in the current OC account to set to the uploaded file.
     */
    private void requestUpload(String localPath, String remotePath) {
        FileUploader.UploadRequester requester = new FileUploader.UploadRequester();
        requester.uploadNewFile(
                mActivity,
                mAccount,
                localPath,
                remotePath,
                mBehaviour,
                null,       // MIME type will be detected from file name
                false,      // do not create parent folder if not existent
                UploadFileOperation.CREATED_BY_USER
        );
    }

    /**
     *
     * @param sourceUris        Array of content:// URIs to the files to upload
     * @param remotePaths       Array of absolute paths to set to the uploaded files
     */
    private void copyThenUpload(Uri[] sourceUris, String[] remotePaths) {
        if (mActivity instanceof ReceiveExternalFilesActivity) {
            ((ReceiveExternalFilesActivity) mActivity).showWaitingCopyDialog();
        }

        CopyAndUploadContentUrisTask copyTask = new CopyAndUploadContentUrisTask(this, mActivity);

        copyTask.execute(
                CopyAndUploadContentUrisTask.makeParamsToExecute(
                        mAccount,
                        sourceUris,
                        remotePaths,
                        mContentResolver
                )
        );
    }

    /**
     * Process the result of CopyAndUploadContentUrisTask
     */
    @Override
    public void onTmpFilesCopied(RemoteOperationResult.ResultCode result) {
        if (mActivity instanceof ReceiveExternalFilesActivity) {
            ((ReceiveExternalFilesActivity) mActivity).dismissWaitingCopyDialog();
            mActivity.finish();
        }
    }
}
