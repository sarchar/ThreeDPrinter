package com.sarcharsoftware.threedprinter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;


public class SerialMonitorLineAdapater extends ArrayAdapter<SerialMonitorLine> { // TODO: change String to a custom object SerialMonitorLine
    private final Context context;
    private final List<SerialMonitorLine> values;

    static class ViewHolder {
        public TextView text;
        public ImageView image;
        public boolean is_send;
    }

    public SerialMonitorLineAdapater(Context context, List<SerialMonitorLine> values) {
        super(context, -1, values);
        this.context = context;
        this.values = values;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        SerialMonitorLine item = (SerialMonitorLine)getItem(position);
        View rowView = convertView;

        if(rowView != null) {
            ViewHolder viewHolder = (ViewHolder)rowView.getTag();
            if(item.isSend() != viewHolder.is_send) rowView = null;
        }

        if(rowView == null) {
            LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            if(item.isSend()) {
                rowView = inflater.inflate(R.layout.send_line, parent, false);
            } else {
                rowView = inflater.inflate(R.layout.recv_line, parent, false);
            }

            ViewHolder viewHolder = new ViewHolder();
            viewHolder.text = (TextView) rowView.findViewById(R.id.firstLine);
            viewHolder.image = (ImageView) rowView.findViewById(R.id.icon);
            viewHolder.is_send = item.isSend();
            rowView.setTag(viewHolder);
        }

        ViewHolder viewHolder = (ViewHolder)rowView.getTag();
        viewHolder.text.setText(item.getLine());

        return rowView;
    }
}

