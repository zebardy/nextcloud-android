/**
 * ownCloud Android client application
 *
 * @author LukeOwncloud
 * @author masensio
 * Copyright (C) 2015 ownCloud Inc.
 * <p/>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 * <p/>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p/>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.owncloud.android.ui.adapter;

import android.content.Context;
import android.content.Intent;
import android.database.DataSetObserver;
import android.graphics.Bitmap;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.ExpandableListView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.owncloud.android.R;
import com.owncloud.android.authentication.AuthenticatorActivity;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.datamodel.ThumbnailsCacheManager;
import com.owncloud.android.datamodel.UploadsStorageManager;
import com.owncloud.android.datamodel.UploadsStorageManager.UploadStatus;
import com.owncloud.android.db.OCUpload;
import com.owncloud.android.files.services.FileUploader;
import com.owncloud.android.lib.common.network.OnDatatransferProgressListener;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.ui.activity.FileActivity;
import com.owncloud.android.utils.DisplayUtils;
import com.owncloud.android.utils.MimetypeIconUtil;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Observable;
import java.util.Observer;

/**
 * This Adapter populates a ListView with following types of uploads: pending,
 * active, completed. Filtering possible.
 *
 */
public class ExpandableUploadListAdapter extends BaseExpandableListAdapter implements Observer {

    private static final String TAG = ExpandableUploadListAdapter.class.getSimpleName();
    private FileActivity mParentActivity;

    private UploadsStorageManager mUploadsStorageManager;

    public ProgressListener mProgressListener;

    interface Refresh {
        public void refresh();
    }

    abstract class UploadGroup implements Refresh {
        OCUpload[] items;
        String name;

        public UploadGroup(String groupName) {
            this.name = groupName;
            items = new OCUpload[0];
        }

        public String getGroupName() {
            return name;
        }

        public Comparator<OCUpload> comparator = new Comparator<OCUpload>() {
            @Override
            public int compare(OCUpload lhs, OCUpload rhs) {
                return compareUploadId(lhs, rhs);
            }

            private int compareUploadId(OCUpload lsh, OCUpload rsh) {
                return Long.valueOf(lsh.getUploadId()).compareTo(rsh.getUploadId());
            }

            private int compareUpdateTime(OCUpload lhs, OCUpload rhs) {
                long lLastModified = new File(lhs.getLocalPath()).lastModified();
                long rLastModified = new File(rhs.getLocalPath()).lastModified();
                return Long.valueOf(rLastModified).compareTo(lLastModified);
            }
        };

        abstract public int getGroupIcon();
    }

    private UploadGroup[] mUploadGroups = null;
    private final int MAX_NUM_UPLOADS_SHOWN = 30;

    public ExpandableUploadListAdapter(FileActivity parentActivity) {
        Log_OC.d(TAG, "ExpandableUploadListAdapter");
        mParentActivity = parentActivity;
        mUploadsStorageManager = new UploadsStorageManager(mParentActivity.getContentResolver());
        mUploadGroups = new UploadGroup[3];
        mUploadGroups[0] = new UploadGroup(mParentActivity.getString(R.string.uploads_view_group_current_uploads)) {
            @Override
            public void refresh() {
                items = mUploadsStorageManager.getCurrentAndPendingUploads();
                Arrays.sort(items, comparator);
                items = trimToMaxLength(items);
            }

            @Override
            public int getGroupIcon() {
                return R.drawable.upload_in_progress;
            }
        };
        mUploadGroups[1] = new UploadGroup(mParentActivity.getString(R.string.uploads_view_group_failed_uploads)) {
            @Override
            public void refresh() {
                items = mUploadsStorageManager.getFailedUploads();
                Arrays.sort(items, comparator);
                items = trimToMaxLength(items);
            }

            @Override
            public int getGroupIcon() {
                return R.drawable.upload_failed;
            }

        };
        mUploadGroups[2] = new UploadGroup(mParentActivity.getString(R.string.uploads_view_group_finished_uploads)) {
            @Override
            public void refresh() {
                items = mUploadsStorageManager.getFinishedUploads();
                Arrays.sort(items, comparator);
                items = trimToMaxLength(items);
            }

            @Override
            public int getGroupIcon() {
                return R.drawable.upload_finished;
            }

        };
        loadUploadItemsFromDb();
    }


