/**
 *  ownCloud Android client application
 *
 *  @author Bartek Przybylski
 *  @author masensio
 *  Copyright (C) 2012  Bartek Przybylski
 *  Copyright (C) 2016 ownCloud Inc.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License version 2,
 *  as published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.owncloud.android.ui.activity;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorException;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.IntentFilter;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources.NotFoundException;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AlertDialog.Builder;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import com.owncloud.android.MainApp;
import com.owncloud.android.R;
import com.owncloud.android.authentication.AccountAuthenticator;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.files.services.FileUploader;
import com.owncloud.android.lib.common.operations.RemoteOperation;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.common.operations.RemoteOperationResult.ResultCode;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.operations.CreateFolderOperation;
import com.owncloud.android.operations.RefreshFolderOperation;
import com.owncloud.android.operations.UploadFileOperation;
import com.owncloud.android.syncadapter.FileSyncAdapter;
import com.owncloud.android.ui.adapter.UploaderAdapter;
import com.owncloud.android.ui.dialog.CreateFolderDialogFragment;
import com.owncloud.android.ui.dialog.LoadingDialog;
import com.owncloud.android.ui.asynctasks.CopyAndUploadContentUrisTask;
import com.owncloud.android.utils.DisplayUtils;
import com.owncloud.android.utils.ErrorMessageAdapter;
import com.owncloud.android.utils.UriUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Stack;
import java.util.Vector;


/**
 * This can be used to upload things to an ownCloud instance.
 */
