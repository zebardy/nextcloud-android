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

package com.owncloud.android.ui.activity;

import com.actionbarsherlock.app.ActionBar;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.db.UploadDbObject;
import com.owncloud.android.files.services.FileUploadService;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.ui.dialog.ConflictsResolveDialog;
import com.owncloud.android.ui.dialog.ConflictsResolveDialog.Decision;
import com.owncloud.android.ui.dialog.ConflictsResolveDialog.OnConflictDecisionMadeListener;
import com.owncloud.android.utils.DisplayUtils;

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;

/**
 * Wrapper activity which will be launched if keep-in-sync file will be modified by external
 * application. 
 * 
 * @author Bartek Przybylski
 * @author David A. Velasco
 */
public class ConflictsResolveActivity extends FileActivity implements OnConflictDecisionMadeListener {

    private String TAG = ConflictsResolveActivity.class.getSimpleName();
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActionBar actionBar = getSupportActionBar();
        actionBar.setIcon(DisplayUtils.getSeasonalIconId());
    }

    @Override
    public void conflictDecisionMade(Decision decision) {
        Intent i = new Intent(getApplicationContext(), FileUploadService.class);
        
        switch (decision) {
            case CANCEL:
                finish();
                return;
            case OVERWRITE:
                i.putExtra(FileUploadService.KEY_FORCE_OVERWRITE, true);
                break;
            case KEEP_BOTH:
                i.putExtra(FileUploadService.KEY_LOCAL_BEHAVIOUR, FileUploadService.LocalBehaviour.LOCAL_BEHAVIOUR_MOVE);
                break;
            default:
                Log_OC.wtf(TAG, "Unhandled conflict decision " + decision);
                return;
        }
        i.putExtra(FileUploadService.KEY_ACCOUNT, getAccount());
        i.putExtra(FileUploadService.KEY_FILE, (Parcelable)getFile());
        i.putExtra(FileUploadService.KEY_UPLOAD_TYPE, FileUploadService.UploadSingleMulti.UPLOAD_SINGLE_FILE);
        
        startService(i);
        finish();
    }

    @Override
    protected void onAccountSet(boolean stateWasRecovered) {
        super.onAccountSet(stateWasRecovered);
        if (getAccount() != null) {
            OCFile file = getFile();
            if (getFile() == null) {
                Log_OC.e(TAG, "No conflictive file received");
                finish();
            } else {
                /// Check whether the 'main' OCFile handled by the Activity is contained in the current Account
                file = getStorageManager().getFileByPath(file.getRemotePath());   // file = null if not in the current Account
                if (file != null) {
                    setFile(file);
                    ConflictsResolveDialog d = ConflictsResolveDialog.newInstance(file.getRemotePath(), this);
                    d.showDialog(this);
                    
                } else {
                    // account was changed to a different one - just finish
                    finish();
                }
            }
            
        } else {
            finish();
        }
        
    }
}
