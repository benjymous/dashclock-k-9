/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.grapefruitopia.dashclock.k9;

import java.util.ArrayList;
import java.util.List;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import com.google.android.apps.dashclock.api.DashClockExtension;
import com.google.android.apps.dashclock.api.ExtensionData;

public class K9MailExtension extends DashClockExtension {
    private static final String TAG = "K9MailExtension";

    public static final String PREF_NAME = "pref_name";
    
	static final Uri k9AccountsUri = Uri.parse("content://com.fsck.k9.messageprovider/accounts/");
	static final String k9UnreadUri = "content://com.fsck.k9.messageprovider/account_unread/";
	static final String k9MessageProvider = "content://com.fsck.k9.messageprovider/";
	
	ContentObserver contentObserver = null;
	BroadcastReceiver receiver = null;
	IntentFilter filter = null;
	
	@Override
	public void onCreate() {
        super.onCreate();
        
        Log.d(TAG, "onCreate()");
        
        int versionNumber = 0;
        
        String packageName = "com.fsck.k9";
        try {
            PackageInfo pi = getApplicationContext().getPackageManager().getPackageInfo(packageName, PackageManager.GET_META_DATA);
            versionNumber = pi.versionCode;
            String versionName = pi.versionName;
            
            Log.d(TAG, "K-9 is installed - "+versionNumber+" "+versionName);
            
        } catch (NameNotFoundException e) {
        	Log.d(TAG, "K-9 not found");
        }
        
        if( versionNumber <= 16024 ) {
	        // Register a listener for broadcasts (needed for the older versions of k9)
			Log.d(TAG, "Initialising BroadcastReceiver for old K-9 version");
			receiver = new BroadcastReceiver() {  
		        @Override  
		        public void onReceive(Context context, Intent intent) {  
		            Log.d(TAG, "receiver.onReceive()");
		            doRefresh();
		        }
		    };
			
			filter = new IntentFilter();
			filter.addAction("com.fsck.k9.intent.action.EMAIL_RECEIVED");
			filter.addAction("com.fsck.k9.intent.action.EMAIL_DELETED");
			filter.addDataScheme("email");
			registerReceiver(receiver, filter);
        }
        else {
	    	// Register our own content observer, rather than using addWatchContentUris()
	    	// since DashClock might not have permission to access the database 	
			Log.d(TAG, "Initialising ContentObserver for new K-9 version");
	    	contentObserver = new ContentObserver(null) {
	            @Override
	            public void onChange(boolean selfChange) {
	            	Log.d(TAG, "contentResolver.onChange()");
	                doRefresh();
	            }
	        };
	        getContentResolver().registerContentObserver(Uri.parse(k9UnreadUri), true, contentObserver);
        }
        
	}
	
    @Override
    protected void onInitialize(boolean isReconnect) {
    	super.onInitialize(isReconnect);
    	
    	Log.d(TAG, "onInitialize("+isReconnect+")");
       	
    }
    
    @Override
    protected void onUpdateData(int reason) {
    	Log.d(TAG, "onUpdateData("+reason+")");
    	doRefresh();
    }
    
    @Override
	public void onDestroy()
    {
    	Log.d(TAG, "onDestroy()");
        if (contentObserver != null) {
            getContentResolver().unregisterContentObserver(contentObserver);
            contentObserver = null;
        }
        if (receiver!=null) {
        	unregisterReceiver(receiver);
        	receiver = null;
        }
    }
    
    protected void doRefresh() {
    	Log.d(TAG, "doRefresh()");
    	
		int count = getUnreadK9Count(this);
		
		Log.d(TAG, ""+count+" unread emails");
		
        // Publish the extension data update.
        publishUpdate(new ExtensionData()
                .visible(count>0)
                .icon(R.drawable.ic_launcher)
                .status(Integer.toString(count))
                .expandedTitle(count + " unread")
                .expandedBody("K-9 mail")
                .clickIntent(new Intent().setClassName("com.fsck.k9", "com.fsck.k9.activity.Accounts")));
    }
	
	public static class CursorHandler {
		private List<Cursor> cursors = new ArrayList<Cursor>();

		public Cursor add(Cursor c) {
			if (c!=null)
				cursors.add(c);
			return c;
		}

		public void closeAll() {
			for(Cursor c : cursors) {
				if(!c.isClosed())
					c.close();
			}
		}
	}

	private static int k9UnreadCount = 0;	

	public static int getUnreadK9Count(Context context) {
		refreshUnreadK9Count(context);

		return k9UnreadCount;
	}

	private static int getUnreadK9Count(Context context, int accountNumber) {
		CursorHandler ch = new CursorHandler();
		try {
			Cursor cur = ch.add(context.getContentResolver().query(Uri.parse(k9UnreadUri+"/"+accountNumber+"/"), null, null, null, null));
		    if (cur!=null) {
		    	Log.d(TAG, "k9: "+cur.getCount()+ " unread rows returned");

		    	if (cur.getCount()>0) {
			    	cur.moveToFirst();
			    	int unread = 0;
			    	int nameIndex = cur.getColumnIndex("accountName");
			    	int unreadIndex = cur.getColumnIndex("unread");
			    	do {
			    		String acct = cur.getString(nameIndex);
			    		int unreadForAcct = cur.getInt(unreadIndex);
			    		Log.d(TAG, "k9: "+acct+" - "+unreadForAcct+" unread");
			    		unread += unreadForAcct;
			    	} while (cur.moveToNext());
				    cur.close();
				    return unread;
		    	}
		    }
		    else {
		    	Log.d(TAG, "Failed to query k9 unread contentprovider.");
		    }
		}
		catch (IllegalStateException e) {
			Log.d(TAG, "k-9 unread uri unknown.");
		}
		return 0;
	}

	public static void refreshUnreadK9Count(Context context) {		
		int accounts = getK9AccountCount(context);
		if (accounts>0) {
			int count = 0;
			for (int acct=0; acct<accounts; ++acct) {
				count += getUnreadK9Count(context, acct);
			}
			k9UnreadCount = count;
		}	
	}

	public static int getK9AccountCount(Context context) {
		CursorHandler ch = new CursorHandler();
		try {
			Cursor cur = ch.add( context.getContentResolver().query(k9AccountsUri, null, null, null, null) );
		    if (cur!=null) {
		    	//if (Preferences.logging) Log.d(MetaWatch.TAG, "k9: "+cur.getCount()+ " account rows returned");

		    	int count = cur.getCount();

		    	return count;
		    }
		    else {
		    	//if (Preferences.logging) Log.d(MetaWatch.TAG, "Failed to query k9 unread contentprovider.");
		    }
		}
		catch (IllegalStateException e) {
			//if (Preferences.logging) Log.d(MetaWatch.TAG, "k-9 accounts uri unknown.");
		}
		catch (java.lang.SecurityException e) {
			//if (Preferences.logging) Log.d(MetaWatch.TAG, "Permissions failure accessing k-9 databases");
		}
		finally {
			ch.closeAll();
		}
		return 0;

	}
}
