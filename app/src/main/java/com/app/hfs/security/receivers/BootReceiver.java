package com.hfs.security.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.hfs.security.services.AppMonitorService;
import com.hfs.security.utils.HFSDatabaseHelper;

/**
 * System Boot Receiver (Phase 7).
 * Automatically restarts the HFS Security Monitor when the device reboots.
 * This prevents intruders from bypassing the security by simply restarting the phone.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "HFS_BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        // We listen for both standard boot and "Quick Boot" (used by some manufacturers)
        String action = intent.getAction();
        
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || 
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            
            Log.i(TAG, "Device reboot detected. Restarting HFS Security Guard...");

            HFSDatabaseHelper db = HFSDatabaseHelper.getInstance(context);

            // Logic: Only start the service if it was active before the reboot
            // or if the user has completed the initial setup.
            if (db.isSetupComplete()) {
                startHfsService(context);
            }
        }
    }

    /**
     * Helper to launch the AppMonitorService as a Foreground Service.
     */
    private void startHfsService(Context context) {
        Intent serviceIntent = new Intent(context, AppMonitorService.class);
        
        try {
            // In Android 8.0 (API 26) and above, we must start foreground services 
            // specifically to avoid background execution limits.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.d(TAG, "HFS AppMonitorService successfully restarted on boot.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to restart HFS Service on boot: " + e.getMessage());
        }
    }
}