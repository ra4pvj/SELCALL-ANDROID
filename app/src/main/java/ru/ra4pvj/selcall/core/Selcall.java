package ru.ra4pvj.selcall.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * HF Selcall CCIR 493-4 (CODAN / Barrett) — кодер и декодер.
 * Перенос python/selcall.py и Sources/SelcallCore из ra4pvj/SELCALL (тот же алгоритм).
 *
 * FSK 100 бод, сдвиг 170 Гц, центр 1785 Гц (1870 = "1", 1700 = "0" в USB).
 * Символ 10 бит = 7 инф. (LSB первым) + 3 проверочных (число нулей, MSB первым).
 * Кадр (по записи CODAN): DX: 125 x6 | F A.. C S.. [MSG..] EOS x3;  RX: 109..104 | F F F A.. C S.. [MSG..] EOS.
 */
public final class Selcall {
    private Selcall() {}

    public static final double BAUD = 100.0;
    public static final double F_CENTER = 1785.0;
    public static final double SHIFT = 170.0;

    public static final int FMT_SELCALL = 120;
    public static final int CAT_ROUTINE = 100;
    public static final int EOS_ACK_RQ = 117;
    public static final int EOS_ACK_BQ = 122;
    public static final int EOS_EOS = 127;
    public static final int PHASE_DX = 125;
    public static final int MAX_MSG = 64;

    static boolean isEos(Integer v) {
        return v != null && (v == EOS_ACK_RQ || v == EOS_ACK_BQ || v == EOS_EOS);
    }

    static String eosName(int v) {
        switch (v) {
            case EOS_ACK_RQ: return "ACK-RQ";
            case EOS_ACK_BQ: return "ACK-BQ";
            case EOS_EOS: return "EOS";
            default: return Integer.toString(v);
        }
    }

    static boolean isRxPhasing(Integer v) {
        return v != null && v >= 104 && v <= 111;
    }

    // ------------------------------------------------------------ символы
    /** 10 бит символа в порядке передачи. */
    public static int[] symBits(int v) {
        int[] b = new int[10];
        int ones = 0;
        for (int i = 0; i < 7; i++) {
            b[i] = (v >> i) & 1;
            ones += b[i];
        }
        int zeros = 7 - ones;
        b[7] = (zeros >> 2) & 1;
        b[8] = (zeros >> 1) & 1;
        b[9] = zeros & 1;
        return b;
    }

    static final short[] SYM_LUT = new short[1024];

    static {
        Arrays.fill(SYM_LUT, (short) -1);
        for (int v = 0; v < 128; v++) {
            int[] b = symBits(v);
            int w = 0;
            for (int i = 0; i < 10; i++) w |= b[i] << i;
            SYM_LUT[w] = (short) v;
        }
    }

    public static String digits(String s) {
        StringBuilder b = new StringBuilder();
        if (s != null) for (char c : s.toCharArray()) if (c >= '0' && c <= '9') b.append(c);
        return b.toString();
    }

