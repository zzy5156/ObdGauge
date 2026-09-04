package com.cloudwolf.obdgauge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启接收器。接收器本身的启停由设置页控制（PackageManager.setComponentEnabledSetting）。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        if (!Prefs.isAutoBootEnabled(context)) {
            return;
        }
        if (!Prefs.isFloatingEnabled(context)) {
            return;
        }
        FloatingService.start(context);
    }
}
