package com.sarcharsoftware.threedprinter;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import android.util.TypedValue;
import android.view.Window;
import android.view.WindowManager;
import android.view.View;
import android.widget.Toast;

import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;

// TEMP - for BlankFragment
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.view.ViewGroup;
//

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;

import org.json.JSONObject;
import org.json.JSONException;

import com.astuetz.PagerSlidingTabStrip;

public class ThreeDPrinterMain extends FragmentActivity
{
    public static final String BROADCAST_GROUP = "224.1.1.1";
    public static final int BROADCAST_PORT = 10334;
    public static final int BROADCAST_MAGIC = 0xE468A9CC;

    public static final int MSG_STATUS = 1;
    public static final int MSG_SERIAL_LINE = 2;
    public static final int MSG_SEND = 3;
    public static final int MSG_DISMISS_CONNECTING_DIALOG = 4;
    public static final int MSG_SHOW_CONNECTING_DIALOG = 5;

    private PagerSlidingTabStrip tabs;
    private ViewPager pager;
    private MyPagerAdapter pager_adapter;

    private ProgressDialog connecting_dialog = null;
    private boolean connecting_dialog_canceled;

    private ServiceConnection service_connection = null;
    private ThreeDPrinterService service;
    private Timer update_timer;

    ArrayList<SerialMonitorLine> serial_lines = new ArrayList<SerialMonitorLine>();
    HashMap<Integer, SerialMonitorLine> serial_lines_by_number = new HashMap<Integer, SerialMonitorLine>();

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        Intent intent = new Intent(this, ThreeDPrinterService.class);
        startService(intent);

        tabs = (PagerSlidingTabStrip)findViewById(R.id.tabs);
        pager = (ViewPager)findViewById(R.id.pager);
        pager_adapter = new MyPagerAdapter(getSupportFragmentManager());
        pager.setAdapter(pager_adapter);

        final int page_margin = (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources().getDisplayMetrics());
        pager.setPageMargin(page_margin);
        tabs.setViewPager(pager);
        setColors();

        // Start the connecting dialog immediately
        showConnectingDialog();

