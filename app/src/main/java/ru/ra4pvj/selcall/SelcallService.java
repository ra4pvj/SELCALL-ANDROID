package ru.ra4pvj.selcall;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

import ru.ra4pvj.selcall.core.Call;
import ru.ra4pvj.selcall.core.Downsampler;
import ru.ra4pvj.selcall.core.Selcall;
import ru.ra4pvj.selcall.core.StreamReceiver;
import ru.ra4pvj.selcall.core.Tones;

/** Приём (AudioRecord), передача (AudioTrack), PTT, звонок. Работает в фоне как foreground-сервис. */
public class SelcallService extends Service {
    static final int RX_FS = 8000;
    static final String CH_RX = "rx";
    static final String CH_CALL = "call";
    static final int NOTIF_RX = 1, NOTIF_CALL = 2;
    static final String ACTION_STOP = "ru.ra4pvj.selcall.STOP";
    static final String ACTION_DISMISS = "ru.ra4pvj.selcall.DISMISS";

    /** Строка журнала. */
    static final class Entry {
        final long when;
        final String text, detail, tag, sender;

        Entry(long when, String text, String detail, String tag, String sender) {
            this.when = when;
            this.text = text;
            this.detail = detail;
            this.tag = tag;
            this.sender = sender;
        }
    }

    interface Listener {
        void onEntry(Entry e);

        void onState();

        void onIncoming(String text, String from);
    }

    final class LocalBinder extends Binder {
        SelcallService service() {
            return SelcallService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private Listener listener;
    final List<Entry> log = new ArrayList<>();

    // приём
    volatile boolean receiving = false;
    volatile float level = 0;
    volatile List<Double> centers = new ArrayList<>();
    volatile int inputRate = 0;
    private Thread recThread, decThread;
    private AudioRecord rec;
    private final ConcurrentLinkedQueue<float[]> audioQ = new ConcurrentLinkedQueue<>();
    private StreamReceiver rx;
    private long rxStartMs;
    private volatile long muteUntil = 0;
    private PowerManager.WakeLock wake;

    // передача
    volatile boolean transmitting = false;
    private final LinkedBlockingQueue<Tx> txQ = new LinkedBlockingQueue<>();
    private Thread txThread;
    private volatile AudioTrack txTrack;
    private volatile boolean txAbort = false;
    private UsbPtt ptt;
    private final List<long[]> txTimes = new ArrayList<>();      // [начало, конец] мс
    private final List<String[]> txWhat = new ArrayList<>();     // [кому, текст]

    // звонок
    private AudioTrack ringTrack;
    private final Runnable stopRingR = this::stopRing;
    String incomingFrom = null;
    String incomingText = null;

    private static final class Tx {
        float[] audio;
        int fs;
        String me, to, text, title;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        ptt = new UsbPtt(this);
        createChannels();
        loadJournal();
        txThread = new Thread(this::txLoop, "tx");
        txThread.setDaemon(true);
        txThread.start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopRx();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_DISMISS.equals(intent.getAction())) {
            dismissIncoming();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopRx();
        stopTx();
        stopRing();
        ptt.close();
        txThread.interrupt();
        super.onDestroy();
    }

    void setListener(Listener l) {
        listener = l;
    }

    // ------------------------------------------------------------------ журнал
    private File journalFile() {
        return new File(getFilesDir(), "journal.log");
    }

    private void loadJournal() {
        try (BufferedReader r = new BufferedReader(new FileReader(journalFile()))) {
            List<String> lines = new ArrayList<>();
            String s;
            while ((s = r.readLine()) != null) {
                lines.add(s);
                if (lines.size() > 400) lines.remove(0);
            }
            for (String l : lines) {
                String[] p = l.split("\t", -1);
                if (p.length >= 5) log.add(new Entry(Long.parseLong(p[0]), p[2], p[3], p[1], p[4].isEmpty() ? null : p[4]));
            }
        } catch (Exception ignored) {
        }
    }

    void clearJournal() {
        log.clear();
        //noinspection ResultOfMethodCallIgnored
        journalFile().delete();
    }

    /** Добавить строку журнала (из любого потока). */
    void add(String text, String tag, String detail, String sender) {
        Entry e = new Entry(System.currentTimeMillis(), text, detail == null ? "" : detail, tag, sender);
        main.post(() -> {
            log.add(e);
            if (log.size() > 1000) log.remove(0);
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(journalFile(), true), "UTF-8")) {
                w.write(e.when + "\t" + e.tag + "\t" + clean(e.text) + "\t" + clean(e.detail) + "\t"
                        + (e.sender == null ? "" : e.sender) + "\n");
            } catch (Exception ignored) {
            }
            if (listener != null) listener.onEntry(e);
        });
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ');
    }

