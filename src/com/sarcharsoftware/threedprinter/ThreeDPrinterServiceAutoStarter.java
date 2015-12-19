package com.sarcharsoftware.threedprinter;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class ThreeDPrinterServiceAutoStarter extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d("ListeningTask", "ThreeDPrinterServiceAutoStarter.onReceive()");
        context.startService(new Intent(context, ThreeDPrinterService.class));
    }
}
