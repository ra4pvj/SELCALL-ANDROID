package ru.ra4pvj.selcall.core;

/** Сигналы звонка и ответа в эфир (как в HF Selcall для Windows/macOS). */
public final class Tones {
    private Tones() {}

    public static final String[] STYLES = {"codan", "vertex", "beep", "kalinka"};
    public static final double ANSWER_SECONDS = 2.5;

    // Начало припева «Калинки» (И. Ларионов, 1860; общественное достояние), на октаву выше записи:
    // E5 | D5 B4 C5 | D5 B4 C5 | D5 C5 B4 | A4; длительность в четвертях; четверть 0,18 с.
    private static final double E5 = 659.26, D5 = 587.33, C5 = 523.25, B4 = 493.88, A4 = 440.0;
    private static final double QUARTER = 0.18;
    private static final double[][] KALINKA = {{E5, 2}, {D5, 2}, {B4, 1}, {C5, 1}, {D5, 2}, {B4, 1}, {C5, 1},
            {D5, 2}, {C5, 1}, {B4, 1}, {A4, 2}};

    private static final class Buf {
        float[] a = new float[48000];
        int n = 0;

        void add(double v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = (float) v;
        }

        float[] get() {
            return java.util.Arrays.copyOf(a, n);
        }
    }

    private static void tone(Buf b, int fs, double vol, double f, double sec) {
        int n = (int) (sec * fs);
        int r = Math.max(1, (int) (0.004 * fs));
        for (int i = 0; i < n; i++) {
            double w = 1;
            if (i < r) w = (double) i / r;
            else if (i >= n - r) w = (double) (n - 1 - i) / r;
            b.add(vol * w * Math.sin(2 * Math.PI * f * i / fs));
        }
    }

    private static void silence(Buf b, int fs, double sec) {
        int n = (int) (sec * fs);
        for (int i = 0; i < n; i++) b.add(0);
    }

    /** Один период звонка (для повтора по кругу). */
    public static float[] ring(String style, int fs, double vol) {
        Buf b = new Buf();
        if ("kalinka".equals(style)) {
            for (double[] note : KALINKA) {
                double dur = note[1] * QUARTER;
                int m = (int) ((dur - 0.015) * fs);
                for (int i = 0; i < m; i++) {
                    double t = (double) i / fs;
                    double env = Math.min(1, t / 0.006) * Math.exp(-1.2 * t / dur) * Math.min(1, (m - 1 - i) / (0.006 * fs));
                    b.add(vol * env * (0.85 * Math.sin(2 * Math.PI * note[0] * t) + 0.15 * Math.sin(4 * Math.PI * note[0] * t)));
                }
                silence(b, fs, 0.015);
            }
            silence(b, fs, 0.8);
        } else if ("vertex".equals(style)) {
            for (int k = 0; k < 2; k++) {
                for (int j = 0; j < 10; j++) {
                    tone(b, fs, vol, 1500, 0.025);
                    tone(b, fs, vol, 2000, 0.025);
                }
                silence(b, fs, 0.2);
            }
            silence(b, fs, 1.4);
        } else if ("beep".equals(style)) {
            for (int k = 0; k < 3; k++) {
                tone(b, fs, vol, 1200, 0.15);
                silence(b, fs, 0.1);
            }
            silence(b, fs, 1.0);
        } else {
            for (int k = 0; k < 5; k++) {
                tone(b, fs, vol, 1000, 0.08);
                tone(b, fs, vol, 1300, 0.08);
            }
            silence(b, fs, 1.0);
        }
        return b.get();
    }

    /** Ответ в эфир: мелодия один раз без паузы; тоны — 2,5 с с плавным концом. */
    public static float[] answer(String style, int fs, double vol) {
        float[] x = ring(style, fs, vol);
        if ("kalinka".equals(style)) return java.util.Arrays.copyOf(x, Math.max(0, x.length - (int) (0.8 * fs)));
        int n = (int) (ANSWER_SECONDS * fs);
        float[] y = new float[n];
        for (int i = 0; i < n; i++) y[i] = x[i % x.length];
        int f = Math.min(n, (int) (0.02 * fs));
        for (int i = 0; i < f; i++) y[n - f + i] *= (float) (1 - (double) i / Math.max(1, f - 1));
        return y;
    }
}
