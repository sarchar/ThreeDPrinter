package com.sarcharsoftware.threedprinter;

import android.os.Bundle;
import android.os.Message;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;


import java.util.ArrayList;
import java.util.HashMap;

public class SerialMonitorFragment extends Fragment {
    
    ThreeDPrinterMain main;
    EditText send_text;
    Button send_button;
    ListView list_view;
    SerialMonitorLineAdapater adapter;

    ArrayList<SerialMonitorLine> serial_lines = new ArrayList<SerialMonitorLine>();
    HashMap<Integer, SerialMonitorLine> serial_lines_by_number = new HashMap<Integer, SerialMonitorLine>();

    public static SerialMonitorFragment newInstance() {
        SerialMonitorFragment frag = new SerialMonitorFragment();
        Bundle b = new Bundle();
        // setArgs
        frag.setArguments(b);
        return frag;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // getArguments()....
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup parent, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.serial_monitor, parent, false);
        main = (ThreeDPrinterMain)getActivity();

        // Setup send text line
        send_text = (EditText)v.findViewById(R.id.sendtext);
        send_button = (Button)v.findViewById(R.id.sendbutton);
        send_button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String line = send_text.getText().toString();
                send_text.setText("");

                Message msg = Message.obtain(main.getMessageHandler(), ThreeDPrinterMain.MSG_SEND, 0, 0);
                Bundle b = new Bundle();
                b.putString("line", String.format("%s\r\n", line));
                msg.setData(b);
                main.getMessageHandler().sendMessage(msg);

                SerialMonitorFragment.this.addSentLine(-1, line);
            }
        });


        list_view = (ListView)v.findViewById(R.id.listview);
        adapter = new SerialMonitorLineAdapater(main.getApplicationContext(), serial_lines);
        list_view.setAdapter(adapter);

        list_view.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, final View view, int position, long id) {
                final SerialMonitorLine item = (SerialMonitorLine) parent.getItemAtPosition(position);
                /*
                view.animate().setDuration(2000).alpha(0).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        serial_lines.remove(item);
                        adapter.notifyDataSetChanged();
                        view.setAlpha(1);
                    }
                });
                */
            }
        });

        list_view.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                final SerialMonitorLine item = (SerialMonitorLine) parent.getItemAtPosition(position);
                Toast.makeText(main.getApplicationContext(), String.valueOf(item.getLine()), Toast.LENGTH_LONG).show();
                return true;
            }
        });

        // Start off at the end (when lines are in memory but no view exists)
        list_view.post(new Runnable() {
            @Override
            public void run() {
                // Select the last row so it will scroll into view...
                list_view.setSelection(adapter.getCount() - 1);
            }
        });

        return v;
    }

    void serialLine(Integer line_number, String line) {
        Log.d("ListeningTask", String.format("d. Adding %d: %s[][]", line_number, line));

        boolean scroll = false;

        if(serial_lines_by_number.containsKey(line_number)) {
            SerialMonitorLine sml = serial_lines_by_number.get(line_number);
            if(sml.getLine().equals(line)) return; // no change
            sml.setLine(line);
        } else {
            SerialMonitorLine sml = new SerialMonitorLine(line, line_number.intValue(), false);
            serial_lines.add(sml);
            serial_lines_by_number.put(line_number, sml);
            scroll = true;
        }

        if(list_view != null && adapter != null) {
            adapter.notifyDataSetChanged();

            if(scroll) {
                list_view.post(new Runnable() {
                    @Override
                    public void run() {
                        // Select the last row so it will scroll into view...
                        list_view.setSelection(adapter.getCount() - 1);
                    }
                });
            }
        }
    }

    void addSentLine(Integer line_number, String line) {
        Log.d("ListeningTask", String.format("Sent %d: %s[][]", line_number, line));

        boolean scroll = false;
        serial_lines.add(new SerialMonitorLine(line, line_number.intValue(), true));
        scroll = true;

        if(list_view != null && adapter != null) {
            adapter.notifyDataSetChanged();

            if(scroll) {
                list_view.post(new Runnable() {
                    @Override
                    public void run() {
                        // Select the last row so it will scroll into view...
                        list_view.setSelection(adapter.getCount() - 1);
                    }
                });
            }
        }
    }


}
