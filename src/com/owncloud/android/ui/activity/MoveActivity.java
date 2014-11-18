/* ownCloud Android client application
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

package com.owncloud.android.ui.activity;

import android.content.Intent;

import android.view.View;
import android.view.View.OnClickListener;



import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.ui.fragment.FileFragment;


public class MoveActivity extends FolderPickerActivity implements FileFragment.ContainerActivity, 
    OnClickListener, OnEnforceableRefreshListener {

    public static final int RESULT_OK_AND_MOVE = 1;


    @Override
    public void onClick(View v) {
        if (v == mCancelBtn) {
            finish();
        } else if (v == mChooseBtn) {
            Intent i = getIntent();
            OCFile targetFile = (OCFile) i.getParcelableExtra(MoveActivity.EXTRA_TARGET_FILE);

            Intent data = new Intent();
            data.putExtra(EXTRA_CURRENT_FOLDER, getCurrentFolder());
            data.putExtra(EXTRA_TARGET_FILE, targetFile);
            setResult(RESULT_OK_AND_MOVE, data);
            finish();
        }
    }
}