    static List<Integer> idToSyms(String id) {
        String s = digits(id);
        if (s.isEmpty()) throw new IllegalArgumentException("ID должен состоять из цифр");
        if (s.length() % 2 != 0) s = "0" + s;
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < s.length(); i += 2) out.add(Integer.parseInt(s.substring(i, i + 2)));
        return out;
    }

    static String symsToId(List<Integer> syms) {
        StringBuilder b = new StringBuilder();
        for (int v : syms) b.append(String.format(java.util.Locale.US, "%02d", v));
        return b.toString();
    }

    /** Гипотеза: каждый символ текста = 7-битный ASCII (раскладка CODAN не опубликована). */
    static List<Integer> msgToSyms(String text) {
        List<Integer> out = new ArrayList<>();
        int n = Math.min(text.length(), MAX_MSG);
        for (int i = 0; i < n; i++) {
            int c = text.charAt(i);
            out.add(c >= 32 && c <= 126 ? c : '?');
        }
        return out;
    }

    static String symsToMsg(List<Integer> syms) {
        StringBuilder b = new StringBuilder();
        for (Integer v : syms) b.append(v != null && v >= 32 && v <= 126 ? (char) (int) v : '�');
        return b.toString();
    }

    static String zfill(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = s.length(); i < n; i++) b.append('0');
        return b.append(s).toString();
    }

    // ------------------------------------------------------------ кодер
    public static int[] buildCall(String toId, String fromId, String message) {
        return buildCall(toId, fromId, message, FMT_SELCALL, CAT_ROUTINE, EOS_ACK_RQ);
    }

    /** Поток символов в порядке передачи (DX, RX, DX, RX ...), кадр как в записи CODAN. */
    public static int[] buildCall(String toId, String fromId, String message, int fmt, int category, int eos) {
        String to = digits(toId), fr = digits(fromId);
        int n = Math.max(to.length(), fr.length());
        List<Integer> rest = new ArrayList<>(idToSyms(zfill(to, n)));
        rest.add(category);
        rest.addAll(idToSyms(zfill(fr, n)));
        if (message != null && !message.isEmpty()) rest.addAll(msgToSyms(message));
        List<Integer> dx = new ArrayList<>();
        for (int i = 0; i < 6; i++) dx.add(PHASE_DX);
        dx.add(fmt);
        dx.addAll(rest);
        for (int i = 0; i < 3; i++) dx.add(eos);
        List<Integer> rx = new ArrayList<>();
        for (int v = 109; v >= 104; v--) rx.add(v);
        for (int i = 0; i < 3; i++) rx.add(fmt);
        rx.addAll(rest);
        rx.add(eos);
        int m = Math.min(dx.size(), rx.size());
        int[] out = new int[2 * m];
        for (int i = 0; i < m; i++) {
            out[2 * i] = dx.get(i);
            out[2 * i + 1] = rx.get(i);
        }
        return out;
    }

    /** FSK с непрерывной фазой; точки длиной dotSeconds; края 5 мс. */
    public static float[] modulate(int[] stream, int fs, double dotSeconds, double center, double amp) {
        int ndots = ((int) (dotSeconds * BAUD)) / 2 * 2;
        int nbits = ndots + stream.length * 10;
        byte[] bits = new byte[nbits];
        for (int i = 0; i < ndots; i++) bits[i] = (byte) ((i % 2 == 0) ? 1 : 0);
        int p = ndots;
        for (int v : stream) for (int b : symBits(v)) bits[p++] = (byte) b;
        int nsamp = (int) Math.round(nbits * fs / BAUD);
        float[] y = new float[nsamp];
        double phase = 0;
        int ramp = (int) (0.005 * fs);
        for (int t = 0; t < nsamp; t++) {
            int bi = Math.min((int) (t * BAUD / fs), nbits - 1);
            double f = bits[bi] == 1 ? center + SHIFT / 2 : center - SHIFT / 2;
            phase += 2 * Math.PI * f / fs;
            if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
            double w = 1;
            if (ramp > 1) {
                if (t < ramp) w = (double) t / (ramp - 1);
                else if (t >= nsamp - ramp) w = (double) (nsamp - 1 - t) / (ramp - 1);
            }
            y[t] = (float) (amp * Math.sin(phase) * w);
        }
        return y;
    }

    // ------------------------------------------------------------ демодулятор
    /** Разность амплитуд верхнего и нижнего тонов (скользящее окно в бит). >0 => «1». */
    static float[] toneDiff(float[] x, int len, double fs, double center) {
        double[] a = mag(x, len, fs, center + SHIFT / 2);
        double[] b = mag(x, len, fs, center - SHIFT / 2);
        float[] d = new float[len];
        for (int i = 0; i < len; i++) d[i] = (float) (a[i] - b[i]);
        return d;
    }

    private static double[] mag(float[] x, int n, double fs, double f) {
        int L = Math.max(1, (int) Math.round(fs / BAUD));
        double[] cr = new double[n + 1], ci = new double[n + 1];
        double w = -2 * Math.PI * f / fs;
        double sr = 0, si = 0;
        double cw = Math.cos(w), sw = Math.sin(w);
        for (int blk = 0; blk < n; blk += 1024) {
            double c = Math.cos(w * blk), s = Math.sin(w * blk);   // точный фазор раз в 1024 отсчёта
            int end = Math.min(n, blk + 1024);
            for (int j = blk; j < end; j++) {
                double xv = x[j];
                sr += xv * c;
                si += xv * s;
                cr[j + 1] = sr;
                ci[j + 1] = si;
                double nc = c * cw - s * sw;                       // поворот фазора
                s = c * sw + s * cw;
                c = nc;
            }
        }
        double[] out = new double[n];
        int half = L / 2;
        for (int k = 0; k < n; k++) {
            int lo = Math.max(0, Math.min(n, k - half));
            int hi = Math.max(0, Math.min(n, k - half + L));
            double re = cr[hi] - cr[lo], im = ci[hi] - ci[lo];
            out[k] = Math.sqrt(re * re + im * im) / L;
        }
        return out;
    }

    /** Декодирование при известном центре; time/endTime — относительно начала x. */
    public static List<Call> decodeAt(float[] x, int len, double fs, double center) {
        float[] d = toneDiff(x, len, fs, center);
        double spb = fs / BAUD;
        List<Call> found = new ArrayList<>();
        for (int k = 0; k < 8; k++) {
            double ph = k * spb / 8;
            int cnt = (int) ((len - ph) / spb);
            if (cnt < 200) continue;
            int[] idx = new int[cnt];
            byte[] raw = new byte[cnt], inv = new byte[cnt];
            for (int i = 0; i < cnt; i++) {
                idx[i] = (int) (ph + i * spb);
                raw[i] = (byte) (d[idx[i]] > 0 ? 1 : 0);
                inv[i] = (byte) (1 - raw[i]);
            }
            for (int pol = 0; pol < 2; pol++) {
                for (Call c : findCallsInBits(pol == 0 ? raw : inv)) {
                    c.time = idx[c.bitpos] / fs - 2.3;
                    int eb = Math.min(cnt - 1, c.bitpos + c.endRel);
                    c.endTime = idx[eb] / fs;
                    c.inverted = pol == 1;
                    c.centerHz = Math.round(center * 10) / 10.0;
                    found.add(c);
                }
            }
        }
        return found;
    }

    static List<Call> findCallsInBits(byte[] bits) {
        int N = bits.length;
        List<Call> calls = new ArrayList<>();
        if (N < 200) return calls;
        int M = N - 9;
        short[] sym = new short[M];
        for (int j = 0; j < M; j++) {
            int w = 0;
            for (int i = 0; i < 10; i++) w |= bits[j + i] << i;
            sym[j] = SYM_LUT[w];
        }
        int[] score = new int[M];
        List<Integer> cand = new ArrayList<>();
        for (int q = 0; q < M; q++) {
            int s = 0;
            for (int i = 0; i < 8; i++) {
                int a = q - 20 * i, b = q - 10 - 20 * i;
                if (a >= 0 && sym[a] == 104 + i) s++;
                if (b >= 0 && sym[b] == PHASE_DX) s++;
            }
            score[q] = s;
            if (s >= 7) cand.add(q);
        }
        // по убыванию счёта, при равенстве — по позиции (stable)
        Collections.sort(cand, (p1, p2) -> score[p2] != score[p1] ? score[p2] - score[p1] : p1 - p2);
        List<Integer> taken = new ArrayList<>();
        outer:
        for (int p : cand) {
            for (int t : taken) if (Math.abs(p - t) <= 200) continue outer;
            taken.add(p);
            Call c = extract(sym, p, 90);
            if (c != null) {
                c.bitpos = p;
                c.phasingScore = score[p];
                calls.add(c);
            }
        }
        return calls;
    }

    private static Integer at(short[] sym, int j) {
        if (j < 0 || j >= sym.length) return null;
        int v = sym[j];
        return v >= 0 ? v : null;
    }

    /** Логическая последовательность L[k]: DX в q+10+20k и его RX-копия в q+60+20k. */
    static Call extract(short[] sym, int q, int maxLen) {
        List<Integer> vals = new ArrayList<>();
        List<Boolean> conf = new ArrayList<>();
        List<Integer> cnt = new ArrayList<>();
        for (int k = -2; k < maxLen; k++) {
            Integer d = at(sym, q + 10 + 20 * k), r = at(sym, q + 60 + 20 * k);
            if (d != null && d == PHASE_DX) d = null;
            if (isRxPhasing(r)) r = null;
            if (d == null && r == null) {
                vals.add(null); conf.add(false); cnt.add(0);
            } else if (d == null || r == null) {
                vals.add(d == null ? r : d); conf.add(false); cnt.add(1);
            } else {
                vals.add(d); conf.add(!d.equals(r)); cnt.add(d.equals(r) ? 2 : 1);
            }
            int n = vals.size();
            if (d == null && r == null && k > 6 && n >= 3
                    && vals.get(n - 1) == null && vals.get(n - 2) == null && vals.get(n - 3) == null) break;
        }
        return parse(vals, conf, cnt);
    }

    private static Integer get(List<Integer> v, int i) {
        return i < v.size() ? v.get(i) : null;
    }

    static Call parse(List<Integer> vals, List<Boolean> conf, List<Integer> cnt) {
        int n = vals.size();
        int i = 0;
        while (i < n && i < 3 && vals.get(i) == null) i++;
        if (i >= n || vals.get(i) == null || vals.get(i) < 100 || isEos(vals.get(i))) return null;
        int fmt = vals.get(i++);
        while (i < n && vals.get(i) != null && vals.get(i) == fmt) i++;
        List<Integer> addr = new ArrayList<>();
        while (i < n && vals.get(i) != null && vals.get(i) < 100 && addr.size() < 5) addr.add(vals.get(i++));
        if (addr.isEmpty() || i >= n || vals.get(i) == null || vals.get(i) < 100) return null;
        int category = vals.get(i++);
        if (i + addr.size() > n) return null;
        List<Integer> selfid = new ArrayList<>(vals.subList(i, i + addr.size()));
        for (Integer v : selfid) if (v == null || v >= 100) return null;
        i += addr.size();
        List<Integer> head = new ArrayList<>();
        head.add(fmt);
        head.addAll(addr);
        head.add(category);
        head.addAll(selfid);

        int bestJ = -1;
        String bestStatus = null;
        boolean bestComplete = false;
        for (int j = i; j < Math.min(n, i + MAX_MSG + 1); j++) {
            if (!isEos(vals.get(j))) continue;
            List<Integer> msg = vals.subList(i, j);
            Integer nxt = get(vals, j + 1);
            String status;
            if (msg.contains(null)) {
                status = "BAD";
            } else {
                int ecc = 0;
                for (int v : head) ecc ^= v;
                for (int v : msg) ecc ^= v;
                ecc ^= vals.get(j);
                if (nxt != null && nxt == ecc) status = "ok";
                else if (nxt == null || nxt.equals(vals.get(j))) status = "нет";
                else status = "BAD";
            }
            if (!status.equals("BAD")) {
                bestJ = j; bestStatus = status; bestComplete = nxt != null;
                break;
            }
            if (bestJ < 0) {
                bestJ = j; bestStatus = status; bestComplete = nxt != null;
            }
        }
        if (bestJ < 0) return null;
        int j = bestJ;
        List<Integer> msg = new ArrayList<>(vals.subList(i, j));
        int conflicts = 0, single = 0;
        for (int k = 0; k <= j; k++) {
            if (conf.get(k)) conflicts++;
            if (vals.get(k) != null && cnt.get(k) == 1) single++;
        }
        boolean valid = bestStatus.equals("ok")
                || (bestStatus.equals("нет") && conflicts == 0 && !head.contains(null) && !msg.contains(null));
        Call c = new Call();
        c.type = msg.isEmpty() ? "selcall" : "message";
        c.format = fmt;
        c.to = symsToId(addr);
        c.from = symsToId(selfid);
        c.category = category;
        c.message = msg.isEmpty() ? "" : symsToMsg(msg);
        c.eos = eosName(vals.get(j));
        c.ecc = bestStatus;
        c.eccOk = valid;
        c.conflicts = conflicts;
        c.singleCopy = single;
        c.complete = bestComplete;
        c.endRel = 70 + 20 * (j - 2);
        return c;
    }

    /** Объединение дублей от разных центров / фаз / полярностей. */
    public static List<Call> merge(List<Call> found) {
        List<Call> s = new ArrayList<>(found);
        Collections.sort(s, (a, b) -> {
            if (a.eccOk != b.eccOk) return a.eccOk ? -1 : 1;
            if (a.conflicts != b.conflicts) return a.conflicts - b.conflicts;
            return b.phasingScore - a.phasingScore;
        });
        List<Call> out = new ArrayList<>();
        outer:
        for (Call c : s) {
            for (Call o : out) if (Math.abs(c.time - o.time) <= 0.3) continue outer;
            out.add(c);
        }
        Collections.sort(out, (a, b) -> Double.compare(a.time, b.time));
        return out;
    }

    /** Пакетное декодирование (файл): АПЧ по всему сигналу + центр по умолчанию. */
    public static List<Call> decode(float[] x, int fs, Double center) {
        List<Double> centers = new ArrayList<>();
        if (center != null) {
            centers.add(center);
        } else {
            Afc afc = new Afc(fs, 0);
            afc.append(x, x.length);
            centers.addAll(afc.topCenters(3));
            boolean has = false;
            for (double c : centers) if (Math.abs(c - F_CENTER) < 20) has = true;
            if (!has) centers.add(F_CENTER);
        }
        List<Call> found = new ArrayList<>();
        for (double c : centers) found.addAll(decodeAt(x, x.length, fs, c));
        return merge(found);
    }

    // ------------------------------------------------------------ текст
    private static final String RU = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя№";
    private static final String[] LAT = {"A", "B", "V", "G", "D", "E", "E", "ZH", "Z", "I", "J", "K", "L", "M", "N", "O", "P",
            "R", "S", "T", "U", "F", "H", "C", "CH", "SH", "SCH", "", "Y", "", "E", "YU", "YA",
            "a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "j", "k", "l", "m", "n", "o", "p",
            "r", "s", "t", "u", "f", "h", "c", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya", "N"};

    public static String translit(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            int k = RU.indexOf(c);
            b.append(k >= 0 ? LAT[k] : String.valueOf(c));
        }
        return b.toString();
    }
}
