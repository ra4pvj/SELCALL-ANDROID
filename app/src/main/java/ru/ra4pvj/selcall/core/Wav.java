package ru.ra4pvj.selcall.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Чтение / запись WAV (PCM 8/16/24/32 бит, float32; берётся первый канал). */
public final class Wav {
    public final float[] samples;
    public final int fs;

    public Wav(float[] samples, int fs) {
        this.samples = samples;
        this.fs = fs;
    }

    public static Wav read(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] tmp = new byte[65536];
        int r;
        while ((r = in.read(tmp)) > 0) bo.write(tmp, 0, r);
        ByteBuffer b = ByteBuffer.wrap(bo.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        if (b.remaining() < 12 || b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157)
            throw new IOException("не WAV-файл");
        int pos = 12, fmt = 0, ch = 0, fs = 0, bits = 0, dataPos = -1, dataLen = 0;
        while (pos + 8 <= b.limit()) {
            int id = b.getInt(pos), sz = b.getInt(pos + 4);
            if (id == 0x20746d66) {                     // "fmt "
                fmt = b.getShort(pos + 8) & 0xffff;
                ch = b.getShort(pos + 10) & 0xffff;
                fs = b.getInt(pos + 12);
                bits = b.getShort(pos + 22) & 0xffff;
                if (fmt == 0xFFFE && sz >= 26) fmt = b.getShort(pos + 32) & 0xffff;   // EXTENSIBLE
            } else if (id == 0x61746164) {              // "data"
                dataPos = pos + 8;
                dataLen = Math.min(sz < 0 ? Integer.MAX_VALUE : sz, b.limit() - dataPos);
                break;
            }
            pos += 8 + sz + (sz & 1);
        }
        if (dataPos < 0 || ch == 0 || fs == 0) throw new IOException("WAV: нет fmt/data");
        int bps = bits / 8, frame = bps * ch;
        int n = dataLen / frame;
        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            int p = dataPos + i * frame;
            float v;
            if (fmt == 3 && bits == 32) v = b.getFloat(p);
            else if (fmt == 3 && bits == 64) v = (float) b.getDouble(p);
            else if (bits == 8) v = ((b.get(p) & 0xff) - 128) / 128f;
            else if (bits == 16) v = b.getShort(p) / 32768f;
            else if (bits == 24) {
                int s = (b.get(p) & 0xff) | ((b.get(p + 1) & 0xff) << 8) | (b.get(p + 2) << 16);
                v = s / 8388608f;
            } else if (bits == 32) v = b.getInt(p) / 2147483648f;
            else throw new IOException("WAV: " + bits + " бит не поддерживается");
            x[i] = v;
        }
        return new Wav(x, fs);
    }

    public static void write(OutputStream out, float[] y, int fs) throws IOException {
        int n = y.length;
        ByteBuffer b = ByteBuffer.allocate(44 + 2 * n).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46464952).putInt(36 + 2 * n).putInt(0x45564157);
        b.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(fs).putInt(fs * 2).putShort((short) 2).putShort((short) 16);
        b.putInt(0x61746164).putInt(2 * n);
        for (float v : y) {
            float c = Math.max(-1f, Math.min(1f, v));
            b.putShort((short) Math.round(c * 32767));
        }
        out.write(b.array());
        out.flush();
    }
}
