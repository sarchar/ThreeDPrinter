package com.sarcharsoftware.threedprinter;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Message;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

public class StatusFragment extends Fragment {
    ThreeDPrinterMain main;

    TextView state;
    TextView duration;
    TextView location;
    TextView tempuratures;

    public static StatusFragment newInstance() {
        StatusFragment frag = new StatusFragment();
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
        View v = inflater.inflate(R.layout.status, parent, false);
        main = (ThreeDPrinterMain)getActivity();

        state = (TextView)v.findViewById(R.id.state);
        if(state != null) {
            state.setText(new String(""));
            state.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }

        duration = (TextView)v.findViewById(R.id.duration);
        if(duration != null) {
            duration.setText(new String(""));
            duration.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }

        location = (TextView)v.findViewById(R.id.location);
        if(location != null) {
            location.setText(new String(""));
            location.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        }

        tempuratures = (TextView)v.findViewById(R.id.tempuratures);
        if(tempuratures != null) {
            tempuratures.setText(new String(""));
            tempuratures.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        }

        return v;
    }

    public void update(Bundle b) {
        String state = b.getString("state");
        if(this.state != null) {
            this.state.setText(state);
            if(state.startsWith("Printing")) {
                this.state.setTypeface(Typeface.DEFAULT, Typeface.BOLD_ITALIC);
            } else {
                this.state.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            }

            if(state.startsWith("Disconnected")) {
                if(this.duration != null) this.duration.setText("");
                if(this.location != null) this.location.setText("");
                if(this.tempuratures != null) this.tempuratures.setText("");
                return;
            }
        }

        int duration = b.getInt("duration");
        int progress = b.getInt("progress");
        if(this.duration != null) this.duration.setText(String.format("%s (%d%%)", ThreeDPrinterMain.formatPrintDuration(duration), progress));

        double x = b.getDouble("x");
        double y = b.getDouble("y");
        double z = b.getDouble("z");
        if(this.location != null) this.location.setText(String.format("X: %.2f Y: %.2f Z: %.3f", x, y, z));

        double extruder_tempurature = b.getDouble("extruder_tempurature"); 
        double extruder_tempurature_target = b.getDouble("extruder_tempurature_target"); 
        double bed_tempurature = b.getDouble("bed_tempurature"); 
        double bed_tempurature_target = b.getDouble("bed_tempurature_target"); 
        if(this.tempuratures != null) this.tempuratures.setText(String.format("E: %.1f/%.1f B: %.1f/%.1f", extruder_tempurature, extruder_tempurature_target, bed_tempurature, bed_tempurature_target));
    }
} 

