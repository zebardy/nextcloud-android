/**
 *   ownCloud Android client application
 *
 *   @author Bartek Przybylski
 *   @author Tobias Kaminsky
 *   @author David A. Velasco
 *   Copyright (C) 2011  Bartek Przybylski
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
package com.owncloud.android.ui.adapter;


import java.io.File;
import java.util.Vector;

import android.accounts.Account;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.preference.PreferenceManager;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.TextView;

import com.owncloud.android.R;
import com.owncloud.android.authentication.AccountUtils;
import com.owncloud.android.datamodel.FileDataStorageManager;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.datamodel.ThumbnailsCacheManager;
import com.owncloud.android.files.services.FileDownloader.FileDownloaderBinder;
import com.owncloud.android.files.services.FileUploadService.FileUploaderBinder;
import com.owncloud.android.services.OperationsService.OperationsServiceBinder;
import com.owncloud.android.ui.activity.ComponentsGetter;
import com.owncloud.android.utils.DisplayUtils;
import com.owncloud.android.utils.FileStorageUtils;


/**
 * This Adapter populates a ListView with all files and folders in an ownCloud
 * instance.
 */
public class FileListListAdapter extends BaseAdapter implements ListAdapter {
    private final static String PERMISSION_SHARED_WITH_ME = "S";

    private Context mContext;
    private OCFile mFile = null;
    private Vector<OCFile> mFiles = null;
    private Vector<OCFile> mFilesOrig = new Vector<OCFile>();
    private boolean mJustFolders;

    private FileDataStorageManager mStorageManager;
    private Account mAccount;
    private ComponentsGetter mTransferServiceGetter;
    private boolean mGridMode;

    private enum ViewType {LIST_ITEM, GRID_IMAGE, GRID_ITEM };

    private SharedPreferences mAppPreferences;
    
    public FileListListAdapter(
            boolean justFolders, 
            Context context,
            ComponentsGetter transferServiceGetter
            ) {
        
        mJustFolders = justFolders;
        mContext = context;
        mAccount = AccountUtils.getCurrentOwnCloudAccount(mContext);

        mTransferServiceGetter = transferServiceGetter;

        mAppPreferences = PreferenceManager
                .getDefaultSharedPreferences(mContext);
        
        // Read sorting order, default to sort by name ascending

        FileStorageUtils.mSortOrder = mAppPreferences.getInt("sortOrder", 0);
        FileStorageUtils.mSortAscending = mAppPreferences.getBoolean("sortAscending", true);
        
        // initialise thumbnails cache on background thread
        new ThumbnailsCacheManager.InitDiskCacheTask().execute();

        mGridMode = false;
    }
    
    @Override
    public boolean areAllItemsEnabled() {
        return true;
    }

    @Override
    public boolean isEnabled(int position) {
        return true;
    }

    @Override
    public int getCount() {
        return mFiles != null ? mFiles.size() : 0;
    }

    @Override
    public Object getItem(int position) {
        if (mFiles == null || mFiles.size() <= position)
            return null;
        return mFiles.get(position);
    }

    @Override
    public long getItemId(int position) {
        if (mFiles == null || mFiles.size() <= position)
            return 0;
        return mFiles.get(position).getFileId();
    }

