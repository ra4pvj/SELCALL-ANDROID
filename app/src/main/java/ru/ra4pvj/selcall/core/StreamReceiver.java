package ru.ra4pvj.selcall.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Потоковый приёмник: моно-отсчёты (лучше 8000 Гц) в скользящем окне, периодический поиск вызовов.
 * Возвращает только новые вызовы; time — абсолютное (с от старта). Не потокобезопасен.
 */
public final class StreamReceiver {
    public final int fs;
    public final double windowSeconds;
    /** null = автоматическая АПЧ, иначе фиксированный центр. */
    public Double fixedCenter = null;
    public List<Double> lastCenters = new ArrayList<>();
    public double repeatWindow = 8.0;

    private float[] buf;
    private int len = 0;
    private long base = 0;           // абсолютный индекс buf[0]
    private final Afc afc;
    private final List<Double> reported = new ArrayList<>();
    private final Map<String, Double> recent = new HashMap<>();

    public StreamReceiver(int fs, double windowSeconds) {
        this.fs = fs;
        this.windowSeconds = windowSeconds;
        buf = new float[(int) (windowSeconds * fs)];
        afc = new Afc(fs, 20);
    }

    public double now() {
        return (double) (base + len) / fs;
    }

    public void reset() {
        len = 0;
        base = 0;
        afc.reset();
        reported.clear();
        recent.clear();
    }

    public void append(float[] x, int n) {
        afc.append(x, n);
        int cap = buf.length;
        if (n >= cap) {
            System.arraycopy(x, n - cap, buf, 0, cap);
            base += len + n - cap;
            len = cap;
            return;
        }
        if (len + n > cap) {
            int drop = len + n - cap;
            System.arraycopy(buf, drop, buf, 0, len - drop);
            len -= drop;
            base += drop;
        }
        System.arraycopy(x, 0, buf, len, n);
        len += n;
    }

    public List<Call> process(boolean flush) {
        List<Call> out = new ArrayList<>();
        if (len <= fs * 2) return out;
        List<Double> centers = new ArrayList<>();
        if (fixedCenter != null) {
            centers.add(fixedCenter);
        } else {
            centers.addAll(afc.topCenters(3));
            boolean has = false;
            for (double c : centers) if (Math.abs(c - Selcall.F_CENTER) < 20) has = true;
            if (!has) centers.add(Selcall.F_CENTER);
        }
        lastCenters = centers;
        List<Call> found = new ArrayList<>();
        for (double c : centers) found.addAll(Selcall.decodeAt(buf, len, fs, c));
        double t0 = (double) base / fs;
        double end = now();
        for (Call c : Selcall.merge(found)) {
            double absT = t0 + c.time;
            boolean dup = false;
            for (double r : reported) if (Math.abs(r - absT) < 1.0) { dup = true; break; }
            if (dup) continue;
            String key = c.from + "|" + c.to + "|" + c.message + "|" + c.eos;
            Double prev = recent.get(key);
            if (prev != null && Math.abs(absT - prev) < repeatWindow) continue;
            // нет символа после EOS: ждём 1,5 с (EOS мог оказаться буквой текста)
            if (!c.complete && !flush && end - (t0 + c.endTime) < 1.5) continue;
            if (!c.eccOk && !flush && end - absT < 20) continue;
            c.time = absT;
            c.endTime += t0;
            reported.add(absT);
            if (c.eccOk) recent.put(key, absT);
            out.add(c);
        }
        reported.removeIf(r -> r <= t0 - 5);
        for (Iterator<Map.Entry<String, Double>> it = recent.entrySet().iterator(); it.hasNext(); ) {
            if (end - it.next().getValue() >= repeatWindow + 60) it.remove();
        }
        return out;
    }
}
