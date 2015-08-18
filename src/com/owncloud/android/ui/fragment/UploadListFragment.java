/**
 *   ownCloud Android client application
 *
 *   @author LukeOwncloud
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
package com.owncloud.android.ui.fragment;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.ExpandableListView;
import android.widget.ExpandableListView.ExpandableListContextMenuInfo;
import android.widget.ListView;
import android.widget.TextView;

import com.owncloud.android.R;
import com.owncloud.android.db.UploadDbObject;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.ui.activity.FileActivity;
import com.owncloud.android.ui.activity.FileDisplayActivity;
import com.owncloud.android.ui.adapter.ExpandableUploadListAdapter;
import com.owncloud.android.utils.UploadUtils;

/**
 * A Fragment that lists all files and folders in a given LOCAL path.
 * 
 */
public class UploadListFragment extends ExpandableListFragment {
    static private String TAG = UploadListFragment.class.getSimpleName();

    /**
     * Reference to the Activity which this fragment is attached to. For
     * callbacks
     */
    private UploadListFragment.ContainerActivity mContainerActivity;

    ExpandableUploadListAdapter mAdapter;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = super.onCreateView(inflater, container, savedInstanceState);
        getListView().setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        setMessageForEmptyList(getString(R.string.upload_list_empty));
        setOnRefreshListener(this);
        return v;
    }
    
    @Override
    public void onRefresh() {
        mAdapter.notifyDataSetChanged();        
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            mContainerActivity = (ContainerActivity) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString() + " must implement "
                    + UploadListFragment.ContainerActivity.class.getSimpleName());
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        Log_OC.d(TAG, "onActivityCreated() start");
        super.onActivityCreated(savedInstanceState);
        mAdapter = new ExpandableUploadListAdapter(getActivity());
        setListAdapter(mAdapter);
        mAdapter.setFileActivity(((FileActivity) getActivity()));
        
        registerForContextMenu(getListView());
        getListView().setOnCreateContextMenuListener(this);
    }

    @Override
    public boolean onChildClick(ExpandableListView parent, View v, int groupPosition, int childPosition, long id) {
        boolean handled = false;
        UploadDbObject uploadDbObject = (UploadDbObject) mAdapter.getChild(groupPosition, childPosition);
        if (uploadDbObject != null) {
            // notify the click to container Activity
            handled = mContainerActivity.onUploadItemClick(uploadDbObject);
        } else {
            Log_OC.w(TAG, "Null object in ListAdapter!!");
        }
        return handled;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        MenuInflater inflater = getActivity().getMenuInflater();
        inflater.inflate(R.menu.upload_actions_menu, menu);
        
        ExpandableListContextMenuInfo info = (ExpandableListContextMenuInfo) menuInfo;  
        int childPosition = ExpandableListView.getPackedPositionChild(info.packedPosition);
        int groupPosition = ExpandableListView.getPackedPositionGroup(info.packedPosition);
        UploadDbObject uploadFile = (UploadDbObject) mAdapter.getChild(groupPosition, childPosition);
        if (UploadUtils.userCanCancelUpload(uploadFile)) {
            MenuItem item = menu.findItem(R.id.action_remove_upload);
            if (item != null) {
                item.setVisible(false);
                item.setEnabled(false);
            }
        } else {
            MenuItem item = menu.findItem(R.id.action_cancel_upload);
            if (item != null) {
                item.setVisible(false);
                item.setEnabled(false);
            }
        }
        if (!UploadUtils.userCanRetryUpload(uploadFile)) {
            MenuItem item = menu.findItem(R.id.action_retry_upload);
            if (item != null) {
                item.setVisible(false);
                item.setEnabled(false);
            }
        }
    }
    
    @Override
    public boolean onContextItemSelected (MenuItem item) {
        ExpandableListContextMenuInfo info = (ExpandableListContextMenuInfo) item.getMenuInfo();  
        int childPosition = ExpandableListView.getPackedPositionChild(info.packedPosition);
        int groupPosition = ExpandableListView.getPackedPositionGroup(info.packedPosition);
        UploadDbObject uploadFile = (UploadDbObject) mAdapter.getChild(groupPosition, childPosition);
        switch (item.getItemId()) {
        case R.id.action_cancel_upload:
            ((FileActivity) getActivity()).getFileOperationsHelper().cancelTransference(uploadFile.getOCFile());
            return true;
        case R.id.action_remove_upload: {
            ((FileActivity) getActivity()).getFileOperationsHelper().removeUploadFromList(uploadFile);
            return true;
        }case R.id.action_retry_upload: {
            ((FileActivity) getActivity()).getFileOperationsHelper().retryUpload(uploadFile);
            return true;
        }case R.id.action_see_details: {
            Intent showDetailsIntent = new Intent(getActivity(), FileDisplayActivity.class);
            showDetailsIntent.putExtra(FileActivity.EXTRA_FILE, (Parcelable) uploadFile.getOCFile());
            showDetailsIntent.putExtra(FileActivity.EXTRA_ACCOUNT, uploadFile.getAccount(getActivity()));
            startActivity(showDetailsIntent);
            return true;
        }
        case R.id.action_open_file_with: {
            ((FileActivity) getActivity()).getFileOperationsHelper().openFile(uploadFile.getOCFile());
            return true;
        }
        default:
            return super.onContextItemSelected(item);
        }
    }

    /**
     * Interface to implement by any Activity that includes some instance of
     * UploadListFragment
     * 
     * @author LukeOwncloud
     */
    public interface ContainerActivity {

        /**
         * Callback method invoked when an upload item is clicked by the user on
         * the upload list
         * 
         * @param file
         * @return return true if click was handled.
         */
        public boolean onUploadItemClick(UploadDbObject file);

    }

}