    void info(String s) {
        add(s, "info", null, null);
    }

    void error(String s) {
        add(s, "error", null, null);
    }

    private void state() {
        main.post(() -> {
            if (listener != null) listener.onState();
        });
    }

    // ------------------------------------------------------------------ уведомления
    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel a = new NotificationChannel(CH_RX, "Приём", NotificationManager.IMPORTANCE_LOW);
        a.setShowBadge(false);
        nm.createNotificationChannel(a);
        NotificationChannel b = new NotificationChannel(CH_CALL, "Входящий вызов", NotificationManager.IMPORTANCE_HIGH);
        b.setSound(null, null);          // звонок играет само приложение
        b.enableVibration(false);
        b.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(b);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder builder(String ch) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
    }

    private Notification rxNotification() {
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, SelcallService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return builder(CH_RX)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("HF Selcall — приём")
                .setContentText("Мой ID " + Selcall.digits(prefs.myId()))
                .setOngoing(true)
                .setContentIntent(openApp())
                .addAction(new Notification.Action.Builder(null, "Стоп", stop).build())
                .build();
    }

    @SuppressWarnings("deprecation")
    private void notifyIncoming(String title, String text) {
        PendingIntent dismiss = PendingIntent.getService(this, 2, new Intent(this, SelcallService.class).setAction(ACTION_DISMISS),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = builder(CH_CALL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_CALL)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .setFullScreenIntent(openApp(), true)
                .addAction(new Notification.Action.Builder(null, "Сбросить", dismiss).build());
        try {
            getSystemService(NotificationManager.class).notify(NOTIF_CALL, b.build());
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ приём
    private AudioDeviceInfo findDevice(int id, int flag) {
        if (id == 0) return null;
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        for (AudioDeviceInfo d : am.getDevices(flag)) if (d.getId() == id) return d;
        return null;
    }

    private int sourceId() {
        switch (prefs.audioSource()) {
            case "unprocessed":
                return MediaRecorder.AudioSource.UNPROCESSED;
            case "mic":
                return MediaRecorder.AudioSource.MIC;
            case "camcorder":
                return MediaRecorder.AudioSource.CAMCORDER;
            default:
                return MediaRecorder.AudioSource.VOICE_RECOGNITION;
        }
    }

    @SuppressLint("MissingPermission")
    String startRx() {
        if (receiving) return null;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return "Нет разрешения на микрофон (звуковой вход)";
        AudioRecord r = null;
        int fs = 0;
        for (int rate : new int[]{RX_FS, 48000, 44100, 16000}) {
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) continue;
            try {
                r = new AudioRecord(sourceId(), rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(min * 4, rate));
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    fs = rate;
                    break;
                }
                r.release();
                r = null;
            } catch (Exception e) {
                r = null;
            }
        }
        if (r == null) return "Не удалось открыть звуковой вход";
        AudioDeviceInfo dev = findDevice(prefs.inDevice(), AudioManager.GET_DEVICES_INPUTS);
        if (dev != null) r.setPreferredDevice(dev);
        try {
            r.startRecording();
        } catch (Exception e) {
            r.release();
            return "Не удалось запустить запись: " + e.getMessage();
        }
        // foreground-сервис, чтобы приём шёл при выключенном экране
        Intent self = new Intent(this, SelcallService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(self); else startService(self);
        try {
            if (Build.VERSION.SDK_INT >= 30)
                startForeground(NOTIF_RX, rxNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTIF_RX, rxNotification());
        } catch (Exception e) {
            error("Фоновый приём недоступен: " + e.getMessage());
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "selcall:rx");
        wake.acquire();

        rec = r;
        inputRate = fs;
        rx = new StreamReceiver(RX_FS, 30);
        audioQ.clear();
        rxStartMs = System.currentTimeMillis() - 0;
        receiving = true;
        final AudioRecord ar = r;
        final int rate = fs;
        recThread = new Thread(() -> recordLoop(ar, rate), "rec");
        recThread.start();
        decThread = new Thread(this::decodeLoop, "dec");
        decThread.start();
        state();
        return null;
    }

    void stopRx() {
        if (!receiving) return;
        receiving = false;
        try {
            if (recThread != null) recThread.join(1500);
            if (decThread != null) decThread.interrupt();
        } catch (InterruptedException ignored) {
        }
        try {
            rec.stop();
        } catch (Exception ignored) {
        }
        rec.release();
        rec = null;
        level = 0;
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(true);
        stopSelf();
        state();
    }

    private void recordLoop(AudioRecord r, int fs) {
        int chunk = fs / 10;
        short[] s = new short[chunk];
        float[] f = new float[chunk];
        float[] out = new float[chunk + 16];
        Downsampler ds = new Downsampler(fs, RX_FS);
        rxStartMs = System.currentTimeMillis();
        while (receiving) {
            int n = r.read(s, 0, chunk);
            if (n <= 0) {
                SystemClock.sleep(20);
                continue;
            }
            float peak = 0;
            for (int i = 0; i < n; i++) {
                f[i] = s[i] / 32768f;
                peak = Math.max(peak, Math.abs(f[i]));
            }
            level = Math.max(peak, level * 0.8f);
            int m = ds.push(f, n, out);
            float[] y = java.util.Arrays.copyOf(out, m);
            if (System.currentTimeMillis() < muteUntil) java.util.Arrays.fill(y, 0);
            audioQ.add(y);
        }
    }

    private void decodeLoop() {
        while (receiving) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
            if (!receiving) break;
            try {
                float[] x;
                while ((x = audioQ.poll()) != null) rx.append(x, x.length);
                rx.fixedCenter = prefs.afc() ? null : prefs.fixedCenter();
                List<Call> calls = rx.process(false);
                centers = rx.lastCenters;
                state();
                for (Call c : calls) handleRx(c, rxStartMs + (long) (c.time * 1000), false);
            } catch (Exception ex) {
                error("Ошибка декодера: " + ex);
            }
        }
    }

    // ------------------------------------------------------------------ обработка вызова
    void handleRx(Call c, long whenMs, boolean fromFile) {
        String me = Selcall.digits(prefs.myId());
        boolean mine = c.to.equals(zfill(me, c.to.length()));
        boolean fromMe = c.from.equals(zfill(me, c.from.length()));
        boolean own = false;
        if (!fromFile && fromMe) {
            synchronized (txTimes) {
                for (int i = 0; i < txTimes.size(); i++) {
                    long[] t = txTimes.get(i);
                    String[] w = txWhat.get(i);
                    if (zfill(w[0], c.to.length()).equals(c.to) && w[1].equals(c.message)
                            && whenMs >= t[0] - 3000 && whenMs <= t[1] + 3000) own = true;
                }
            }
        }
        if (!prefs.showAll() && !mine && !fromFile) return;
        String title = c.isAck() ? "Подтверждение" : (c.message.isEmpty() ? "Вызов" : "Сообщение");
        if (own) title = "Своя передача (эхо)";
        else title += (mine ? " мне" : "") + (fromFile ? " (файл)" : "");
        String line = "▼ " + title + "  " + c.from + " → " + c.to;
        if (!c.message.isEmpty()) line += "  «" + c.message + "»";
        String detail = String.format(Locale.US, "fmt %d · cat %d · %s · ECC %s · %s · %.1f Гц%s%s",
                c.format, c.category, c.eos, c.ecc, c.eccOk ? "принят" : "СОМНИТЕЛЬНО", c.centerHz,
                c.inverted ? " · LSB" : "", c.conflicts > 0 ? " · исправлено " + c.conflicts : "");
        if (fromFile) detail += String.format(Locale.US, " · %.1f с", c.time);
        String tag = c.eccOk ? (mine ? "mine" : "rx") : "rx_bad";
        add(line, tag, detail, c.from);
        if (mine && !own && !fromFile && c.eccOk && !c.isAck()) incoming(title, c);
    }

    private static String zfill(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = s.length(); i < n; i++) b.append('0');
        return b.append(s).toString();
    }

    private void incoming(String title, Call c) {
        String text = "☎  " + title + " от " + c.from + (c.message.isEmpty() ? "" : ":  " + c.message);
        main.post(() -> {
            incomingFrom = c.from;
            incomingText = text;
            if (listener != null) listener.onIncoming(text, c.from);
            notifyIncoming(title + " от " + c.from, c.message.isEmpty() ? "HF Selcall" : c.message);
            if (prefs.ring()) ring(false);
            if (prefs.vibrate()) vibrate();
            if (prefs.ringAir()) {
                // пауза: вызывающая станция успевает перейти на приём
                main.postDelayed(() -> {
                    String err = sendAnswer(c.from);
                    if (err != null) error(err);
                }, 1000);
            }
        });
    }

    void dismissIncoming() {
        stopRing();
        incomingFrom = null;
        incomingText = null;
        try {
            getSystemService(NotificationManager.class).cancel(NOTIF_CALL);
        } catch (Exception ignored) {
        }
        main.post(() -> {
            if (listener != null) listener.onIncoming(null, null);
        });
    }

    @SuppressWarnings("deprecation")
    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            long[] pat = {0, 400, 300, 400, 300, 400};
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pat, -1));
            else v.vibrate(pat, -1);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ звонок
    /** Звонок по кругу (answer=false) или один раз сигнал ответа (прослушивание, не в эфир). */
    void ring(boolean answer) {
        stopRing();
        int fs = 48000;
        float[] pat = answer ? Tones.answer(prefs.answerStyle(), fs, prefs.ringVolume())
                : Tones.ring(prefs.ringStyle(), fs, prefs.ringVolume());
        try {
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(fs)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pat.length * 4)
                    .build();
            t.write(pat, 0, pat.length, AudioTrack.WRITE_BLOCKING);
            if (!answer) t.setLoopPoints(0, pat.length, -1);
            t.play();
            ringTrack = t;
            main.removeCallbacks(stopRingR);
            main.postDelayed(stopRingR, answer ? (long) (pat.length * 1000.0 / fs) + 300 : (long) (prefs.ringSeconds() * 1000));
        } catch (Exception e) {
            error("Звонок: " + e.getMessage());
        }
    }

    void stopRing() {
        main.removeCallbacks(stopRingR);
        AudioTrack t = ringTrack;
        ringTrack = null;
        if (t != null) {
            try {
                t.stop();
            } catch (Exception ignored) {
            }
            t.release();
        }
    }

    // ------------------------------------------------------------------ передача
    private int outRate() {
        return 48000;
    }

    String send(String toId, String message, String title) {
        String to = Selcall.digits(toId), me = Selcall.digits(prefs.myId());
        if (to.isEmpty() || me.isEmpty() || to.length() > 10 || me.length() > 10)
            return "Укажите цифровые ID: свой и получателя (4 или 6 цифр)";
        String text = null;
        if (message != null && !message.isEmpty()) {
            text = prefs.translit() ? Selcall.translit(message) : message;
            if (text.length() > Selcall.MAX_MSG) text = text.substring(0, Selcall.MAX_MSG);
        }
        int fs = outRate();
        float[] y = Selcall.modulate(Selcall.buildCall(to, me, text), fs, prefs.dotSeconds(), Selcall.F_CENTER, prefs.txLevel());
        Tx t = new Tx();
        t.audio = pad(y, fs);
        t.fs = fs;
        t.me = me;
        t.to = to;
        t.text = text == null ? "" : text;
        t.title = title;
        txQ.add(t);
        return null;
    }

    /** Ответ в эфир: сигнал ответа один раз (мелодия ~2,9 с или тоны 2,5 с). */
    String sendAnswer(String toId) {
        int fs = outRate();
        Tx t = new Tx();
        t.audio = pad(Tones.answer(prefs.answerStyle(), fs, prefs.txLevel()), fs);
        t.fs = fs;
        t.me = Selcall.digits(prefs.myId());
        t.to = Selcall.digits(toId);
        t.text = "";
        t.title = "Ответ в эфир";
        txQ.add(t);
        return null;
    }

    private float[] pad(float[] y, int fs) {
        int lead = (int) (prefs.pttDelay() * fs), tail = (int) (prefs.pttTail() * fs);
        float[] a = new float[lead + y.length + tail];
        System.arraycopy(y, 0, a, lead, y.length);
        return a;
    }

    /** Сигнал вызова (без задержек PTT) — для сохранения в WAV. */
    float[] build(String toId, String message, int fs) {
        String text = null;
        if (message != null && !message.isEmpty()) {
            text = prefs.translit() ? Selcall.translit(message) : message;
            if (text.length() > Selcall.MAX_MSG) text = text.substring(0, Selcall.MAX_MSG);
        }
        return Selcall.modulate(Selcall.buildCall(Selcall.digits(toId), Selcall.digits(prefs.myId()), text),
                fs, prefs.dotSeconds(), Selcall.F_CENTER, prefs.txLevel());
    }

    void stopTx() {
        txQ.clear();
        txAbort = true;
        AudioTrack t = txTrack;
        if (t != null) {
            try {
                t.pause();
                t.flush();
            } catch (Exception ignored) {
            }
        }
    }

    void testPtt() {
        if (transmitting) return;
        new Thread(() -> {
            ptt.configure(prefs);
            if (!ptt.active()) {
                info("PTT не управляется (VOX / внешний PTT): проверять нечего");
                return;
            }
            String err = ptt.key(true);
            if (err != null) {
                error(err);
                return;
            }
            info("Тест PTT: " + ptt.method + " — 1 с (лучше на эквивалент нагрузки)");
            SystemClock.sleep(1000);
            if (!transmitting) ptt.unkeySafe();
        }).start();
    }

    private void txLoop() {
        while (true) {
            Tx t;
            try {
                t = txQ.take();
            } catch (InterruptedException e) {
                return;
            }
            txAbort = false;
            transmitting = true;
            state();
            long dur = (long) (t.audio.length * 1000.0 / t.fs);
            if (prefs.muteRxOnTx()) muteUntil = System.currentTimeMillis() + dur + 1000;
            ptt.configure(prefs);
            String err = ptt.key(true);
            if (err != null) {
                error(err + " — передача отменена");
                txQ.clear();
                transmitting = false;
                muteUntil = 0;
                state();
                continue;
            }
            String line = "▲ " + t.title + "  " + t.me + " → " + t.to + (t.text.isEmpty() ? "" : "  «" + t.text + "»");
            add(line, "tx", String.format(Locale.US, "%.1f с", dur / 1000.0), null);
            long now = System.currentTimeMillis();
            synchronized (txTimes) {
                for (int i = txTimes.size() - 1; i >= 0; i--) {
                    if (now - txTimes.get(i)[0] > 120000) {
                        txTimes.remove(i);
                        txWhat.remove(i);
                    }
                }
                txTimes.add(new long[]{now, now + dur});
                txWhat.add(new String[]{t.to, t.text});
            }
            try {
                play(t);
            } catch (Exception e) {
                error("Ошибка передачи: " + e.getMessage());
            }
            ptt.unkeySafe();
            transmitting = false;
            if (prefs.muteRxOnTx()) muteUntil = System.currentTimeMillis() + 500;
            if (txAbort) {
                muteUntil = 0;
                info("Передача остановлена");
            }
            state();
        }
    }

    private void play(Tx t) {
        int min = AudioTrack.getMinBufferSize(t.fs, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        AudioTrack tr = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(t.fs)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(min, t.fs / 5 * 4))
                .build();
        AudioDeviceInfo dev = findDevice(prefs.outDevice(), AudioManager.GET_DEVICES_OUTPUTS);
        if (dev != null) tr.setPreferredDevice(dev);
        txTrack = tr;
        try {
            tr.play();
            int pos = 0, step = t.fs / 20;
            while (pos < t.audio.length && !txAbort) {
                int n = Math.min(step, t.audio.length - pos);
                int w = tr.write(t.audio, pos, n, AudioTrack.WRITE_BLOCKING);
                if (w < 0) throw new IllegalStateException("AudioTrack.write " + w);
                pos += w;
            }
            // дождаться, пока буфер доиграет
            long deadline = SystemClock.uptimeMillis() + 3000;
            while (!txAbort && tr.getPlaybackHeadPosition() < pos && SystemClock.uptimeMillis() < deadline)
                SystemClock.sleep(10);
            tr.stop();
        } finally {
            txTrack = null;
            tr.release();
        }
    }

    String status() {
        StringBuilder b = new StringBuilder("Мой ID ").append(Selcall.digits(prefs.myId()));
        if (receiving) {
            b.append(" · ");
            if (!prefs.afc()) b.append(String.format(Locale.US, "центр %.0f Гц", prefs.fixedCenter()));
            else {
                b.append("АПЧ");
                List<Double> c = centers;
                if (!c.isEmpty()) b.append(String.format(Locale.US, " %.0f Гц", c.get(0)));
            }
            if (inputRate != RX_FS) b.append(" · вход ").append(inputRate).append(" Гц");
        }
        return b.toString();
    }

    static String time(long ms) {
        return new SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(new Date(ms));
    }
}
