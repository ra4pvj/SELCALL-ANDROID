package ru.ra4pvj.selcall.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * АПЧ: спектрограмма (кадр 20 мс, шаг 10 мс). Для каждого кандидата центра c копится
 * сумма по кадрам |E(c-85) - E(c+85)| / E_кадра. У FSK в каждом кадре горит один из двух
 * тонов, поэтому метрика максимальна на верном центре, а шум даёт ровный фон. Инкрементальная.
 */
public final class Afc {
    final double fs, lo = 1000, hi = 2600, df = 2.5;
    final int win, hop, kLo, nb, sh;
    final float[] cosT, sinT;
    final double[] total;
    final ArrayDeque<float[]> history = new ArrayDeque<>();
    final int maxFrames;      // 0 = без ограничения (пакетная обработка)
    float[] pending = new float[0];
    int pendingLen = 0;

    public Afc(double fs, double windowSeconds) {
        this.fs = fs;
        win = Math.max(16, (int) (0.02 * fs));
        hop = Math.max(1, win / 2);
        kLo = (int) Math.round((lo - Selcall.SHIFT / 2) / df);
        int k1 = (int) Math.round((hi + Selcall.SHIFT / 2) / df);
        nb = k1 - kLo + 1;
        sh = (int) Math.round(Selcall.SHIFT / df);
        cosT = new float[nb * win];
        sinT = new float[nb * win];
        for (int b = 0; b < nb; b++) {
            double f = (kLo + b) * df;
            for (int n = 0; n < win; n++) {
                double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * n / (win - 1));
                double a = 2 * Math.PI * f * n / fs;
                cosT[b * win + n] = (float) (w * Math.cos(a));
                sinT[b * win + n] = (float) (w * Math.sin(a));
            }
        }
        total = new double[nb - sh];
        maxFrames = windowSeconds > 0 ? (int) (windowSeconds * fs / hop) : 0;
    }

    public synchronized void reset() {
        pendingLen = 0;
        history.clear();
        java.util.Arrays.fill(total, 0);
    }

    public synchronized void append(float[] x, int len) {
        if (pending.length < pendingLen + len) {
            float[] p = new float[Math.max(pendingLen + len, pending.length * 2)];
            System.arraycopy(pending, 0, p, 0, pendingLen);
            pending = p;
        }
        System.arraycopy(x, 0, pending, pendingLen, len);
        pendingLen += len;
        int start = 0;
        while (pendingLen - start >= win) {
            frame(start);
            start += hop;
        }
        if (start > 0) {
            System.arraycopy(pending, start, pending, 0, pendingLen - start);
            pendingLen -= start;
        }
    }

    private void frame(int s) {
        float[] E = new float[nb];
        double sum = 0;
        for (int b = 0; b < nb; b++) {
            float re = 0, im = 0;
            int o = b * win;
            for (int n = 0; n < win; n++) {
                float v = pending[s + n];
                re += v * cosT[o + n];
                im += v * sinT[o + n];
            }
            float e = re * re + im * im;
            E[b] = e;
            sum += e;
        }
        if (sum <= 1e-20) return;
        float[] vec = new float[nb - sh];
        for (int c = 0; c < nb - sh; c++) {
            float v = (float) (Math.abs(E[c] - E[c + sh]) / sum);
            vec[c] = v;
            total[c] += v;
        }
        if (maxFrames > 0) {
            history.addLast(vec);
            if (history.size() > maxFrames) {
                float[] old = history.removeFirst();
                for (int c = 0; c < old.length; c++) total[c] -= old[c];
            }
        }
    }

    /** До count лучших центров с разносом > 40 Гц. */
    public synchronized List<Double> topCenters(int count) {
        Integer[] order = new Integer[total.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(total[b], total[a]));
        List<Double> picked = new ArrayList<>();
        boolean any = false;
        for (double t : total) if (t > 0) { any = true; break; }
        if (any) {
            for (int i : order) {
                double c = (kLo + i) * df + Selcall.SHIFT / 2;
                if (c < lo || c > hi) continue;
                boolean ok = true;
                for (double p : picked) if (Math.abs(p - c) <= 40) { ok = false; break; }
                if (ok) picked.add(c);
                if (picked.size() >= count) break;
            }
        }
        if (picked.isEmpty()) picked.add(Selcall.F_CENTER);
        return picked;
    }
}
