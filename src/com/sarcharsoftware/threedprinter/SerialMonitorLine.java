package com.sarcharsoftware.threedprinter;

public class SerialMonitorLine extends Object {
    private String line;
    private boolean is_send;
    private int line_number;

    public SerialMonitorLine(String _line, int _line_number, boolean _is_send) {
        line = _line;
        line_number = line_number;
        is_send = _is_send;
    }

    public boolean isSend() { return is_send; }
    public int getLineNumber() { return line_number; }

    public void setLine(String s) { line = s; }
    public String getLine() { return line; }

    public boolean equals(SerialMonitorLine other) {
        return getLineNumber() == other.getLineNumber();
    }
}