    private OCUpload[] trimToMaxLength(OCUpload[] items) {
        if (items.length > 30) {
            OCUpload[] arrayTrim = new OCUpload[30];

            for (int i = 0; i < MAX_NUM_UPLOADS_SHOWN; i++) {
                arrayTrim[i] = items[i];
            }
            return arrayTrim;

        } else {
            return items;
        }
    }

    @Override
    public void registerDataSetObserver(DataSetObserver observer) {
        super.registerDataSetObserver(observer);
        mUploadsStorageManager.addObserver(this);
        Log_OC.d(TAG, "registerDataSetObserver");
    }

    @Override
    public void unregisterDataSetObserver(DataSetObserver observer) {
        super.unregisterDataSetObserver(observer);
        mUploadsStorageManager.deleteObserver(this);
        Log_OC.d(TAG, "unregisterDataSetObserver");
    }

    @Override
    public boolean areAllItemsEnabled() {
        return true;
    }

    private View getView(OCUpload[] uploadsItems, int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            LayoutInflater inflator =
                    (LayoutInflater) mParentActivity.getSystemService(
                            Context.LAYOUT_INFLATER_SERVICE
                    );
            view = inflator.inflate(R.layout.upload_list_item, null);
        }


        if (uploadsItems != null && uploadsItems.length > position) {
            final OCUpload upload = uploadsItems[position];

            // local file name
            TextView fileTextView = (TextView) view.findViewById(R.id.upload_name);
            File localFile = new File(upload.getLocalPath());
            String fileName = localFile.getName();
            if (fileName.length() == 0) {
                fileName = File.separator;
            }
            fileTextView.setText(fileName);

            // remote path to parent folder
            TextView pathTextView = (TextView) view.findViewById(R.id.upload_local_path);
            String remoteParentPath = upload.getRemotePath();
            remoteParentPath = new File(remoteParentPath).getParent();
            pathTextView.setText(mParentActivity.getString(R.string.app_name) + remoteParentPath);

            // file size
            TextView fileSizeTextView = (TextView) view.findViewById(R.id.upload_file_size);
            fileSizeTextView.setText(DisplayUtils.bytesToHumanReadable(localFile.length()) + ", ");

            //* upload date
            TextView uploadDateTextView = (TextView) view.findViewById(R.id.upload_date);
            long updateTime = (new File(upload.getLocalPath())).lastModified();
            CharSequence dateString = DisplayUtils.getRelativeDateTimeString(
                    mParentActivity,
                    updateTime,
                    DateUtils.SECOND_IN_MILLIS,
                    DateUtils.WEEK_IN_MILLIS,
                    0
            );
            uploadDateTextView.setText(dateString);

            TextView accountNameTextView = (TextView) view.findViewById(R.id.upload_account);
            accountNameTextView.setText(upload.getAccountName());

            TextView statusTextView = (TextView) view.findViewById(R.id.upload_status);
            String status;

            // Reset fields visibility
            uploadDateTextView.setVisibility(View.VISIBLE);
            pathTextView.setVisibility(View.VISIBLE);
            fileSizeTextView.setVisibility(View.VISIBLE);
            accountNameTextView.setVisibility(View.VISIBLE);
            statusTextView.setVisibility(View.VISIBLE);

            switch (upload.getUploadStatus()) {
                case UPLOAD_IN_PROGRESS:
                    status = mParentActivity.getString(R.string.uploader_upload_in_progress_ticker);
                    ProgressBar progressBar = (ProgressBar) view.findViewById(R.id.upload_progress_bar);
                    progressBar.setProgress(0);
                    progressBar.setVisibility(View.VISIBLE);
                    mProgressListener = new ProgressListener(progressBar);
                    if (mParentActivity.getFileUploaderBinder() != null) {
                        mParentActivity.getFileUploaderBinder().addDatatransferProgressListener(
                                mProgressListener,
                                mParentActivity.getAccount(),
                                upload
                        );
                    } else {
                        Log_OC.e(TAG, "UploadBinder == null. It should have been created on creating mParentActivity"
                                + " which inherits from FileActivity. Fix that!");
                        Log_OC.e(TAG, "PENDING BINDING for upload = " + upload.getLocalPath());
                    }
                    uploadDateTextView.setVisibility(View.GONE);
                    pathTextView.setVisibility(View.GONE);
                    fileSizeTextView.setVisibility(View.GONE);
                    accountNameTextView.setVisibility(View.INVISIBLE);
                    break;
                case UPLOAD_FAILED:
                    uploadDateTextView.setVisibility(View.GONE);
                    if (upload.getLastResult() != null) {
                        switch (upload.getLastResult()) {
                            case CREDENTIAL_ERROR:
                                status = mParentActivity.getString(
                                        R.string.uploads_view_upload_status_failed_credentials_error);

                                view.setOnClickListener(new OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        // let the user update credentials with one click
                                        Intent updateAccountCredentials = new Intent(mParentActivity,
                                                AuthenticatorActivity.class);
                                        updateAccountCredentials.putExtra(
                                                AuthenticatorActivity.EXTRA_ACCOUNT, upload.getAccount
                                                        (mParentActivity));
                                        updateAccountCredentials.putExtra(
                                                AuthenticatorActivity.EXTRA_ACTION,
                                                AuthenticatorActivity.ACTION_UPDATE_EXPIRED_TOKEN);
                                        updateAccountCredentials.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                                        updateAccountCredentials.addFlags(Intent.FLAG_FROM_BACKGROUND);
                                        mParentActivity.startActivity(updateAccountCredentials);
                                    }
                                });
                                break;
                            case FOLDER_ERROR:
                                status = mParentActivity.getString(
                                        R.string.uploads_view_upload_status_failed_folder_error);
                                break;
                            case FILE_ERROR:
                                status = mParentActivity.getString(
                                        R.string.uploads_view_upload_status_failed_file_error);
                                break;
                            case PRIVILEDGES_ERROR:
                                status = mParentActivity.getString(
                                        R.string.uploads_view_upload_status_failed_permission_error);
                                break;
                            case NETWORK_CONNECTION:
                                status = mParentActivity.getString(R.string.uploads_view_upload_status_failed_connection_error);
                                break;
                            default:
                                status = mParentActivity.getString(
                                        R.string.uploads_view_upload_status_failed) + ": "
                                        + upload.getLastResult().toString();
                                break;
                        }
                    } else {
                        status = mParentActivity.getString(
                                R.string.uploads_view_upload_status_failed);
                        ;
                    }

