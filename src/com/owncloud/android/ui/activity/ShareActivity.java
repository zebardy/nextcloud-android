/**
 *   ownCloud Android client application
 *
 *   @author masensio
 *   @author David A. Velasco
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

package com.owncloud.android.ui.activity;

import android.app.SearchManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.FragmentTransaction;
import android.widget.Toast;

import com.owncloud.android.R;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.providers.UsersAndGroupsSearchProvider;

import com.owncloud.android.lib.common.operations.RemoteOperation;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.operations.UnshareOperation;
import com.owncloud.android.ui.fragment.SearchFragment;
import com.owncloud.android.ui.fragment.ShareFileFragment;

/**
 * Activity for sharing files
 */

public class ShareActivity extends FileActivity
        implements ShareFileFragment.OnShareFragmentInteractionListener,
        SearchFragment.OnSearchFragmentInteractionListener {

    private static final String TAG = ShareActivity.class.getSimpleName();

    private static final String TAG_SHARE_FRAGMENT = "SHARE_FRAGMENT";
    private static final String TAG_SEARCH_FRAGMENT = "SEARCH_USER_AND_GROUPS_FRAGMENT";

    private static final String DIALOG_WAIT_LOAD_DATA = "DIALOG_WAIT_LOAD_DATA";

    private ShareFileFragment mShareFileFragment;
    private SearchFragment mSearchFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.share_activity);

        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();

        if (savedInstanceState != null) {

            mShareFileFragment = (ShareFileFragment) getSupportFragmentManager().
                    getFragment(savedInstanceState, TAG_SHARE_FRAGMENT);
            mSearchFragment = (SearchFragment) getSupportFragmentManager().
                    getFragment(savedInstanceState, TAG_SEARCH_FRAGMENT);

            if (mShareFileFragment != null){
                ft.replace(R.id.share_fragment_container, mShareFileFragment, TAG_SHARE_FRAGMENT);

                if (mSearchFragment != null){
                    ft.hide(mShareFileFragment);
                    ft.add(R.id.share_fragment_container, mSearchFragment, TAG_SEARCH_FRAGMENT);
                    ft.addToBackStack(TAG_SEARCH_FRAGMENT);
                }
                ft.commit();
            }

        } else {
            // Add Share fragment
            mShareFileFragment = ShareFileFragment.newInstance(getFile(), getAccount());
            ft.replace(R.id.share_fragment_container, mShareFileFragment, TAG_SHARE_FRAGMENT);
            ft.commit();

            mSearchFragment = null;
        }

        handleIntent(getIntent());
    }


    @Override
    protected void onNewIntent(Intent intent) {
        setIntent(intent);
        handleIntent(intent);
    }


    private void handleIntent(Intent intent) {
        // Verify the action and get the query
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String query = intent.getStringExtra(SearchManager.QUERY);
            doMySearch(query);

        } else if (UsersAndGroupsSearchProvider.ACTION_SHARE_WITH.equals(intent.getAction())) {
            Uri data = intent.getData();
            doShareWith(
                    data.getLastPathSegment(),
                    UsersAndGroupsSearchProvider.DATA_GROUP.equals(data.getAuthority())
            );

        } else {
            Log_OC.wtf(TAG, "Unexpected intent " + intent.toString());
        }
    }

    private void doMySearch(String query) {
        // TODO implement , or prevent that search may be sent without choosing from the suggestions list
        Toast.makeText(this, "You want to search for [" + query + "]", Toast.LENGTH_SHORT).show();
    }

    private void doShareWith(String username, boolean isGroup) {
        // TODO implement
        if (isGroup) {
            Toast.makeText(this, "You want to SHARE with GROUP [" + username + "]", Toast.LENGTH_SHORT).show();

        } else {
            Toast.makeText(this, "You want to SHARE with USER [" + username + "]", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        //Save the fragment's instance
        getSupportFragmentManager().putFragment(outState, TAG_SHARE_FRAGMENT, mShareFileFragment);
        if (mSearchFragment != null) {
            getSupportFragmentManager().putFragment(outState, TAG_SEARCH_FRAGMENT, mSearchFragment);
        }

    }

    @Override
    public void showSearchUsersAndGroups() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        mSearchFragment = SearchFragment.newInstance(getFile(), getAccount());
        ft.hide(mShareFileFragment);
        ft.add(R.id.share_fragment_container, mSearchFragment, TAG_SEARCH_FRAGMENT);
        ft.addToBackStack(TAG_SEARCH_FRAGMENT);
        ft.commit();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        if (mSearchFragment != null){
            getSupportFragmentManager().popBackStackImmediate();
            mSearchFragment = null;
        }
    }

    /**
     * Updates the view associated to the activity after the finish of some operation over files
     * in the current account.
     *
     * @param operation Removal operation performed.
     * @param result    Result of the removal.
     */
    @Override
    public void onRemoteOperationFinish(RemoteOperation operation, RemoteOperationResult result) {
        super.onRemoteOperationFinish(operation, result);
        if (operation instanceof UnshareOperation) {
            if (mShareFileFragment != null){
                mShareFileFragment.refreshUsersOrGroupsList();
            }
        }

    }

    @Override
    public void onShareFragmentInteraction(Uri uri) {

    }

    @Override
    public void onSearchFragmentInteraction(Uri uri) {

    }
}