    @Override
    public int getItemViewType(int position) {
        return 0;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {

        View view = convertView;
        OCFile file = null;
        LayoutInflater inflator = (LayoutInflater) mContext
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        if (mFiles != null && mFiles.size() > position) {
            file = mFiles.get(position);
        }

        // Find out which layout should be displayed
        ViewType viewType;
        if (!mGridMode){
            viewType = ViewType.LIST_ITEM;
        } else if (file.isImage()){
            viewType = ViewType.GRID_IMAGE;
        } else {
            viewType = ViewType.GRID_ITEM;
        }

        // create view only if differs, otherwise reuse
        if (convertView == null || (convertView != null && convertView.getTag() != viewType)) {
            switch (viewType) {
                case GRID_IMAGE:
                    view = inflator.inflate(R.layout.grid_image, null);
                    view.setTag(ViewType.GRID_IMAGE);
                    break;
                case GRID_ITEM:
                    view = inflator.inflate(R.layout.grid_item, null);
                    view.setTag(ViewType.GRID_ITEM);
                    break;
                case LIST_ITEM:
                    view = inflator.inflate(R.layout.list_item, null);
                    view.setTag(ViewType.LIST_ITEM);
                    break;
            }
        }

        view.invalidate();

        if (file != null){

            ImageView fileIcon = (ImageView) view.findViewById(R.id.thumbnail);

            fileIcon.setTag(file.getFileId());
            TextView fileName;
            String name = file.getFileName();

            LinearLayout linearLayout = (LinearLayout) view.findViewById(R.id.ListItemLayout);
            linearLayout.setContentDescription("LinearLayout-" + name);

            switch (viewType){
                case LIST_ITEM:
                    TextView fileSizeV = (TextView) view.findViewById(R.id.file_size);
                    TextView lastModV = (TextView) view.findViewById(R.id.last_mod);
                    ImageView checkBoxV = (ImageView) view.findViewById(R.id.custom_checkbox);

                    lastModV.setVisibility(View.VISIBLE);
                    lastModV.setText(showRelativeTimestamp(file));

                    checkBoxV.setVisibility(View.GONE);

                    fileSizeV.setVisibility(View.VISIBLE);
                    fileSizeV.setText(DisplayUtils.bytesToHumanReadable(file.getFileLength()));

                    if (!file.isFolder()) {
                        AbsListView parentList = (AbsListView)parent;
                        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                            if (parentList.getChoiceMode() == AbsListView.CHOICE_MODE_NONE) {
                                checkBoxV.setVisibility(View.GONE);
                            } else {
                                if (parentList.isItemChecked(position)) {
                                    checkBoxV.setImageResource(
                                            android.R.drawable.checkbox_on_background);
                                } else {
                                    checkBoxV.setImageResource(
                                            android.R.drawable.checkbox_off_background);
                                }
                                checkBoxV.setVisibility(View.VISIBLE);
                            }
                        }

                    } else { //Folder
                        fileSizeV.setVisibility(View.INVISIBLE);
                    }

                case GRID_ITEM:
                    // filename
                    fileName = (TextView) view.findViewById(R.id.Filename);
                    name = file.getFileName();
                    fileName.setText(name);

                case GRID_IMAGE:
                    // sharedIcon
                    ImageView sharedIconV = (ImageView) view.findViewById(R.id.sharedIcon);
                    if (file.isShareByLink()) {
                        sharedIconV.setVisibility(View.VISIBLE);
                        sharedIconV.bringToFront();
                    } else {
                        sharedIconV.setVisibility(View.GONE);
                    }

                    // local state
                    ImageView localStateView = (ImageView) view.findViewById(R.id.localFileIndicator);
                    localStateView.bringToFront();
                    FileDownloaderBinder downloaderBinder =
                            mTransferServiceGetter.getFileDownloaderBinder();
                    FileUploaderBinder uploaderBinder =
                            mTransferServiceGetter.getFileUploaderBinder();
                    boolean downloading = (downloaderBinder != null &&
                            downloaderBinder.isDownloading(mAccount, file));
                    OperationsServiceBinder opsBinder =
                            mTransferServiceGetter.getOperationsServiceBinder();
                    downloading |= (opsBinder != null &&
                            opsBinder.isSynchronizing(mAccount, file.getRemotePath()));
                    if (downloading) {
                        localStateView.setImageResource(R.drawable.downloading_file_indicator);
                        localStateView.setVisibility(View.VISIBLE);
                    } else if (uploaderBinder != null &&
                            uploaderBinder.isUploading(mAccount, file)) {
                        localStateView.setImageResource(R.drawable.uploading_file_indicator);
                        localStateView.setVisibility(View.VISIBLE);
                    } else if (file.isDown()) {
                        localStateView.setImageResource(R.drawable.local_file_indicator);
                        localStateView.setVisibility(View.VISIBLE);
                    } else {
                        localStateView.setVisibility(View.INVISIBLE);
                    }

                    // share with me icon
                    ImageView sharedWithMeIconV = (ImageView)
                            view.findViewById(R.id.sharedWithMeIcon);
                    sharedWithMeIconV.bringToFront();
                    if (checkIfFileIsSharedWithMe(file) &&
                            (!file.isFolder() || !mGridMode)) {
                        sharedWithMeIconV.setVisibility(View.VISIBLE);
                    } else {
                        sharedWithMeIconV.setVisibility(View.GONE);
                    }

                    break;
            }
            
            // For all Views
            
            // this if-else is needed even though favorite icon is visible by default
            // because android reuses views in listview
            if (!file.isFavorite()) {
                view.findViewById(R.id.favoriteIcon).setVisibility(View.GONE);
            } else {
                view.findViewById(R.id.favoriteIcon).setVisibility(View.VISIBLE);
            }
            
            // No Folder
            if (!file.isFolder()) {
                if (file.isImage() && file.getRemoteId() != null){
                    // Thumbnail in Cache?
                    Bitmap thumbnail = ThumbnailsCacheManager.getBitmapFromDiskCache(
                            String.valueOf(file.getRemoteId())
                            );
                    if (thumbnail != null && !file.needsUpdateThumbnail()){
                        fileIcon.setImageBitmap(thumbnail);
                    } else {
                        // generate new Thumbnail
                        if (ThumbnailsCacheManager.cancelPotentialWork(file, fileIcon)) {
                            final ThumbnailsCacheManager.ThumbnailGenerationTask task =
                                    new ThumbnailsCacheManager.ThumbnailGenerationTask(
                                            fileIcon, mStorageManager, mAccount
                                            );
                            if (thumbnail == null) {
                                thumbnail = ThumbnailsCacheManager.mDefaultImg;
                            }
                            final ThumbnailsCacheManager.AsyncDrawable asyncDrawable =
                                    new ThumbnailsCacheManager.AsyncDrawable(
                                    mContext.getResources(), 
                                    thumbnail, 
                                    task
                                    );
                            fileIcon.setImageDrawable(asyncDrawable);
                            task.execute(file);
                        }
                    }
                } else {
                    fileIcon.setImageResource(DisplayUtils.getFileTypeIconId(file.getMimetype(),
                            file.getFileName()));
                }


            } else {
                // Folder
                if (checkIfFileIsSharedWithMe(file)) {
                    fileIcon.setImageResource(R.drawable.shared_with_me_folder);
                } else if (file.isShareByLink()) {
                    // If folder is sharedByLink, icon folder must be changed to
                    // folder-public one
                    fileIcon.setImageResource(R.drawable.folder_public);
                } else {
                    fileIcon.setImageResource(
                            DisplayUtils.getFileTypeIconId(file.getMimetype(), file.getFileName())
                    );
                }
            }
        }

        return view;
    }

