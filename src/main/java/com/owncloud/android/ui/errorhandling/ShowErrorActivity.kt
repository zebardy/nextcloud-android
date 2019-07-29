/*
 * Nextcloud Android client application
 *
 * @author Andy Scherzinger
 * Copyright (C) 2019 Andy Scherzinger <info@andy-scherzinger.de>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.owncloud.android.ui.errorhandling

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.owncloud.android.R
import com.owncloud.android.utils.ClipboardUtil
import com.owncloud.android.utils.DisplayUtils
import com.owncloud.android.utils.ThemeUtils
import kotlinx.android.synthetic.main.activity_show_error.*
import kotlinx.android.synthetic.main.toolbar_standard.*

class ShowErrorActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ERROR_TEXT = "error"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_show_error)

        text_view_error.text = intent.getStringExtra(EXTRA_ERROR_TEXT)

        setSupportActionBar(toolbar)

        val snackbar = DisplayUtils.createSnackbar(
            error_page_container,
            R.string.error_report_issue_text, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.error_report_issue_action
            ) { v ->
                reportIssue()
            }

        val primaryColor = ThemeUtils.primaryColor(this)
        val fontColor = ThemeUtils.fontColor(this)

        ThemeUtils.colorSnackbar(this, snackbar)
        ThemeUtils.colorStatusBar(this, primaryColor)
        progressBar?.visibility = View.GONE

        toolbar.setBackgroundColor(primaryColor)
        if (toolbar.overflowIcon != null) {
            ThemeUtils.tintDrawable(toolbar.overflowIcon, fontColor)
        }

        if (toolbar.navigationIcon != null) {
            ThemeUtils.tintDrawable(toolbar.navigationIcon, fontColor)
        }

        ThemeUtils.setColoredTitle(supportActionBar, R.string.common_error, this)

        snackbar.show()
    }

    private fun reportIssue() {
        ClipboardUtil.copyToClipboard(this, text_view_error.text.toString(), false)
        val issueLink = getString(R.string.report_issue_link)
        if (!issueLink.isEmpty()) {
            val uriUrl = Uri.parse(issueLink)
            val intent = Intent(Intent.ACTION_VIEW, uriUrl)
            DisplayUtils.startIntentIfAppAvailable(intent, this, R.string.no_browser_available)
        }
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_LONG).show()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.activity_error_show, menu)

        ThemeUtils.tintDrawable(menu?.findItem(R.id.error_share)?.icon, ThemeUtils.fontColor(this))

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        return when (item?.itemId) {
            R.id.error_share -> {
                onClickedShare(); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun onClickedShare() {
        val intent = Intent(Intent.ACTION_SEND)
        intent.putExtra(Intent.EXTRA_SUBJECT, "Nextcloud Error")
        intent.putExtra(Intent.EXTRA_TEXT, text_view_error.text)
        intent.type = "text/plain"
        startActivity(intent)
    }
}
