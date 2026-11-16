package ru.ra4pvj.selcall.core;

/** Принятый вызов (результат декодера). */
public final class Call {
    public String type;          // "selcall" | "message"
    public int format;
    public String to;
    public String from;
    public int category;
    public String message;
    public String eos;           // "ACK-RQ" | "ACK-BQ" | "EOS" | число
    public String ecc;           // "ok" | "нет" | "BAD"
    public boolean eccOk;
    public int conflicts;        // расхождений DX/RX
    public int singleCopy;
    public boolean complete;     // после EOS виден ещё символ
    public double time;          // начало (с)
    public double endTime;       // конец кадра (с)
    public boolean inverted;     // LSB
    public double centerHz;
    public int phasingScore;

    // служебные
    int bitpos;
    int endRel;

    public boolean isAck() {
        return "ACK-BQ".equals(eos);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append(String.format(java.util.Locale.US, "[%7.2fs] %-7s %s -> %s", time, type.toUpperCase(), from, to));
        if (message != null && !message.isEmpty()) s.append("  \"").append(message).append('"');
        s.append(String.format(java.util.Locale.US, "  fmt=%d cat=%d %s ECC:%s %s  f0=%.1fHz %s",
                format, category, eos, ecc, eccOk ? "OK" : "СОМНИТЕЛЬНО", centerHz, inverted ? "inverted" : "normal"));
        if (conflicts > 0) s.append(" conflicts=").append(conflicts);
        return s.toString();
    }
}