    /**
     * Local Folder size in human readable format
     * 
     * @param path
     *            String
     * @return Size in human readable format
     */
    private String getFolderSizeHuman(String path) {

        File dir = new File(path);

        if (dir.exists()) {
            long bytes = FileStorageUtils.getFolderSize(dir);
            return DisplayUtils.bytesToHumanReadable(bytes);
        }

        return "0 B";
    }

    /**
     * Local Folder size
     * @param dir File
     * @return Size in bytes
     */
    private long getFolderSize(File dir) {
        if (dir.exists()) {
            long result = 0;
            File[] fileList = dir.listFiles();
            for(int i = 0; i < fileList.length; i++) {
                if(fileList[i].isDirectory()) {
                    result += getFolderSize(fileList[i]);
                } else {
                    result += fileList[i].length();
                }
            }
            return result;
        }
        return 0;
    } 

    @Override
    public int getViewTypeCount() {
        return 1;
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public boolean isEmpty() {
        return (mFiles == null || mFiles.isEmpty());
    }

    /**
     * Change the adapted directory for a new one
     * @param directory                 New file to adapt. Can be NULL, meaning 
     *                                  "no content to adapt".
     * @param updatedStorageManager     Optional updated storage manager; used to replace 
     *                                  mStorageManager if is different (and not NULL)
     */
    public void swapDirectory(OCFile directory, FileDataStorageManager updatedStorageManager
            /*, boolean onlyOnDevice*/) {
        mFile = directory;
        if (updatedStorageManager != null && updatedStorageManager != mStorageManager) {
            mStorageManager = updatedStorageManager;
            mAccount = AccountUtils.getCurrentOwnCloudAccount(mContext);
        }
        if (mStorageManager != null) {
            // TODO Enable when "On Device" is recovered ?
            mFiles = mStorageManager.getFolderContent(mFile/*, onlyOnDevice*/);
            mFilesOrig.clear();
            mFilesOrig.addAll(mFiles);
            
            if (mJustFolders) {
                mFiles = getFolders(mFiles);
            }
        } else {
            mFiles = null;
        }

        mFiles = FileStorageUtils.sortFolder(mFiles);
        notifyDataSetChanged();
    }
    

    /**
     * Filter for getting only the folders
     * @param files
     * @return Vector<OCFile>
     */
    public Vector<OCFile> getFolders(Vector<OCFile> files) {
        Vector<OCFile> ret = new Vector<OCFile>(); 
        OCFile current = null; 
        for (int i=0; i<files.size(); i++) {
            current = files.get(i);
            if (current.isFolder()) {
                ret.add(current);
            }
        }
        return ret;
    }
    
    
    /**
     * Check if parent folder does not include 'S' permission and if file/folder
     * is shared with me
     * 
     * @param file: OCFile
     * @return boolean: True if it is shared with me and false if it is not
     */
    private boolean checkIfFileIsSharedWithMe(OCFile file) {
        return (mFile.getPermissions() != null 
                && !mFile.getPermissions().contains(PERMISSION_SHARED_WITH_ME)
                && file.getPermissions() != null 
                && file.getPermissions().contains(PERMISSION_SHARED_WITH_ME));
    }

    public void setSortOrder(Integer order, boolean ascending) {
        SharedPreferences.Editor editor = mAppPreferences.edit();
        editor.putInt("sortOrder", order);
        editor.putBoolean("sortAscending", ascending);
        editor.commit();
        
        FileStorageUtils.mSortOrder = order;
        FileStorageUtils.mSortAscending = ascending;
        

        mFiles = FileStorageUtils.sortFolder(mFiles);
        notifyDataSetChanged();

    }
    
    private CharSequence showRelativeTimestamp(OCFile file){
        return DisplayUtils.getRelativeDateTimeString(mContext, file.getModificationTimestamp(),
                DateUtils.SECOND_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0);
    }

    public void setGridMode(boolean gridMode) {
        mGridMode = gridMode;
    }
}
