package ru.ra4pvj.selcall.core;

/** Поток fsIn -> fsOut (8000): ФНЧ 3,4 кГц + линейная интерполяция. */
public final class Downsampler {
    private final double ratio;
    private final double[] taps;
    private final double[] hist;
    private double pos = 0;       // позиция следующего выходного отсчёта во входном потоке
    private long consumed = 0;

    public Downsampler(int fsIn, int fsOut) {
        ratio = (double) fsIn / fsOut;
        if (ratio > 1.001) {
            int n = 127;
            taps = new double[n];
            double sum = 0, fc = 3400.0 / fsIn;
            for (int i = 0; i < n; i++) {
                double m = i - (n - 1) / 2.0;
                double sinc = m == 0 ? 1 : Math.sin(Math.PI * 2 * fc * m) / (Math.PI * 2 * fc * m);
                double bl = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)) + 0.08 * Math.cos(4 * Math.PI * i / (n - 1));
                taps[i] = sinc * bl;
                sum += taps[i];
            }
            for (int i = 0; i < n; i++) taps[i] /= sum;
            hist = new double[n - 1];
        } else {
            taps = null;
            hist = null;
        }
    }

    /** Возвращает число выходных отсчётов, записанных в out (out достаточно длинный). */
    public int push(float[] x, int len, float[] out) {
        if (taps == null) {
            System.arraycopy(x, 0, out, 0, len);
            return len;
        }
        int nt = taps.length, h = nt - 1;
        double[] y = new double[len];
        for (int i = 0; i < len; i++) {
            double acc = 0;
            for (int k = 0; k < nt; k++) {
                int j = i + k;      // индекс в z = hist ++ x
                double v = j < h ? hist[j] : x[j - h];
                acc += v * taps[nt - 1 - k];
            }
            y[i] = acc;
        }
        // история
        if (len >= h) {
            for (int k = 0; k < h; k++) hist[k] = x[len - h + k];
        } else {
            System.arraycopy(hist, len, hist, 0, h - len);
            for (int k = 0; k < len; k++) hist[h - len + k] = x[k];
        }
        long start = consumed;
        consumed += len;
        int n = 0;
        while (pos < consumed - 1) {
            double t = pos - start;
            int i0 = (int) Math.floor(t);
            double fr = t - i0;
            double v = i0 < 0 ? y[0] : i0 + 1 < len ? y[i0] * (1 - fr) + y[i0 + 1] * fr : y[Math.min(i0, len - 1)];
            out[n++] = (float) v;
            pos += ratio;
        }
        return n;
    }
}
