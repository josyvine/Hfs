package com.hfs.security.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.hfs.security.ui.SplashActivity;
import com.hfs.security.utils.HFSDatabaseHelper;

/**
 * Advanced Stealth Mode Receiver for Oppo/Realme (Phase 8 Fix).
 * Logic:
 * 1. Listens for the 'ACTION_NEW_OUTGOING_CALL' event.
 * 2. Retrieves the Custom Secret PIN saved by the user in Settings.
 * 3. If the dialed number matches the PIN:
 *    - Aborts the call (ResultData = null).
 *    - Prevents the call from appearing in logs (abortBroadcast).
 *    - Launches the HFS Security App.
 */
public class StealthLaunchReceiver extends BroadcastReceiver {

    private static final String TAG = "HFS_StealthTrigger";

    @Override
    public void onReceive(Context context, Intent intent) {
        // We only care about new outgoing calls
        String action = intent.getAction();
        
        if (action != null && action.equals(Intent.ACTION_NEW_OUTGOING_CALL)) {
            
            // 1. Retrieve the number that the user just dialed
            String dialedNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
            
            if (dialedNumber == null) {
                return;
            }

            // 2. Fetch the CUSTOM PIN set by the user from the Database Helper
            HFSDatabaseHelper db = HFSDatabaseHelper.getInstance(context);
            String savedPin = db.getMasterPin(); 

            // 3. Normalize both numbers for comparison
            // This removes any non-digit characters like spaces, dashes, *, or #
            String cleanDialed = dialedNumber.replaceAll("[^\\d]", "");
            String cleanSaved = savedPin.replaceAll("[^\\d]", "");

            // 4. Verify match and ensure the PIN is not empty
            if (cleanDialed.equals(cleanSaved) && !cleanSaved.isEmpty()) {
                
                Log.i(TAG, "Custom Stealth PIN matched. Intercepting call...");

                /* 
                 * 5. CANCEL THE CALL 
                 * Setting result data to NULL tells Android to stop placing the call.
                 * abortBroadcast() ensures other apps don't try to handle it.
                 */
                setResultData(null);
                abortBroadcast();

                // 6. LAUNCH THE HFS APPLICATION
                Intent launchIntent = new Intent(context, SplashActivity.class);
                
                /*
                 * FLAG_ACTIVITY_NEW_TASK: 
                 * Required to launch an activity from outside an existing activity context.
                 * 
                 * FLAG_ACTIVITY_CLEAR_TOP:
                 * Ensures that if the app is already open, it resets to the entry point.
                 */
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                
                try {
                    context.startActivity(launchIntent);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to launch HFS Activity: " + e.getMessage());
                }
            }
        }
    }
}