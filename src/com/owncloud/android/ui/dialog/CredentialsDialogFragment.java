/* ownCloud Android client application
 *   Copyright (C) 2014 ownCloud Inc.
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

package com.owncloud.android.ui.dialog;

import com.actionbarsherlock.app.SherlockDialogFragment;
import com.owncloud.android.R;
import com.owncloud.android.authentication.AuthenticatorActivity;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.AlertDialog.Builder;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager.LayoutParams;
import android.webkit.HttpAuthHandler;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;



/**
 *  Dialog to input authentication credentials
 * 
 */
public class CredentialsDialogFragment extends SherlockDialogFragment
    implements DialogInterface.OnClickListener {

    private WebView mWebView = null;
    private HttpAuthHandler mHandler = null;

    private EditText mUsernameET;
    private EditText mPasswordET;
    
    private String mUsernameStr;
    private String mPasswordStr;


    /**
     * Public factory method to create new CredentialsDialogFragment instances.
     * @param webView       WebView that is being loaded
     * @param handler       HttpAuthHandler
     * @return              Dialog ready to show
     */
    public static CredentialsDialogFragment newInstanceForCredentials(WebView webView, HttpAuthHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("Trying to create instance with parameter handler == null");
        }
        CredentialsDialogFragment frag = new CredentialsDialogFragment();
        frag.mHandler = handler;
        frag.mWebView = webView;
        return frag;
    }


    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {

        // Create field for username
        mUsernameET = new EditText(getSherlockActivity());
        mUsernameET.setHint(getSherlockActivity().getText(R.string.auth_username));

        // Create field for password
        mPasswordET = new EditText(getSherlockActivity());
        mPasswordET.setHint(getSherlockActivity().getText(R.string.auth_password));
        mPasswordET.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        // Prepare LinearLayout for dialog
        LinearLayout ll = new LinearLayout(getSherlockActivity());
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.addView(mUsernameET);
        ll.addView(mPasswordET);
        
        ll.requestFocus();
        
        setRetainInstance(true);

        Builder authDialog = new AlertDialog
                .Builder(getSherlockActivity())
                .setTitle(getSherlockActivity().getText(R.string.saml_authentication_required_text))
                .setView(ll)
                .setCancelable(false)
                .setPositiveButton(R.string.common_ok, this)
                .setNegativeButton(R.string.common_cancel, this);

        Dialog d = authDialog.create();
        d.getWindow().setSoftInputMode(LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        return d;
    }


    @Override
    public void onPause() {
        super.onPause();
        // Due to the use of setRetainInstance(true) for keep the dialog over the rest of dialogs,
        // we need to save the inputs text for being injected in onResume()
        mUsernameStr = mUsernameET.getText().toString();
        mPasswordStr = mPasswordET.getText().toString();
    }


    @Override
    public void onResume() {
        super.onResume();
        mUsernameET.setText(mUsernameStr);
        mPasswordET.setText(mPasswordStr);
    }


    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == AlertDialog.BUTTON_POSITIVE) { 

            String username = mUsernameET.getText().toString().trim();
            String password = mPasswordET.getText().toString().trim();

            // Proceed with the authentication
            mHandler.proceed(username, password);
            dialog.dismiss();

        } else if (which == AlertDialog.BUTTON_NEGATIVE) {
            dialog.dismiss();
            mWebView.stopLoading();
            ((AuthenticatorActivity)getActivity()).doNegativeAuthenticatioDialogClick();
        }
    }
    
    
    @Override
    public void onDestroyView() {
      if (getDialog() != null && getRetainInstance())
        getDialog().setDismissMessage(null);
      super.onDestroyView();
    }

}