public class Uploader extends FileActivity
        implements OnItemClickListener, android.view.View.OnClickListener,
    CopyAndUploadContentUrisTask.OnCopyTmpFilesTaskListener {

    private static final String TAG = Uploader.class.getSimpleName();

    private static final String FTAG_TASK_RETAINER_FRAGMENT = "TASK_RETAINER_FRAGMENT";

    private AccountManager mAccountManager;
    private Stack<String> mParents;
    private ArrayList<Parcelable> mStreamsToUpload;
    private String mUploadPath;
    private OCFile mFile;

    private SyncBroadcastReceiver mSyncBroadcastReceiver;
    private boolean mSyncInProgress = false;
    private boolean mAccountSelected;
    private boolean mAccountSelectionShowing;

    private final static int DIALOG_NO_ACCOUNT = 0;
    private final static int DIALOG_NO_STREAM = 2;
    private final static int DIALOG_MULTIPLE_ACCOUNT = 3;
    private final static int DIALOG_STREAM_UNKNOWN = 4;

    private final static int REQUEST_CODE__SETUP_ACCOUNT = REQUEST_CODE__LAST_SHARED + 1;

    private final static String KEY_PARENTS = "PARENTS";
    private final static String KEY_FILE = "FILE";
    private final static String KEY_ACCOUNT_SELECTED = "ACCOUNT_SELECTED";
    private final static String KEY_ACCOUNT_SELECTION_SHOWING = "ACCOUNT_SELECTION_SHOWING";

    private static final String DIALOG_WAIT_COPY_FILE = "DIALOG_WAIT_COPY_FILE";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prepareStreamsToUpload();

        if (savedInstanceState == null) {
            mParents = new Stack<>();
            mAccountSelected = false;
            mAccountSelectionShowing = false;

        } else {
            mParents = (Stack<String>) savedInstanceState.getSerializable(KEY_PARENTS);
            mFile = savedInstanceState.getParcelable(KEY_FILE);
            mAccountSelected = savedInstanceState.getBoolean(KEY_ACCOUNT_SELECTED);
            mAccountSelectionShowing = savedInstanceState.getBoolean(KEY_ACCOUNT_SELECTION_SHOWING);
        }

        super.onCreate(savedInstanceState);

        if (mAccountSelected) {
            setAccount((Account) savedInstanceState.getParcelable(FileActivity.EXTRA_ACCOUNT));
        }

        // Listen for sync messages
        IntentFilter syncIntentFilter = new IntentFilter(RefreshFolderOperation.
                EVENT_SINGLE_FOLDER_CONTENTS_SYNCED);
        syncIntentFilter.addAction(RefreshFolderOperation.EVENT_SINGLE_FOLDER_SHARES_SYNCED);
        mSyncBroadcastReceiver = new SyncBroadcastReceiver();
        registerReceiver(mSyncBroadcastReceiver, syncIntentFilter);

        // Init Fragment without UI to retain AsyncTask across configuration changes
        FragmentManager fm = getSupportFragmentManager();
        TaskRetainerFragment taskRetainerFragment =
            (TaskRetainerFragment) fm.findFragmentByTag(FTAG_TASK_RETAINER_FRAGMENT);
        if (taskRetainerFragment == null) {
            taskRetainerFragment = new TaskRetainerFragment();
            fm.beginTransaction().add(taskRetainerFragment, FTAG_TASK_RETAINER_FRAGMENT).commit();
        }   // else, Fragment already created and retained across configuration change
    }

    @Override
    protected void setAccount(Account account, boolean savedAccount) {
        if (somethingToUpload()) {
            mAccountManager = (AccountManager) getSystemService(Context.ACCOUNT_SERVICE);
            Account[] accounts = mAccountManager.getAccountsByType(MainApp.getAccountType());
            if (accounts.length == 0) {
                Log_OC.i(TAG, "No ownCloud account is available");
                showDialog(DIALOG_NO_ACCOUNT);
            } else if (accounts.length > 1 && !mAccountSelected && !mAccountSelectionShowing) {
                Log_OC.i(TAG, "More than one ownCloud is available");
                showDialog(DIALOG_MULTIPLE_ACCOUNT);
                mAccountSelectionShowing = true;
            } else {
                if (!savedAccount) {
                    setAccount(accounts[0]);
                }
            }

        } else {
            showDialog(DIALOG_NO_STREAM);
        }

        super.setAccount(account, savedAccount);
    }

    @Override
    protected void onAccountSet(boolean stateWasRecovered) {
        super.onAccountSet(mAccountWasRestored);
        initTargetFolder();
        populateDirectoryList();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        Log_OC.d(TAG, "onSaveInstanceState() start");
        super.onSaveInstanceState(outState);
        outState.putSerializable(KEY_PARENTS, mParents);
        //outState.putParcelable(KEY_ACCOUNT, mAccount);
        outState.putParcelable(KEY_FILE, mFile);
        outState.putBoolean(KEY_ACCOUNT_SELECTED, mAccountSelected);
        outState.putBoolean(KEY_ACCOUNT_SELECTION_SHOWING, mAccountSelectionShowing);
        outState.putParcelable(FileActivity.EXTRA_ACCOUNT, getAccount());

        Log_OC.d(TAG, "onSaveInstanceState() end");
    }

    @Override
    protected void onDestroy(){
        if (mSyncBroadcastReceiver != null) {
            unregisterReceiver(mSyncBroadcastReceiver);
        }
        super.onDestroy();
    }

    @Override
    protected Dialog onCreateDialog(final int id) {
        final AlertDialog.Builder builder = new Builder(this);
        // Create key listener for back button pressed
        DialogInterface.OnKeyListener onKeyListener = new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    Uploader.super.onBackPressed();
                    dialog.dismiss();
                }
                return true;
            }
        };
        switch (id) {
        case DIALOG_NO_ACCOUNT:
            builder.setIcon(R.drawable.ic_warning);
            builder.setTitle(R.string.uploader_wrn_no_account_title);
            builder.setMessage(String.format(
                    getString(R.string.uploader_wrn_no_account_text),
                    getString(R.string.app_name)));
            builder.setCancelable(false);
            builder.setPositiveButton(R.string.uploader_wrn_no_account_setup_btn_text, new OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (android.os.Build.VERSION.SDK_INT >
                            android.os.Build.VERSION_CODES.ECLAIR_MR1) {
                        // using string value since in API7 this
                        // constatn is not defined
                        // in API7 < this constatant is defined in
                        // Settings.ADD_ACCOUNT_SETTINGS
                        // and Settings.EXTRA_AUTHORITIES
                        Intent intent = new Intent(android.provider.Settings.ACTION_ADD_ACCOUNT);
                        intent.putExtra("authorities", new String[]{MainApp.getAuthTokenType()});
                        startActivityForResult(intent, REQUEST_CODE__SETUP_ACCOUNT);
                    } else {
                        // since in API7 there is no direct call for
                        // account setup, so we need to
                        // show our own AccountSetupAcricity, get
                        // desired results and setup
                        // everything for ourself
                        Intent intent = new Intent(getBaseContext(), AccountAuthenticator.class);
                        startActivityForResult(intent, REQUEST_CODE__SETUP_ACCOUNT);
                    }
                }
            });
            builder.setNegativeButton(R.string.uploader_wrn_no_account_quit_btn_text, new OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    finish();
                }
            });
            return builder.create();
        case DIALOG_MULTIPLE_ACCOUNT:
            CharSequence ac[] = new CharSequence[
                    mAccountManager.getAccountsByType(MainApp.getAccountType()).length];
            for (int i = 0; i < ac.length; ++i) {
                ac[i] = DisplayUtils.convertIdn(
                        mAccountManager.getAccountsByType(MainApp.getAccountType())[i].name, false);
            }
            builder.setTitle(R.string.common_choose_account);
            builder.setItems(ac, new OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    setAccount(mAccountManager.getAccountsByType(MainApp.getAccountType())[which]);
                    onAccountSet(mAccountWasRestored);
                    dialog.dismiss();
                    mAccountSelected = true;
                    mAccountSelectionShowing = false;
                }
            });
            builder.setCancelable(true);
            builder.setOnCancelListener(new OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    mAccountSelectionShowing = false;
                    dialog.cancel();
                    finish();
                }
            });
            return builder.create();
        case DIALOG_NO_STREAM:
            builder.setIcon(R.drawable.ic_warning);
            builder.setTitle(R.string.uploader_wrn_no_content_title);
            builder.setMessage(R.string.uploader_wrn_no_content_text);
            builder.setCancelable(false);
            builder.setNegativeButton(R.string.common_back, new OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    finish();
                }
            });
            builder.setOnKeyListener(onKeyListener);
            return builder.create();
        case DIALOG_STREAM_UNKNOWN:
            builder.setIcon(android.R.drawable.ic_dialog_alert);
            builder.setTitle(R.string.uploader_wrn_unknown_content_title);
            String message = String.format(getString(R.string.uploader_error_forbidden_content),
                getString(R.string.app_name));
            builder.setMessage(message);
            builder.setCancelable(false);
            builder.setNegativeButton(R.string.common_back, new OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    finish();
                }
            });
            builder.setOnKeyListener(onKeyListener);
            return builder.create();
        default:
            throw new IllegalArgumentException("Unknown dialog id: " + id);
        }
    }

    class a implements OnClickListener {
        String mPath;
        EditText mDirname;

        public a(String path, EditText dirname) {
            mPath = path;
            mDirname = dirname;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            Uploader.this.mUploadPath = mPath + mDirname.getText().toString();
            uploadFiles();
        }
    }

    @Override
    public void onBackPressed() {
        if (mParents.size() <= 1) {
            super.onBackPressed();
        } else {
            mParents.pop();
            String full_path = generatePath(mParents);
            startSyncFolderOperation(getStorageManager().getFileByPath(full_path));
            populateDirectoryList();
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        // click on folder in the list
        Log_OC.d(TAG, "on item click");
        // TODO Enable when "On Device" is recovered ?
        Vector<OCFile> tmpfiles = getStorageManager().getFolderContent(mFile /*, false*/);
        if (tmpfiles.size() <= 0) return;
        // filter on dirtype
        Vector<OCFile> files = new Vector<>();
        for (OCFile f : tmpfiles)
                files.add(f);
        if (files.size() < position) {
            throw new IndexOutOfBoundsException("Incorrect item selected");
        }
        if (files.get(position).isFolder()){
            OCFile folderToEnter = files.get(position);
            startSyncFolderOperation(folderToEnter);
            mParents.push(folderToEnter.getFileName());
            populateDirectoryList();
        }
    }

    @Override
    public void onClick(View v) {
        // click on button
        switch (v.getId()) {
            case R.id.uploader_choose_folder:
                mUploadPath = "";   // first element in mParents is root dir, represented by "";
                // init mUploadPath with "/" results in a "//" prefix
                for (String p : mParents) {
                    mUploadPath += p + OCFile.PATH_SEPARATOR;
                }
                Log_OC.d(TAG, "Uploading file to dir " + mUploadPath);

                try {
                    uploadFiles();
                } catch (SecurityException e) {
                    showDialog(DIALOG_STREAM_UNKNOWN);  // TODO: _FORBIDDEN
                }
                break;

            case R.id.uploader_cancel:
                finish();
                break;

            default:
                throw new IllegalArgumentException("Wrong element clicked");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log_OC.i(TAG, "result received. req: " + requestCode + " res: " + resultCode);
        if (requestCode == REQUEST_CODE__SETUP_ACCOUNT) {
            dismissDialog(DIALOG_NO_ACCOUNT);
            if (resultCode == RESULT_CANCELED) {
                finish();
            }
            Account[] accounts = mAccountManager.getAccountsByType(MainApp.getAuthTokenType());
            if (accounts.length == 0) {
                showDialog(DIALOG_NO_ACCOUNT);
            } else {
                // there is no need for checking for is there more then one
                // account at this point
                // since account setup can set only one account at time
                setAccount(accounts[0]);
                populateDirectoryList();
            }
        }
    }

    private void populateDirectoryList() {
        setContentView(R.layout.uploader_layout);

        ListView mListView = (ListView) findViewById(android.R.id.list);

        String current_dir = mParents.peek();
        if (current_dir.equals("")) {
            getSupportActionBar().setTitle(getString(R.string.default_display_name_for_root_folder));
        } else {
            getSupportActionBar().setTitle(current_dir);
        }
        boolean notRoot = (mParents.size() > 1);
        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(notRoot);
        actionBar.setHomeButtonEnabled(notRoot);

        String full_path = generatePath(mParents);

        Log_OC.d(TAG, "Populating view with content of : " + full_path);

        mFile = getStorageManager().getFileByPath(full_path);
        if (mFile != null) {
            // TODO Enable when "On Device" is recovered ?
            Vector<OCFile> files = getStorageManager().getFolderContent(mFile/*, false*/);
            List<HashMap<String, OCFile>> data = new LinkedList<>();
            for (OCFile f : files) {
                HashMap<String, OCFile> h = new HashMap<>();
                    h.put("dirname", f);
                    data.add(h);
            }

            UploaderAdapter sa = new UploaderAdapter(this,
                                                data,
                                                R.layout.uploader_list_item_layout,
                                                new String[] {"dirname"},
                                                new int[] {R.id.filename},
                                                getStorageManager(), getAccount());
            
            mListView.setAdapter(sa);
            Button btnChooseFolder = (Button) findViewById(R.id.uploader_choose_folder);
            btnChooseFolder.setOnClickListener(this);

            Button btnNewFolder = (Button) findViewById(R.id.uploader_cancel);
            btnNewFolder.setOnClickListener(this);

            mListView.setOnItemClickListener(this);
        }
    }

    @Override
    public void onSavedCertificate() {
        startSyncFolderOperation(getCurrentDir());
    }

    private void startSyncFolderOperation(OCFile folder) {
        long currentSyncTime = System.currentTimeMillis(); 
        
        mSyncInProgress = true;
        
        // perform folder synchronization
        RemoteOperation synchFolderOp = new RefreshFolderOperation( folder,
                                                                        currentSyncTime, 
                                                                        false,
                                                                        false,
                                                                        false,
                                                                        getStorageManager(),
                                                                        getAccount(),
                                                                        getApplicationContext()
                                                                      );
        synchFolderOp.execute(getAccount(), this, null, null);
    }


    private String generatePath(Stack<String> dirs) {
        String full_path = "";

        for (String a : dirs)
            full_path += a + "/";
        return full_path;
    }

    private void prepareStreamsToUpload() {
        if (getIntent().getAction().equals(Intent.ACTION_SEND)) {
            mStreamsToUpload = new ArrayList<Parcelable>();
            mStreamsToUpload.add(getIntent().getParcelableExtra(Intent.EXTRA_STREAM));
        } else if (getIntent().getAction().equals(Intent.ACTION_SEND_MULTIPLE)) {
            mStreamsToUpload = getIntent().getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        }
    }

    private boolean somethingToUpload() {
        return (mStreamsToUpload != null && mStreamsToUpload.get(0) != null);
    }

    @SuppressLint("NewApi")
    public void uploadFiles() {

        List<Uri> contentUris = new ArrayList<>();
        List<String> contentRemotePaths = new ArrayList<>();

        for (Parcelable sourceStream : mStreamsToUpload) {
            Uri sourceUri = (Uri) sourceStream;
            if (sourceUri == null) {
                showDialog(DIALOG_NO_STREAM);

            } else {
                String displayName = UriUtils.getDisplayNameForUri(sourceUri, this);
                if(displayName == null) {
                    showDialog(DIALOG_NO_STREAM);   // TODO - different dialog?

                } else {
                    String remotePath = mUploadPath + displayName;

                    if (ContentResolver.SCHEME_CONTENT.equals(sourceUri.getScheme())) {
                        contentUris.add(sourceUri);
                        contentRemotePaths.add(remotePath);

                    } else if (ContentResolver.SCHEME_FILE.equals(sourceUri.getScheme())) {
                        /// file: uris should point to a local file, should be safe let FileUploader handle them
                        requestUpload(sourceUri.getPath(), remotePath);

                    } else {
                        showDialog(DIALOG_STREAM_UNKNOWN);
                    }
                }
            }
        }

        if(!contentUris.isEmpty()) {
            /// content: uris will be copied to temporary files before calling {@link FileUploader}
            copyThenUpload(contentUris.toArray(new Uri[contentUris.size()]),
                contentRemotePaths.toArray(new String[contentRemotePaths.size()]));
        }

        // Save the path to shared preferences; even if upload is not possible, user chose the folder
        SharedPreferences.Editor appPrefs = PreferenceManager
            .getDefaultSharedPreferences(getApplicationContext()).edit();
        appPrefs.putString("last_upload_path", mUploadPath);
        appPrefs.apply();
    }

    /**
     *
     * @param sourceUris        Array of content:// URIs to the files to upload
     * @param remotePaths       Array of absolute paths to set to the uploaded files
     */
    private void copyThenUpload(Uri[] sourceUris, String[] remotePaths) {
        showWaitingCopyDialog();

        CopyAndUploadContentUrisTask copyTask = new CopyAndUploadContentUrisTask(this, this);
        FragmentManager fm = getSupportFragmentManager();
        TaskRetainerFragment taskRetainerFragment =
            (TaskRetainerFragment) fm.findFragmentByTag(FTAG_TASK_RETAINER_FRAGMENT);
        taskRetainerFragment.setTask(copyTask);
        copyTask.execute(
            CopyAndUploadContentUrisTask.makeParamsToExecute(
                getAccount(),
                sourceUris,
                remotePaths,
                getContentResolver()
            )
        );
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
            this,
            getAccount(),
            localPath,
            remotePath,
            FileUploader.LOCAL_BEHAVIOUR_FORGET,
            null,       // MIME type will be detected from file name
            false,      // do not create parent folder if not existent
            UploadFileOperation.CREATED_BY_USER
        );
    }


    @Override
    public void onRemoteOperationFinish(RemoteOperation operation, RemoteOperationResult result) {
        super.onRemoteOperationFinish(operation, result);


        if (operation instanceof CreateFolderOperation) {
            onCreateFolderOperationFinish((CreateFolderOperation) operation, result);
        }

    }

    /**
     * Updates the view associated to the activity after the finish of an operation
     * trying create a new folder
     *
     * @param operation Creation operation performed.
     * @param result    Result of the creation.
     */
    private void onCreateFolderOperationFinish(CreateFolderOperation operation,
                                               RemoteOperationResult result) {
        if (result.isSuccess()) {
            populateDirectoryList();
        } else {
            try {
                Toast msg = Toast.makeText(this,
                        ErrorMessageAdapter.getErrorCauseMessage(result, operation, getResources()),
                        Toast.LENGTH_LONG);
                msg.show();

            } catch (NotFoundException e) {
                Log_OC.e(TAG, "Error while trying to show fail message ", e);
            }
        }
    }


    /**
     * Loads the target folder initialize shown to the user.
     * <p/>
     * The target account has to be chosen before this method is called.
     */
    private void initTargetFolder() {
        if (getStorageManager() == null) {
            throw new IllegalStateException("Do not call this method before " +
                    "initializing mStorageManager");
        }

        SharedPreferences appPreferences = PreferenceManager
                .getDefaultSharedPreferences(getApplicationContext());

        String last_path = appPreferences.getString("last_upload_path", "");
        // "/" equals root-directory
        if (last_path.equals("/")) {
            mParents.add("");
        } else {
            String[] dir_names = last_path.split("/");
            mParents.clear();
            for (String dir : dir_names)
                mParents.add(dir);
        }
        //Make sure that path still exists, if it doesn't pop the stack and try the previous path
        while (!getStorageManager().fileExists(generatePath(mParents)) && mParents.size() > 1) {
            mParents.pop();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        menu.findItem(R.id.action_sort).setVisible(false);
        menu.findItem(R.id.action_sync_account).setVisible(false);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        boolean retval = true;
        switch (item.getItemId()) {
            case R.id.action_create_dir:
                CreateFolderDialogFragment dialog = CreateFolderDialogFragment.newInstance(mFile);
                dialog.show(
                        getSupportFragmentManager(),
                        CreateFolderDialogFragment.CREATE_FOLDER_FRAGMENT);
                break;
            case android.R.id.home:
                if ((mParents.size() > 1)) {
                    onBackPressed();
                }
                break;

            default:
                retval = super.onOptionsItemSelected(item);
        }
        return retval;
    }
    
    private OCFile getCurrentFolder(){
        OCFile file = mFile;
        if (file != null) {
            if (file.isFolder()) {
                return file;
            } else if (getStorageManager() != null) {
                return getStorageManager().getFileByPath(file.getParentRemotePath());
            }
        }
        return null;
    }
    
    private void browseToRoot() {
        OCFile root = getStorageManager().getFileByPath(OCFile.ROOT_PATH);
        mFile = root;
        startSyncFolderOperation(root);
    }
    
    private class SyncBroadcastReceiver extends BroadcastReceiver {

        /**
         * {@link BroadcastReceiver} to enable syncing feedback in UI
         */
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String event = intent.getAction();
                Log_OC.d(TAG, "Received broadcast " + event);
                String accountName = intent.getStringExtra(FileSyncAdapter.EXTRA_ACCOUNT_NAME);
                String synchFolderRemotePath =
                        intent.getStringExtra(FileSyncAdapter.EXTRA_FOLDER_PATH);
                RemoteOperationResult synchResult =
                        (RemoteOperationResult) intent.getSerializableExtra(
                                FileSyncAdapter.EXTRA_RESULT);
                boolean sameAccount = (getAccount() != null &&
                        accountName.equals(getAccount().name) && getStorageManager() != null);

                if (sameAccount) {

                    if (FileSyncAdapter.EVENT_FULL_SYNC_START.equals(event)) {
                        mSyncInProgress = true;

                    } else {
                        OCFile currentFile = (mFile == null) ? null :
                                getStorageManager().getFileByPath(mFile.getRemotePath());
                        OCFile currentDir = (getCurrentFolder() == null) ? null :
                                getStorageManager().getFileByPath(getCurrentFolder().getRemotePath());

                        if (currentDir == null) {
                            // current folder was removed from the server 
                            Toast.makeText(context,
                                    String.format(
                                            getString(R.string.sync_current_folder_was_removed),
                                            getCurrentFolder().getFileName()),
                                    Toast.LENGTH_LONG)
                                    .show();
                            browseToRoot();

                        } else {
                            if (currentFile == null && !mFile.isFolder()) {
                                // currently selected file was removed in the server, and now we know it
                                currentFile = currentDir;
                            }

                            if (synchFolderRemotePath != null &&
                                    currentDir.getRemotePath().equals(synchFolderRemotePath)) {
                                populateDirectoryList();
                            }
                            mFile = currentFile;
                        }

                        mSyncInProgress = (!FileSyncAdapter.EVENT_FULL_SYNC_END.equals(event) &&
                                !RefreshFolderOperation.EVENT_SINGLE_FOLDER_SHARES_SYNCED.equals(event));

                        if (RefreshFolderOperation.EVENT_SINGLE_FOLDER_CONTENTS_SYNCED.
                                equals(event) &&
                                /// TODO refactor and make common
                                synchResult != null && !synchResult.isSuccess()) {

                            if(synchResult.getCode() == ResultCode.UNAUTHORIZED ||
                                        (synchResult.isException() && synchResult.getException()
                                                instanceof AuthenticatorException)) {

                                requestCredentialsUpdate(context);

                            } else if(RemoteOperationResult.ResultCode.SSL_RECOVERABLE_PEER_UNVERIFIED.equals(synchResult.getCode())) {

                                showUntrustedCertDialog(synchResult);
                            }
                        }
                    }
                    removeStickyBroadcast(intent);
                    Log_OC.d(TAG, "Setting progress visibility to " + mSyncInProgress);

                }
            } catch (RuntimeException e) {
                // avoid app crashes after changing the serial id of RemoteOperationResult 
                // in owncloud library with broadcast notifications pending to process
                removeStickyBroadcast(intent);
            }
        }
    }

    /**
     * Process the result of CopyAndUploadContentUrisTask
     */
    @Override
    public void onTmpFilesCopied(ResultCode result) {
        dismissWaitingCopyDialog();
        if (result != ResultCode.OK) {
            showDialog(DIALOG_STREAM_UNKNOWN);  // TODO BETTER ERROR DIALOGS!

        } else {
            finish();
        }
    }

    /**
     * Show waiting for copy dialog
     */
    public void showWaitingCopyDialog() {
        // Construct dialog
        LoadingDialog loading = new LoadingDialog(
                getResources().getString(R.string.wait_for_tmp_copy_from_private_storage));
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        loading.show(ft, DIALOG_WAIT_COPY_FILE);

    }


    /**
     * Dismiss waiting for copy dialog
     */
    public void dismissWaitingCopyDialog() {
        Fragment frag = getSupportFragmentManager().findFragmentByTag(DIALOG_WAIT_COPY_FILE);
        if (frag != null) {
            LoadingDialog loading = (LoadingDialog) frag;
            loading.dismiss();
        }
    }


    /**
     * Fragment retaining a background task across configuration changes.
     */
    public static class TaskRetainerFragment extends Fragment {

        private CopyAndUploadContentUrisTask mTask;

        /**
         * Updates the listener of the retained task whenever the parent
         * Activity is attached.
         *
         * Since its done in main thread, and provided the AsyncTask only accesses
         * the listener in the main thread (should so), no sync problem should occur.
         */
        @Override
        public void onAttach(Context context) {
            super.onAttach(context);
            if (mTask != null) {
                mTask.setListener((CopyAndUploadContentUrisTask.OnCopyTmpFilesTaskListener) context);
            }
        }

        /**
         * Only called once, since the instance is retained across configuration changes
         */
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setRetainInstance(true);    // the key point
        }

        /**
         * Set the callback to null so we don't accidentally leak the
         * Activity instance.
         */
        @Override
        public void onDetach() {
            super.onDetach();
        }

        /**
         * Sets the task to retain accross configuration changes
         *
         * @param task  Task to retain
         */
        private void setTask(CopyAndUploadContentUrisTask task) {
            if (mTask != null) {
                mTask.setListener(null);
            }
            mTask = task;
            if (mTask != null && getContext() != null) {
                task.setListener((CopyAndUploadContentUrisTask.OnCopyTmpFilesTaskListener) getContext());
            }
        }
    }
}
