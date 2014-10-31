/* ownCloud Android client application
 *   Copyright (C) 2011-2012  Bartek Przybylski
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
package com.owncloud.android.db;

import com.owncloud.android.MainApp;
import com.owncloud.android.lib.common.utils.Log_OC;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Custom database helper for ownCloud
 * 
 * @author Bartek Przybylski
 * 
 */
public class DbHandler {
    private SQLiteDatabase mDB;
    private OpenerHelper mHelper;
    private final String mDatabaseName;
    private final int mDatabaseVersion = 3;

    private final String TABLE_INSTANT_UPLOAD = "instant_upload";

    public enum UploadStatus {UPLOAD_STATUS_UPLOAD_LATER, UPLOAD_STATUS_UPLOAD_FAILED};
    
    public DbHandler(Context context) {
        mDatabaseName = MainApp.getDBName();
        mHelper = new OpenerHelper(context);
        mDB = mHelper.getWritableDatabase();
    }

    public void close() {
        mDB.close();
    }

    /**
     * Store a file persistantly for upload.
     * @param filepath
     * @param account
     * @param message
     * @return
     */
    public boolean putFileForLater(String filepath, String account, String message) {
        ContentValues cv = new ContentValues();
        cv.put("path", filepath);
        cv.put("account", account);
        cv.put("attempt", String.valueOf(UploadStatus.UPLOAD_STATUS_UPLOAD_LATER));
        cv.put("message", message);
        long result = mDB.insert(TABLE_INSTANT_UPLOAD, null, cv);
        Log_OC.d(TABLE_INSTANT_UPLOAD, "putFileForLater returns with: " + result + " for file: " + filepath);
        return result != -1;
    }

    /**
     * Update upload status of file.
     * 
     * @param filepath
     * @param status
     * @param message
     * @return
     */
    public int updateFileState(String filepath, UploadStatus status, String message) {
        ContentValues cv = new ContentValues();
        cv.put("attempt", String.valueOf(status));
        cv.put("message", message);
        int result = mDB.update(TABLE_INSTANT_UPLOAD, cv, "path=?", new String[] { filepath });
        Log_OC.d(TABLE_INSTANT_UPLOAD, "updateFileState returns with: " + result + " for file: " + filepath);
        return result;
    }

    public Cursor getAwaitingFiles() {
        return mDB.query(TABLE_INSTANT_UPLOAD, null, "attempt=" + UploadStatus.UPLOAD_STATUS_UPLOAD_LATER, null, null, null, null);
    }

  //ununsed until now. uncomment if needed.
//    public Cursor getFailedFiles() {
//        return mDB.query(TABLE_INSTANT_UPLOAD, null, "attempt>" + UploadStatus.UPLOAD_STATUS_UPLOAD_LATER, null, null, null, null);
//    }

  //ununsed until now. uncomment if needed.
//    public void clearFiles() {
//        mDB.delete(TABLE_INSTANT_UPLOAD, null, null);
//    }

    /**
     * Remove file from upload list. Should be called when upload succeed or failed and should not be retried. 
     * @param localPath
     * @return true when one or more pending files was removed
     */
    public boolean removePendingFile(String localPath) {
        long result = mDB.delete(TABLE_INSTANT_UPLOAD, "path = ?", new String[] { localPath });
        Log_OC.d(TABLE_INSTANT_UPLOAD, "delete returns with: " + result + " for file: " + localPath);
        return result != 0;

    }

    private class OpenerHelper extends SQLiteOpenHelper {
        public OpenerHelper(Context context) {
            super(context, mDatabaseName, null, mDatabaseVersion);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_INSTANT_UPLOAD + " (" + " _id INTEGER PRIMARY KEY, " + " path TEXT,"
                    + " account TEXT,attempt INTEGER,message TEXT);");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE " + TABLE_INSTANT_UPLOAD + " ADD COLUMN attempt INTEGER;");
            }
            db.execSQL("ALTER TABLE " + TABLE_INSTANT_UPLOAD + " ADD COLUMN message TEXT;");

        }
    }
}