                    String laterReason = upload.getUploadLaterReason(mParentActivity);
                    if (laterReason != null) {
                        //Upload failed once but is delayed now, show reason.
                        status = laterReason;
                    }
                    break;
//                case UPLOAD_FAILED:
//                    if (upload.getLastResult() == UploadResult.NETWORK_CONNECTION) {
//                        status = mParentActivity.getString(R.string.uploads_view_upload_status_failed_connection_error);
//                    } else {
//                        status = mParentActivity.getString(R.string.uploads_view_upload_status_failed_retry);
//                    }
//                    String laterReason = upload.getUploadLaterReason(mParentActivity);
//                    if (laterReason != null) {
//                        //Upload failed once but is delayed now, show reason.
//                        status = laterReason;
//                    }
//                    pathTextView.setVisibility(View.GONE);
//                    fileSizeTextView.setVisibility(View.GONE);
//                    accountNameTextView.setVisibility(View.INVISIBLE);
//                    uploadDateTextView.setVisibility(View.GONE);
//                    break;
//                case UPLOAD_LATER:
//                    uploadDateTextView.setVisibility(View.GONE);
//                    pathTextView.setVisibility(View.GONE);
//                    fileSizeTextView.setVisibility(View.GONE);
//                    accountNameTextView.setVisibility(View.INVISIBLE);
//                    status = upload.getUploadLaterReason(mParentActivity);
//                    break;
                case UPLOAD_SUCCEEDED:
                    status = mParentActivity.getString(R.string.uploads_view_upload_status_succeeded);
                    statusTextView.setVisibility(View.GONE);
                    break;
                default:
                    status = upload.getUploadStatus().toString();
                    if (upload.getLastResult() != null) {
                        upload.getLastResult().toString();
                    }
                    break;
            }
            if (upload.getUploadStatus() != UploadStatus.UPLOAD_IN_PROGRESS) {
                ProgressBar progressBar = (ProgressBar) view.findViewById(R.id.upload_progress_bar);
                progressBar.setVisibility(View.GONE);
                if (mParentActivity.getFileUploaderBinder() != null && mProgressListener != null) {
                    mParentActivity.getFileUploaderBinder().removeDatatransferProgressListener(
                            mProgressListener,
                            upload.getAccount(mParentActivity),
                            upload
                    );
                    mProgressListener = null;
                }
            }
            statusTextView.setText(status);

            ImageButton rightButton = (ImageButton) view.findViewById(R.id.upload_right_button);
            if (upload.userCanCancelUpload()) {
                //Cancel
                rightButton.setImageResource(R.drawable.ic_cancel);
                rightButton.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        FileUploader.FileUploaderBinder uploaderBinder = mParentActivity.getFileUploaderBinder();
                        if (uploaderBinder != null) {
                            uploaderBinder.cancel(upload);
                            refreshView();
                        }
                    }
                });
            } else {
                //Delete
                rightButton.setImageResource(R.drawable.ic_delete);
                rightButton.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mUploadsStorageManager.removeUpload(upload);
                        refreshView();
                    }
                });
            }

            if (upload.userCanRetryUpload()) {
                view.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mParentActivity.getFileOperationsHelper().retryUpload(upload);
                    }
                });
            }


            ImageView fileIcon = (ImageView) view.findViewById(R.id.thumbnail);
            fileIcon.setImageResource(R.drawable.file);

            /** Cancellation needs do be checked and done before changing the drawable in fileIcon, or
             * {@link ThumbnailsCacheManager#cancelPotentialWork} will NEVER cancel any task.
             **/
            OCFile fakeFileToCheatThumbnailsCacheManagerInterface = new OCFile(upload.getRemotePath());
            fakeFileToCheatThumbnailsCacheManagerInterface.setStoragePath(upload.getLocalPath());
            fakeFileToCheatThumbnailsCacheManagerInterface.setMimetype(upload.getMimeType());

            boolean allowedToCreateNewThumbnail = (ThumbnailsCacheManager.cancelPotentialWork(
                    fakeFileToCheatThumbnailsCacheManagerInterface,
                    fileIcon)
            );

            // TODO this code is duplicated; refactor to a common place
            if ((fakeFileToCheatThumbnailsCacheManagerInterface.isImage()
                    && fakeFileToCheatThumbnailsCacheManagerInterface.getRemoteId() != null &&
                    upload.getUploadStatus() == UploadStatus.UPLOAD_SUCCEEDED)) {
                // Thumbnail in Cache?
                Bitmap thumbnail = ThumbnailsCacheManager.getBitmapFromDiskCache(
                        String.valueOf(fakeFileToCheatThumbnailsCacheManagerInterface.getRemoteId())
                );
                if (thumbnail != null && !fakeFileToCheatThumbnailsCacheManagerInterface.needsUpdateThumbnail()) {
                    fileIcon.setImageBitmap(thumbnail);
                } else {
                    // generate new Thumbnail
                    if (allowedToCreateNewThumbnail) {
                        final ThumbnailsCacheManager.ThumbnailGenerationTask task =
                                new ThumbnailsCacheManager.ThumbnailGenerationTask(
                                        fileIcon, mParentActivity.getStorageManager(), mParentActivity.getAccount()
                                );
                        if (thumbnail == null) {
                            thumbnail = ThumbnailsCacheManager.mDefaultImg;
                        }
                        final ThumbnailsCacheManager.AsyncDrawable asyncDrawable =
                                new ThumbnailsCacheManager.AsyncDrawable(
                                        mParentActivity.getResources(),
                                        thumbnail,
                                        task
                                );
                        fileIcon.setImageDrawable(asyncDrawable);
                        task.execute(fakeFileToCheatThumbnailsCacheManagerInterface);
                    }
                }

                if ("image/png".equals(upload.getMimeType())) {
                    fileIcon.setBackgroundColor(mParentActivity.getResources()
                            .getColor(R.color.background_color));
                }


            } else if (fakeFileToCheatThumbnailsCacheManagerInterface.isImage()) {
                File file = new File(upload.getLocalPath());
                // Thumbnail in Cache?
                Bitmap thumbnail = ThumbnailsCacheManager.getBitmapFromDiskCache(
                        String.valueOf(file.hashCode()));
                if (thumbnail != null) {
                    fileIcon.setImageBitmap(thumbnail);
                } else {
                    // generate new Thumbnail
                    if (allowedToCreateNewThumbnail) {
                        final ThumbnailsCacheManager.ThumbnailGenerationTask task =
                                new ThumbnailsCacheManager.ThumbnailGenerationTask(fileIcon);
                        if (thumbnail == null) {
                            thumbnail = ThumbnailsCacheManager.mDefaultImg;
                        }
                        final ThumbnailsCacheManager.AsyncDrawable asyncDrawable =
                                new ThumbnailsCacheManager.AsyncDrawable(
                                        mParentActivity.getResources(),
                                        thumbnail,
                                        task
                                );
                        fileIcon.setImageDrawable(asyncDrawable);
                        task.execute(file);
                        Log_OC.v(TAG, "Executing task to generate a new thumbnail");
                    }
                }

                if ("image/png".equalsIgnoreCase(upload.getMimeType())) {
                    fileIcon.setBackgroundColor(mParentActivity.getResources()
                            .getColor(R.color.background_color));
                }
            } else {
                fileIcon.setImageResource(MimetypeIconUtil.getFileTypeIconId(
                        upload.getMimeType(),
                        fileName
                ));
            }

        }

        return view;
    }


    @Override
    public boolean hasStableIds() {
        return false;
    }


    /**
     * Load upload items from {@link UploadsStorageManager}.
     */
    private void loadUploadItemsFromDb() {
        Log_OC.d(TAG, "loadUploadItemsFromDb");

        for (UploadGroup group : mUploadGroups) {
            group.refresh();
        }

        notifyDataSetChanged();
    }

    @Override
    public void update(Observable arg0, Object arg1) {
        Log_OC.d(TAG, "update");
        loadUploadItemsFromDb();
    }


    public void refreshView() {
        Log_OC.d(TAG, "refreshView");
        loadUploadItemsFromDb();
    }

    @Override
    public Object getChild(int groupPosition, int childPosition) {
        return mUploadGroups[(int) getGroupId(groupPosition)].items[childPosition];
    }

    @Override
    public long getChildId(int groupPosition, int childPosition) {
        return childPosition;
    }

    @Override
    public View getChildView(int groupPosition, int childPosition, boolean isLastChild, View convertView,
                             ViewGroup parent) {
        return getView(mUploadGroups[(int) getGroupId(groupPosition)].items, childPosition, convertView, parent);
    }

    @Override
    public int getChildrenCount(int groupPosition) {
        return mUploadGroups[(int) getGroupId(groupPosition)].items.length;
    }

    @Override
    public Object getGroup(int groupPosition) {
        return mUploadGroups[(int) getGroupId(groupPosition)];
    }

    @Override
    public int getGroupCount() {
        int size = 0;
        for (UploadGroup uploadGroup : mUploadGroups) {
            if (uploadGroup.items.length > 0) {
                size++;
            }
        }
        return size;
    }

    /**
     * Returns the groupId (that is, index in mUploadGroups) for group at position groupPosition (0-based).
     * Could probably be done more intuitive but this tested methods works as intended.
     */
    @Override
    public long getGroupId(int groupPosition) {
        int id = -1;
        for (int i = 0; i <= groupPosition; ) {
            id++;
            if (mUploadGroups[id].items.length > 0) {
                i++;
            }
        }
        return id;
    }

    @Override
    public View getGroupView(int groupPosition, boolean isExpanded, View convertView, ViewGroup parent) {
        //force group to stay unfolded
        ExpandableListView listView = (ExpandableListView) parent;
        listView.expandGroup(groupPosition);

        listView.setGroupIndicator(null);
        UploadGroup group = (UploadGroup) getGroup(groupPosition);
        if (convertView == null) {
            LayoutInflater inflaInflater = (LayoutInflater) mParentActivity
                    .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            convertView = inflaInflater.inflate(R.layout.upload_list_group, null);
        }
        TextView tv = (TextView) convertView.findViewById(R.id.uploadListGroupName);
        tv.setText(group.getGroupName());
//        ImageView icon = (ImageView) convertView.findViewById(R.id.uploadListGroupIcon);
//        icon.setImageResource(group.getGroupIcon());
        return convertView;
    }

    @Override
    public boolean isChildSelectable(int groupPosition, int childPosition) {
        return true;
    }

    public class ProgressListener implements OnDatatransferProgressListener {
        int mLastPercent = 0;
        WeakReference<ProgressBar> mProgressBar = null;

        ProgressListener(ProgressBar progressBar) {
            mProgressBar = new WeakReference<ProgressBar>(progressBar);
        }

        @Override
        public void onTransferProgress(long progressRate, long totalTransferredSoFar, long totalToTransfer, String
                filename) {
            int percent = (int) (100.0 * ((double) totalTransferredSoFar) / ((double) totalToTransfer));
            if (percent != mLastPercent) {
                ProgressBar pb = mProgressBar.get();
                if (pb != null) {
                    pb.setProgress(percent);
                    pb.postInvalidate();
                }
            }
            mLastPercent = percent;
        }

    }

    ;

    public void addBinder() {
        notifyDataSetChanged();
    }
}