        final Intent thisIntent = getIntent();
        service_connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName className, IBinder service_binder) {
                service = ((ThreeDPrinterService.ThreeDPrinterServiceBinder)service_binder).getService();
            }

            public void onServiceDisconnected(ComponentName className) {
                service = null;
            }
        };

        // bind to the background service
        bindService(new Intent(this, ThreeDPrinterService.class), service_connection, Context.BIND_AUTO_CREATE);

        // poll for updates from the service
        update_timer = new Timer();
        update_timer.scheduleAtFixedRate(new TimerTask() {
            int last_serial_line = 0;

            private void updatePrinter() {
                updateStatus();
                updateSerialMonitor();
            }

            private void updateStatus() {
                Message msg = Message.obtain(message_handler, MSG_STATUS, 0, 0);
                Bundle b = new Bundle();

                // State
                String state = "Idle";
                if(service.isPrinting()) {
                    state = "Printing";
                } else if(service.isError()) {
                    state = "Error";
                }
                b.putString("state", state);

                // Duration
                b.putInt("duration", service.getPrintDuration());
                b.putInt("progress", service.getPrintProgress());

                // Location
                b.putDouble("x", service.getX());
                b.putDouble("y", service.getY());
                b.putDouble("z", service.getZ());


                // Tempurature
                b.putDouble("extruder_tempurature", service.getExtruderTempurature());
                b.putDouble("extruder_tempurature_target", service.getExtruderTempuratureTarget());
                b.putDouble("bed_tempurature", service.getBedTempurature());
                b.putDouble("bed_tempurature_target", service.getBedTempuratureTarget());

                msg.setData(b);
                message_handler.sendMessage(msg);
            }

            private void updateDisconnectedStatus() {
                Message msg = Message.obtain(message_handler, MSG_STATUS, 0, 0);
                Bundle b = new Bundle();

                // State
                b.putString("state", "Disconnected");

                msg.setData(b);
                message_handler.sendMessage(msg);
            }

            private void updateSerialMonitor() {
                int line_count = service.getLastSerialLine();
                for(int line_number = last_serial_line; line_number <= line_count; line_number += 1) {
                    String line = service.getSerialLine(line_number);
                    Log.d("ListeningTask", String.format("1. Adding %d: %s[][]", line_number, line));
                    if(line == null) break;
                
                    Message msg = Message.obtain(message_handler, MSG_SERIAL_LINE, 0, 0);
                    Bundle b = new Bundle();
                    b.putInt("line_number", line_number);
                    b.putString("line", line);
                    msg.setData(b);
                    message_handler.sendMessage(msg);
                }
                
                last_serial_line = line_count;
            }
            @Override
            public void run() {
                if(service == null) return;

                // Show message to user
                if(!service.isConnectedToPrinter()) updateDisconnectedStatus();

                // If the connecting dialog is showing, then we haven't yet seen the printer
                if(connecting_dialog != null) {
                    // Once we find the printer, dismiss the dialog
                    if(service.isConnectedToPrinter()) {
                        message_handler.sendMessage(Message.obtain(message_handler, MSG_DISMISS_CONNECTING_DIALOG, 0, 0));
                    // If the user dismisses the dialog by hitting Back, then close the dialog but still wait for a connection in the background
                    } else if(connecting_dialog_canceled) {
                        connecting_dialog = null;
                    }
                // If the user closed the dialog, then we still haven't seen the printer yet
                } else if(connecting_dialog_canceled) {
                    // once we establish the connection in the background, then we can start updating
                    if(service.isConnectedToPrinter()) {
                        connecting_dialog_canceled = false;
                    }
                // But if we lose connection after being connected before, then repeat this whole thing again
                } else if(!service.isConnectedToPrinter()) {
                    message_handler.sendMessage(Message.obtain(message_handler, MSG_SHOW_CONNECTING_DIALOG, 0, 0));
                // Otherwise, update!
                } else {
                    updatePrinter();
                }
            }
        }, 0, 250);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unbindService(service_connection);
    }

    private void setColors() {
        int primary_color = getResources().getColor(R.color.primary_color);
        int primary_color_dark = getResources().getColor(R.color.primary_color_dark);

        tabs.setIndicatorColor(primary_color);

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            final Handler handler = new Handler();
            final Drawable.Callback drawable_callback = new Drawable.Callback() {
                @Override
                public void invalidateDrawable(Drawable who) {
                    getActionBar().setBackgroundDrawable(who);
                }

                @Override
                public void scheduleDrawable(Drawable who, Runnable what, long when) {
                    handler.postAtTime(what, when);
                }

                @Override
                public void unscheduleDrawable(Drawable who, Runnable what) {
                    handler.removeCallbacks(what);
                }
            };

            Drawable color_drawable = new ColorDrawable(primary_color);
            LayerDrawable ld = new LayerDrawable(new Drawable[] { color_drawable });

            if (true) {//old_background == null) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    ld.setCallback(drawable_callback);
                } else {
                    getActionBar().setBackgroundDrawable(ld);
                }

            } else {
                //TransitionDrawable td = new TransitionDrawable(new Drawable[] { old_background, ld });
                TransitionDrawable td = new TransitionDrawable(new Drawable[] { null, ld });

                // workaround for broken ActionBarContainer drawable handling on
                // pre-API 17 builds
                // https://github.com/android/platform_frameworks_base/commit/a7cc06d82e45918c37429a59b14545c6a57db4e4
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    td.setCallback(drawable_callback);
                } else {
                    getActionBar().setBackgroundDrawable(td);
                }

                td.startTransition(200);
            }

            //old_background = ld;

            // http://stackoverflow.com/questions/11002691/actionbar-setbackgrounddrawable-nulling-background-from-thread-handler
            getActionBar().setDisplayShowTitleEnabled(false);
            getActionBar().setDisplayShowTitleEnabled(true);
        }

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();

            // clear FLAG_TRANSLUCENT_STATUS flag:
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);

            // add FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS flag to the window
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

            // finally change the color
            window.setStatusBarColor(primary_color_dark);
        }
    }

    private void showConnectingDialog() {
        if(connecting_dialog != null) {
            connecting_dialog.dismiss();
        }
        connecting_dialog = ProgressDialog.show(this, "", "Connecting to Printer...", true, true, new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                connecting_dialog_canceled = true;
            }
        });
    }

    class MessageHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            Bundle b = msg.getData();

            switch(msg.what) {
            case MSG_STATUS:
                pager_adapter.status_fragment.update(b);
                break;
            case MSG_SERIAL_LINE:
                pager_adapter.serial_monitor_fragment.serialLine(b.getInt("line_number"), b.getString("line"));
                break;
            case MSG_SEND:
                service.sendSerialLine(b.getString("line"));
                break;
            case MSG_DISMISS_CONNECTING_DIALOG:
                if(connecting_dialog != null) {
                    connecting_dialog.dismiss();
                    connecting_dialog = null;
                }
                break;
            case MSG_SHOW_CONNECTING_DIALOG:
                showConnectingDialog();
                break;
            default:
                super.handleMessage(msg);
            }
        }
    }

    final MessageHandler message_handler = new MessageHandler();
    public MessageHandler getMessageHandler() { return message_handler; }

    public class BlankFragment extends Fragment {
        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup parent, Bundle savedInstanceState) {
            return new FrameLayout(getActivity());
        }
    }

    public class MyPagerAdapter extends FragmentPagerAdapter {

        private class POSITIONS {
            public static final int STATUS = 0;
            public static final int CONTROL = 1;
            public static final int SERIAL = 2;
            public static final int SETTINGS = 3;
        };

        private final String[] TITLES = { "Status", "Control", "Serial", "Settings" };

        StatusFragment status_fragment;
        SerialMonitorFragment serial_monitor_fragment;

        public MyPagerAdapter(FragmentManager fm) {
            super(fm);

            status_fragment         = StatusFragment.newInstance();
            serial_monitor_fragment = SerialMonitorFragment.newInstance();
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return TITLES[position];
        }

        @Override
        public int getCount() {
            return TITLES.length;
        }

        @Override
        public Fragment getItem(int position) {
            switch(position) {
            case POSITIONS.STATUS:
                return status_fragment;
            case POSITIONS.SERIAL:
                return serial_monitor_fragment;
            default:
                return new BlankFragment();
            }
        }
    }

    // From http://stackoverflow.com/questions/4308554/simplest-way-to-read-json-from-a-url-in-java
    public static JSONObject readJsonFromUrl(String url) {
        try {
            InputStream is = new URL(url).openStream();
            try {
                BufferedReader rd = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")));
                String jsonText = readAll(rd);
                JSONObject json = new JSONObject(jsonText);
                return json;
            } catch(JSONException e) {
                e.printStackTrace();
                return null;
            } finally {
                is.close();
            }
        } catch(IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String readFromUrl(String url) {
        try {
            InputStream is = new URL(url).openStream();
            try {
                BufferedReader rd = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")));
                return readAll(rd);
            } finally {
                is.close();
            }
        } catch(IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String readAll(Reader rd) throws IOException {
        StringBuilder sb = new StringBuilder();
        int cp;
        while ((cp = rd.read()) != -1) {
            sb.append((char) cp);
        }
        return sb.toString();
    }

    public static String formatPrintDuration(int d) {
        if(d == 1) return new String("1 second");
        if(d < 60) {
            return String.format("%d seconds", d);
        } else if(d < 60*60) {
            return String.format("%d minutes", d/60);
        } else {
            return String.format("%d hours, %d minutes", d/(60*60), (d%(60*60))/60);
        }
    }

}

