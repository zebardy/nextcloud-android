/**
 *   ownCloud Android client application
 *
 *   @author masensio
 *   Copyright (C) 2015 ownCloud Inc.
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

package com.owncloud.android.utils;

import android.accounts.Account;
import android.os.AsyncTask;

import com.owncloud.android.MainApp;
import com.owncloud.android.datamodel.FileDataStorageManager;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.lib.common.OwnCloudAccount;
import com.owncloud.android.lib.common.OwnCloudClient;
import com.owncloud.android.lib.common.OwnCloudClientManagerFactory;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.lib.resources.shares.OCShare;
import com.owncloud.android.operations.GetSharesForFileOperation;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Async Task to get the users and groups which a file is shared with
 */
public class GetShareWithUserAsyncTask extends AsyncTask<Object, Void, RemoteOperationResult> {

    private final String TAG = GetShareWithUserAsyncTask.class.getSimpleName();
    private final WeakReference<OnGetSharesWithUserTaskListener> mListener;
    private ArrayList<OCShare> mShares;

    public ArrayList<OCShare> getShares(){
        return mShares;
    }

    public GetShareWithUserAsyncTask(OnGetSharesWithUserTaskListener listener) {
        mListener = new WeakReference<OnGetSharesWithUserTaskListener>(listener);
    }

    @Override
    protected RemoteOperationResult doInBackground(Object... params) {

        RemoteOperationResult result = null;

        if (params != null && params.length == 3) {
            OCFile file = (OCFile) params[0];
            Account account = (Account) params[1];
            FileDataStorageManager fileDataStorageManager = (FileDataStorageManager) params[2];

            try {
                // Get shares request
                GetSharesForFileOperation operation =
                        new GetSharesForFileOperation(file.getRemotePath(), false, false);
                OwnCloudAccount ocAccount = new OwnCloudAccount(account,
                        MainApp.getAppContext());
                OwnCloudClient client = OwnCloudClientManagerFactory.getDefaultSingleton().
                        getClientFor(ocAccount, MainApp.getAppContext());
                result = operation.execute(client, fileDataStorageManager);

            } catch (Exception e) {
                result = new RemoteOperationResult(e);
                Log_OC.e(TAG, "Exception while getting shares", e);
            }
        } else {
            result = new RemoteOperationResult(RemoteOperationResult.ResultCode.UNKNOWN_ERROR);
        }

        return result;
    }

    @Override
    protected void onPostExecute(RemoteOperationResult result) {

        if (result!= null)
        {
            OnGetSharesWithUserTaskListener listener = mListener.get();
            if (listener!= null)
            {
                listener.onGetDataShareWithFinish(result);
            }
        }
    }

    /*
     * Interface to retrieve data from get shares task
     */
    public interface OnGetSharesWithUserTaskListener{

        void onGetDataShareWithFinish(RemoteOperationResult result);
    }
}
