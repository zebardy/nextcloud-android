call git submodule init
call git submodule update
call android.bat update project -p actionbarsherlock\library -n ActionBarSherlock
call android.bat update project -p .
copy /Y third_party\android-support-library\android-support-v4.jar actionbarsherlock\library\libs\android-support-v4.jar
call android.bat update test-project -p tests -m ..
