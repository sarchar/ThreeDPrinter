package com.sarcharsoftware.threedprinter;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import java.io.*;
import java.net.*;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class ThreeDPrinterService extends Service {
    final int NUMBER_OF_BROADCASTS_BEFORE_DYING = 10;
    final int DELAY_BETWEEN_BROADCASTS = 3000;
    final int DELAY_BETWEEN_FINDING = 10000;
    final int DELAY_BETWEEN_M105_LONG = 30000; // Long time - when the M105 response is received then we wait less
    final int DELAY_BETWEEN_M105_SHORT = 10000;

    final int MSG_FOUND_PRINTER = 1;
    final int MSG_STATUS = 2;
    final int MSG_SERIAL = 3;

    final String APP_NAME = "ThreeDPrinter";

    public class ThreeDPrinterServiceBinder extends Binder {
        ThreeDPrinterService getService() {
            return ThreeDPrinterService.this;
        }
    }

    ConnectivityManager connectivity_manager = null;

    NotificationManager notification_manager = null;
    NotificationCompat.Builder notification_builder = null;

    Timer main_timer = null;
    long next_find_time;

    long next_m105_time;

    boolean is_error = false;
    boolean is_printing = false;
    boolean finished_printing = false;
    boolean is_connected = false;
    int print_duration = 0;
    int print_progress = 0;
    double[] location = new double[4];
    double[] tempuratures = new double[4];

    @Override
    public void onCreate() {
        Log.d(APP_NAME, "ThreeDPrinterService.onCreate()");
        connectivity_manager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        notification_manager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        main_timer = new Timer();
        send_serial_lines = new ConcurrentLinkedQueue<String>();;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(APP_NAME, "ThreeDPrinterService.onStartCommand()");
        super.onStartCommand(intent, flags, startId);

        next_find_time = 0;

        main_timer.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                if(polling_3d_printer_task_thread != null) {
                    if(polling_3d_printer_task_thread.getState() == Thread.State.TERMINATED) {
                        Log.d(APP_NAME, "Polling3DPrinterTask TERMINATED");
                        polling_3d_printer_task_thread = null;
                        polling_3d_printer_task = null;
                        cleanupNotifications();
                        is_connected = false;
                    } else {
                        if(next_m105_time <= System.currentTimeMillis()) {
                            sendSerialLine("M105\r\n");
                            next_m105_time = System.currentTimeMillis() + DELAY_BETWEEN_M105_LONG;
                        }
                    }
                }

                if(find_3d_printer_task_thread != null) {
                    if(find_3d_printer_task_thread.getState() == Thread.State.TERMINATED) {
                        Log.d(APP_NAME, "Find3DPrinterTask TERMINATED");
                        find_3d_printer_task_thread = null;
                        find_3d_printer_task = null;
                    }
                }

                // TODO: check if we switched onto a new wifi (or from data to wifi) and set next_find_time = 0 if so

                if(polling_3d_printer_task == null && find_3d_printer_task == null && next_find_time <= System.currentTimeMillis()) {
                    if(isOnWifi()) startFind3DPrinterTask();
                    next_find_time = System.currentTimeMillis() + (DELAY_BETWEEN_BROADCASTS * NUMBER_OF_BROADCASTS_BEFORE_DYING) + DELAY_BETWEEN_FINDING;
                }
            }
        }, 0, 250);

        return START_STICKY;
    }

    private final IBinder binder = new ThreeDPrinterServiceBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        Log.d(APP_NAME, "ThreeDPrinterService.onDestroy()");
        main_timer.purge();
        cleanupNotifications();
    }

    private boolean isOnWifi() {
        if(connectivity_manager == null) return false;
        NetworkInfo wifi = connectivity_manager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        return wifi.isConnectedOrConnecting();
    }

    private boolean isOnMobile() {
        if(connectivity_manager == null) return false;
        NetworkInfo mobile = connectivity_manager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
        return mobile.isConnectedOrConnecting();
    } 

    public boolean isConnectedToPrinter() { return is_connected; }
    public boolean finishedPrinting() { return finished_printing; }
    public boolean isPrinting() { return is_printing; }
    public boolean isError() { return is_error; }
    public int getPrintDuration() { return print_duration; }
    public int getPrintProgress() { return print_progress; }
    public double getX() { return location[0]; }
    public double getY() { return location[1]; }
    public double getZ() { return location[2]; }
    public double getE() { return location[3]; }
    public double getExtruderTempurature() { return tempuratures[0]; }
    public double getExtruderTempuratureTarget() { return tempuratures[1]; }
    public double getBedTempurature() { return tempuratures[2]; }
    public double getBedTempuratureTarget() { return tempuratures[3]; }

    void cleanupNotifications() {
        if(notification_builder != null) {
            if(!finished_printing) {
                // Only change to lost connection if we haven't shown the finished printing message
                notification_builder.setContentTitle("3D Printer - Lost Connection");
            }
            notification_builder.setOngoing(false).setProgress(100, 100, false);
            notification_manager.notify(1, notification_builder.build());
            notification_builder = null;
        }
    }

    void update3DPrinterStatus(JSONObject response) throws JSONException {
        // If we get a printer update, then we're connected
        is_connected = true;

        String status = response.getString("status");
        print_duration = response.getInt("time");

        if(status.equals("printing")) {
            print_progress = response.getInt("progress");

            if(notification_builder == null) {
                Intent app_intent = new Intent(this, ThreeDPrinterMain.class);
                PendingIntent pending_intent = PendingIntent.getActivity(this, 0, app_intent, 0);
                notification_builder = new NotificationCompat.Builder(this);
                notification_builder.setContentTitle("3D Printer - Printing")
                    .setOngoing(true)
                    .setContentIntent(pending_intent)
                    .setTicker("Your 3D printer is printing")
                    .setSmallIcon(android.R.drawable.stat_notify_sync);
            }

            notification_builder
                    .setContentText(ThreeDPrinterMain.formatPrintDuration(print_duration))
                    .setProgress(100, print_progress, false);
            notification_manager.notify(1, notification_builder.build());

            is_error = false;
            is_printing = true;
            finished_printing = false;
        } else if(status.equals("idle") && is_printing) {
            if(notification_builder != null) {
                // Done!
                notification_builder
                        .setContentText(ThreeDPrinterMain.formatPrintDuration(print_duration))
                        .setContentTitle("3D Printer - Finished Printing")
                        .setTicker("Your 3D printer is done printing!")
                        //.setSmallIcon(android.R.drawable.stat_notify_sync_anim0)
                        .setOngoing(false)
                        .setProgress(0, 0, false);
                notification_manager.notify(1, notification_builder.build());
                is_printing = false;
                finished_printing = true;
            }
        } else if(status.equals("error")) {
            if(notification_builder != null) {
                // Done!
                notification_builder
                        .setContentText(ThreeDPrinterMain.formatPrintDuration(print_duration))
                        .setContentTitle("3D Printer - Error")
                        .setTicker("Your 3D printer had an error!")
                        //.setSmallIcon(android.R.drawable.stat_notify_sync_anim0)
                        .setOngoing(false)
                        .setProgress(0, 0, false);
                notification_manager.notify(1, notification_builder.build());
                is_error = true;
                is_printing = false;
                finished_printing = true;
            }
        }

        JSONArray loc = response.getJSONArray("location");
        for(int i = 0; i < 4; i++) {
            location[i] = loc.getDouble(i);
        }
    }

    HashMap<Integer, String> serial_lines = new HashMap<Integer, String>();
    ArrayDeque<Integer> serial_changes = new ArrayDeque<Integer>();

    int last_serial_line = -1;

    void tryParseM105Response(String line) {
        try {
            // Check for M105 responses
            if(line.startsWith("ok T:") && line.contains(" B:")) {
                // Extruder tempurature is at the char after T: until the space following the next /
                String tmp = line.substring(5);
                int s = tmp.indexOf("/");
                if(s > -1) {
                    int e = tmp.indexOf(" ", s+1);
                    if(e > -1) {
                        String temp1 = tmp.substring(0, s).trim();
                        String temp2 = tmp.substring(s+1, e).trim();

                        tempuratures[0] = Double.parseDouble(temp1);
                        tempuratures[1] = Double.parseDouble(temp2);

                        int b = tmp.indexOf(" B:");
                        tmp = tmp.substring(b + 3);
                        s = tmp.indexOf("/");
                        if(s > -1) {
                            e = tmp.indexOf(" ", s+1);
                            if(e > -1) {
                                temp1 = tmp.substring(0, s).trim();
                                temp2 = tmp.substring(s+1, e).trim();

                                tempuratures[2] = Double.parseDouble(temp1);
                                tempuratures[3] = Double.parseDouble(temp2);

                                // Good M105!
                                next_m105_time = System.currentTimeMillis() + DELAY_BETWEEN_M105_SHORT;
                            }
                        }
                    }
                }
            }
        } catch(NumberFormatException e) {
            e.printStackTrace();
        }
    }

    // Line number and the corresponding line length should only ever be increasing!
    void handle3DPrinterSerial(int line_number, String line) {
        if(line == null) return;
        Log.d(APP_NAME, String.format("Got line %d: %s", line_number, line));

        tryParseM105Response(line);

        synchronized(serial_lines) {
            serial_lines.put(line_number, line);
            last_serial_line = line_number;
        }
    }

    int getLastSerialLine() {
        return last_serial_line;
    }

    String getSerialLine(int line_number) {
        synchronized(serial_lines) {
            return serial_lines.get(line_number);
        }
    }

    ConcurrentLinkedQueue<String> send_serial_lines;

    void sendSerialLine(String line) {
        try {
            line = URLEncoder.encode(line, "utf-8");
            send_serial_lines.add(line);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    class MessageHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            Bundle b = msg.getData();

            switch(msg.what) {
            case MSG_FOUND_PRINTER:
                String http_address = b.getString("http_address");
                int http_port = b.getInt("http_port");
                startPolling3DPrinterTask(http_address, http_port);

                // since we found the server we want to retry immediately if we lost connection
                next_find_time = 0;
                break;
            case MSG_STATUS:
                try {
                    JSONObject response = new JSONObject(b.getString("status"));
                    update3DPrinterStatus(response);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                break;
            case MSG_SERIAL:
                handle3DPrinterSerial(b.getInt("line_number"), b.getString("line"));
                break;
            default:
                super.handleMessage(msg);
            }
        }
    }

    final MessageHandler messageHandler = new MessageHandler();


    ////////////////////////////////////////////////////////////////////////////////
    // Find3DPrinterTask - locate the printer on a local network
    Find3DPrinterTask find_3d_printer_task = null;
    Thread            find_3d_printer_task_thread = null;

    private class Find3DPrinterTask implements Runnable {
        Thread thread = null;
        public void setThread(Thread _thread) {
            thread = _thread;
        }

        public void run() {
            Log.d(APP_NAME, "Find3DPrinterTask.run()");
            try {
                DatagramSocket recv_socket = new DatagramSocket();
                recv_socket.setSoTimeout(200);
                int local_port = recv_socket.getLocalPort();

                byte[] message = new byte[6];
                message[0] = (byte)(ThreeDPrinterMain.BROADCAST_MAGIC & 0xFF);
                message[1] = (byte)((ThreeDPrinterMain.BROADCAST_MAGIC >> 8) & 0xFF);
                message[2] = (byte)((ThreeDPrinterMain.BROADCAST_MAGIC >> 16) & 0xFF);
                message[3] = (byte)((ThreeDPrinterMain.BROADCAST_MAGIC >> 24) & 0xFF);
                message[4] = (byte)(local_port & 0xFF);
                message[5] = (byte)((local_port >> 8) & 0xFF);
                DatagramPacket packet = new DatagramPacket(message, message.length, 
                                                           InetAddress.getByName(ThreeDPrinterMain.BROADCAST_GROUP),
                                                           ThreeDPrinterMain.BROADCAST_PORT);

                DatagramSocket send_socket = new DatagramSocket();
                send_socket.setBroadcast(true);
                
                byte[] recv_buf = new byte[256];
                DatagramPacket recv_packet = new DatagramPacket(recv_buf, recv_buf.length);
                int count = NUMBER_OF_BROADCASTS_BEFORE_DYING;
                while(!thread.interrupted() && count > 0) {
                    count -= 1;

                    // TODO: If not on WiFi, exit

                    send_socket.send(packet);
                    Log.d(APP_NAME, String.format("Sent broadcast packet (size = %d)", packet.getLength()));

                    // Wait and retry
                    long end_time = System.currentTimeMillis() + DELAY_BETWEEN_BROADCASTS;
                    while(end_time > System.currentTimeMillis()) {
                        try {
                            recv_socket.receive(recv_packet);

                            // Got a packet, parse it and fire off the message then exit
                            if(recv_packet.getLength() > 0) {
                                if((recv_buf[0] == ~message[0])
                                  && (recv_buf[1] == ~message[1])
                                  && (recv_buf[2] == ~message[2])
                                  && (recv_buf[3] == ~message[3])) {
                                    int http_port = recv_buf[4] | (recv_buf[5] << 8);
                                    String http_address = recv_packet.getAddress().getHostAddress();
                                    Log.d(APP_NAME, String.format("Got response from %s (size = %d): HTTP PORT = %d\n", http_address, recv_packet.getLength(), http_port));

                                    // Send the address to the service
                                    Message msg = Message.obtain(messageHandler, MSG_FOUND_PRINTER, 0, 0);
                                    Bundle b = new Bundle();
                                    b.putString("http_address", http_address);
                                    b.putInt("http_port", http_port);
                                    msg.setData(b);
                                    messageHandler.sendMessage(msg);
                                    return;
                                }
                            }
                        } catch (InterruptedIOException e) {
                            // timeout, safely ignore and retry
                        }
                    }
                }
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
    }

    void startFind3DPrinterTask() {
        if(find_3d_printer_task == null) {
            find_3d_printer_task = new Find3DPrinterTask();
            find_3d_printer_task_thread = new Thread(find_3d_printer_task);
            find_3d_printer_task.setThread(find_3d_printer_task_thread);
            find_3d_printer_task_thread.start();
        }
    }

    ////////////////////////////////////////////////////////////////////////////////
    // Polling3DPrinterTask - repeatedly poll the printer for the latest updates
    Polling3DPrinterTask polling_3d_printer_task = null;
    Thread               polling_3d_printer_task_thread = null;

    private class Polling3DPrinterTask implements Runnable {
        final int POLL_DELAY = 750;

        String http_address;
        int http_port;
        Thread thread = null;

        public Polling3DPrinterTask(String _http_address, int _http_port) {
            http_address = _http_address;
            http_port = _http_port;
        }

        public void setThread(Thread _thread) {
            thread = _thread;
        }

        private JSONObject getStatus() {
            return ThreeDPrinterMain.readJsonFromUrl(String.format("http://%s:%d/status", http_address, http_port));
        }

        private String getSerialLine(int line_number) {
            return ThreeDPrinterMain.readFromUrl(String.format("http://%s:%d/serial?line=%d", http_address, http_port, line_number));
        }

        private void sendSerialLine(String line) {
            Log.d(APP_NAME, String.format("Sending: %s", line));
            ThreeDPrinterMain.readFromUrl(String.format("http://%s:%d/send?line=%s", http_address, http_port, line));
        }

        public void run() {
            Log.d(APP_NAME, "Polling3DPrinterTask.run()");

            Message msg;
            Bundle b;

            int last_serial_line = 0;
            int last_serial_line_position = 0;

            long next_poll_time = 0;

            while(!thread.interrupted()) {
                try {
                    int serial_line = last_serial_line;
                    int serial_line_position = last_serial_line_position;

                    // Fetch /status
                    if(next_poll_time <= System.currentTimeMillis()) {
                        JSONObject response = getStatus();
                        if(response == null) return;
                        Log.d(APP_NAME, String.format("Server response: %s", response.toString()));

                        msg = Message.obtain(messageHandler, MSG_STATUS, 0, 0);
                        b = new Bundle();
                        b.putString("status", response.toString());
                        msg.setData(b);
                        messageHandler.sendMessage(msg);

                        // Check if we need to pull any /serial lines
                        JSONObject serial_state = response.getJSONObject("serial");
                        serial_line = serial_state.getInt("current_line");
                        serial_line_position = serial_state.getInt("line_pos");

                        next_poll_time = System.currentTimeMillis() + POLL_DELAY;
                    }

                    // Pull any excess lines
                    if(serial_line > last_serial_line || serial_line_position > last_serial_line_position) {
                        // Fetch all lines up to serial_line, including serial_line if serial_line_position is > 0
                        for(int line_number = last_serial_line; line_number <= serial_line; line_number++) {
                            if((line_number < serial_line) || (line_number == serial_line && serial_line_position > 0)) {
                                String line = getSerialLine(line_number);
                                if(line == null) {
                                    // Lost connection!
                                    return;
                                }

                                msg = Message.obtain(messageHandler, MSG_SERIAL, 0, 0);
                                b = new Bundle();
                                b.putInt("line_number", line_number);
                                b.putString("line", line);
                                msg.setData(b);
                                messageHandler.sendMessage(msg);
                            }
                        }

                        last_serial_line = serial_line;
                        last_serial_line_position = serial_line_position;
                    } else if(serial_line < last_serial_line) {
                        // TODO: handle serial reset
                    }

                    // Immediately send anything we are supposed to
                    String send_serial_line = ThreeDPrinterService.this.send_serial_lines.poll();
                    while(send_serial_line != null) {
                        sendSerialLine(send_serial_line);
                        send_serial_line = ThreeDPrinterService.this.send_serial_lines.poll();
                    }

                    // Small delay to not eat up resources
                    Thread.sleep(50);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    void startPolling3DPrinterTask(final String http_address, final int http_port) {
        if(polling_3d_printer_task == null) {
            polling_3d_printer_task = new Polling3DPrinterTask(http_address, http_port);
            polling_3d_printer_task_thread = new Thread(polling_3d_printer_task);
            polling_3d_printer_task.setThread(polling_3d_printer_task_thread);
            polling_3d_printer_task_thread.start();
        }
    }
}
