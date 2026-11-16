package ru.ra4pvj.selcall.core;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Самопроверка кодека (как python/selcall.py selftest). */
public final class SelfTest {
    private SelfTest() {}

    public interface Log {
        void println(String s);
    }

    private static float[] concat(List<float[]> parts) {
        int n = 0;
        for (float[] p : parts) n += p.length;
        float[] out = new float[n];
        int o = 0;
        for (float[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    private static void feed(StreamReceiver rx, float[] x, List<Call> got) {
        float[] ch = new float[800];
        for (int i = 0, k = 0; i < x.length; i += 800, k++) {
            int n = Math.min(800, x.length - i);
            System.arraycopy(x, i, ch, 0, n);
            rx.append(ch, n);
            if (k % 10 == 9) got.addAll(rx.process(false));
        }
    }

    /** recording — эталонная запись SELCALL_from_1111_to_1234.wav (может быть null). */
    public static boolean run(InputStream recording, Log log) throws Exception {
        int fs = 48000;
        Random rng = new Random(1);
        String[][] tests = {{"1234", "5678", null}, {"123456", "654321", "PRIVET, KAK SLYSHNO? 73"},
                {"0042", "0007", "Test message #1"}};
        List<float[]> parts = new ArrayList<>();
        parts.add(new float[fs / 2]);
        for (String[] t : tests) {
            parts.add(Selcall.modulate(Selcall.buildCall(t[0], t[1], t[2]), fs, 1.0, 1740, 0.5));
            parts.add(new float[fs / 2]);
        }
        float[] x = concat(parts);
        for (int i = 0; i < x.length; i++) x[i] += (float) (rng.nextGaussian() * 0.35);   // шум, расстройка +40 Гц
        List<Call> res = Selcall.decode(x, fs, null);
        for (Call c : res) log.println(c.toString());
        boolean ok = res.size() == tests.length;
        for (int i = 0; ok && i < tests.length; i++) {
            Call c = res.get(i);
            String[] t = tests[i];
            ok = c.to.equals(t[0]) && c.from.equals(t[1]) && c.message.equals(t[2] == null ? "" : t[2]) && c.eccOk;
        }
        log.println("batch decode: " + (ok ? "OK" : "FAIL"));

        // потоковый приём 8 кГц
        StreamReceiver rx = new StreamReceiver(8000, 30);
        int n8 = x.length / 6;
        float[] x8 = new float[n8];
        for (int i = 0; i < n8; i++) x8[i] = x[i * 6];
        Downsampler ds = new Downsampler(48000, 8000);   // проверим и дециматор
        float[] y8 = new float[x.length / 6 + 16];
        int m = ds.push(x, x.length, y8);
        List<Call> got = new ArrayList<>();
        feed(rx, Arrays.copyOf(y8, m), got);
        rx.append(new float[16000], 16000);
        got.addAll(rx.process(true));
        feed(rx, new float[8000 * 70], got);                  // тишина: повторов быть не должно
        float[] same = Selcall.modulate(Selcall.buildCall("1234", "5678", null), 8000, 6.0, 1785, 0.5);
        float[] z = concat(Arrays.asList(same, new float[8000 * 3]));
        feed(rx, z, got);                                     // тот же вызов ещё дважды — оба приняты
        feed(rx, z, got);
        feed(rx, new float[8000 * 40], got);
        boolean streamOk = got.size() == tests.length + 2;
        for (Call c : got) {
            log.println("  stream: " + c);
            streamOk &= c.eccOk;
        }
        log.println("stream decode: " + (streamOk ? "OK" : "FAIL (" + got.size() + ")"));
        ok &= streamOk;

        int[] ref = {125, 109, 125, 108, 125, 107, 125, 106, 125, 105, 125, 104, 120, 120, 12, 120, 34, 120,
                100, 12, 11, 34, 11, 100, 117, 11, 117, 11, 117, 117};
        boolean frameOk = Arrays.equals(Selcall.buildCall("1234", "1111", null), ref);
        log.println("frame matches CODAN recording: " + (frameOk ? "OK" : "FAIL"));
        ok &= frameOk;

        if (recording != null) {
            Wav w = Wav.read(recording);
            List<Call> r = Selcall.decode(w.samples, w.fs, null);
            for (Call c : r) log.println("  " + c);
            boolean recOk = r.size() == 1 && r.get(0).from.equals("1111") && r.get(0).to.equals("1234") && r.get(0).eccOk;
            log.println("real recording 1111 -> 1234: " + (recOk ? "OK" : "FAIL"));
            ok &= recOk;
        }
        log.println("SELFTEST " + (ok ? "OK" : "FAIL"));
        return ok;
    }

    public static void main(String[] args) throws Exception {
        InputStream in = args.length > 0 ? new java.io.FileInputStream(args[0]) : null;
        long t = System.currentTimeMillis();
        boolean ok = run(in, System.out::println);
        System.out.println("time " + (System.currentTimeMillis() - t) + " ms");
        System.exit(ok ? 0 : 1);
    }
}
