package ru.ra4pvj.selcall;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputFilter;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import ru.ra4pvj.selcall.core.Call;
import ru.ra4pvj.selcall.core.SelfTest;
import ru.ra4pvj.selcall.core.Selcall;
import ru.ra4pvj.selcall.core.Wav;

public class MainActivity extends Activity implements SelcallService.Listener {
    private static final int REQ_PERM = 1, REQ_OPEN = 2, REQ_SAVE = 3;

    private SelcallService svc;
    private Prefs prefs;
    private final Handler h = new Handler(Looper.getMainLooper());

    private TextView status, lampRx, lampTx, bannerText;
    private ProgressBar meter;
    private View banner;
    private EditText toId, msg;
    private Button btnRx, btnStopTx;
    private ListView list;
    private LogAdapter adapter;
    private boolean pendingRx = false;
    private String pendingSaveTo, pendingSaveMsg;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((SelcallService.LocalBinder) b).service();
            svc.setListener(MainActivity.this);
            adapter.notifyDataSetChanged();
            list.setSelection(adapter.getCount() - 1);
            onState();
            if (svc.incomingText != null) onIncoming(svc.incomingText, svc.incomingFrom);
            if (pendingRx) {
                pendingRx = false;
                toggleRx();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName n) {
            svc = null;
        }
    };

    private final Runnable meterTick = new Runnable() {
        @Override
        public void run() {
            if (svc != null) meter.setProgress((int) Math.min(100, svc.level * 100));
            h.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        status = findViewById(R.id.status);
        lampRx = findViewById(R.id.lamp_rx);
        lampTx = findViewById(R.id.lamp_tx);
        meter = findViewById(R.id.meter);
        banner = findViewById(R.id.banner);
        bannerText = findViewById(R.id.banner_text);
        toId = findViewById(R.id.to_id);
        msg = findViewById(R.id.msg);
        btnRx = findViewById(R.id.btn_rx);
        btnStopTx = findViewById(R.id.btn_stop_tx);
        list = findViewById(R.id.log);

        msg.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Selcall.MAX_MSG)});
        toId.setText(prefs.toId());
        adapter = new LogAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            SelcallService.Entry e = (SelcallService.Entry) adapter.getItem(pos);
            if (e.sender != null) {
                toId.setText(e.sender);
                prefs.put("to_id", e.sender);
                Toast.makeText(this, "Кому: " + e.sender, Toast.LENGTH_SHORT).show();
            }
        });

        btnRx.setOnClickListener(v -> toggleRx());
        findViewById(R.id.btn_call).setOnClickListener(v -> send(false));
        findViewById(R.id.btn_send).setOnClickListener(v -> send(true));
        btnStopTx.setOnClickListener(v -> {
            if (svc != null) svc.stopTx();
        });
        findViewById(R.id.btn_answer).setOnClickListener(v -> {
            if (svc != null && svc.incomingFrom != null) {
                toId.setText(svc.incomingFrom);
                prefs.put("to_id", svc.incomingFrom);
            }
            if (svc != null) svc.dismissIncoming();
        });
        findViewById(R.id.btn_dismiss).setOnClickListener(v -> {
            if (svc != null) svc.dismissIncoming();
        });
        msg.setOnEditorActionListener((v, a, e) -> {
            if (a == EditorInfo.IME_ACTION_SEND) {
                send(true);
                return true;
            }
            return false;
        });
        askPermissions();
    }

    private void askPermissions() {
        java.util.ArrayList<String> need = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_PERM && pendingRx && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pendingRx = false;
            toggleRx();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, SelcallService.class), conn, Context.BIND_AUTO_CREATE);
        h.post(meterTick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (svc != null) onState();
    }

    @Override
    protected void onStop() {
        h.removeCallbacks(meterTick);
        if (svc != null) svc.setListener(null);
        unbindService(conn);
        svc = null;
        prefs.put("to_id", toId.getText().toString());
        super.onStop();
    }

    // ------------------------------------------------------------------ действия
    private void toggleRx() {
        if (svc == null) {
            pendingRx = true;
            return;
        }
        if (svc.receiving) {
            svc.stopRx();
            svc.info("Приём остановлен");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingRx = true;
            askPermissions();
            return;
        }
        String err = svc.startRx();
        if (err != null) svc.error("Не удалось запустить приём: " + err);
        else svc.info("Приём запущен (вход " + svc.inputRate + " Гц)");
    }

    private void send(boolean withText) {
        if (svc == null) return;
        String to = toId.getText().toString();
        prefs.put("to_id", to);
        String text = withText ? msg.getText().toString().trim() : null;
        if (withText && text.isEmpty()) {
            Toast.makeText(this, "Введите текст сообщения", Toast.LENGTH_SHORT).show();
            return;
        }
        String err = svc.send(to, text, withText ? "Сообщение" : "Вызов");
        if (err != null) svc.error(err);
        else if (withText) msg.setText("");
    }

    // ------------------------------------------------------------------ события сервиса
    @Override
    public void onEntry(SelcallService.Entry e) {
        boolean atEnd = list.getLastVisiblePosition() >= adapter.getCount() - 2;
        adapter.notifyDataSetChanged();
        if (atEnd) list.setSelection(adapter.getCount() - 1);
    }

    @Override
    public void onState() {
        if (svc == null) return;
        status.setText(svc.status());
        btnRx.setText(svc.receiving ? "Стоп приём" : "Приём");
        boolean rxOn = svc.receiving && !(svc.transmitting && prefs.muteRxOnTx());
        lampRx.setBackgroundColor(rxOn ? 0xFF2E7D32 : 0xFF555555);
        lampTx.setBackgroundColor(svc.transmitting ? 0xFFC62828 : 0xFF555555);
        btnStopTx.setEnabled(svc.transmitting);
    }

    @Override
    public void onIncoming(String text, String from) {
        if (text == null) {
            banner.setVisibility(View.GONE);
        } else {
            bannerText.setText(text);
            banner.setVisibility(View.VISIBLE);
        }
    }

    // ------------------------------------------------------------------ меню
    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 1, 0, "Настройки");
        m.add(0, 2, 0, "Декодировать WAV…");
        m.add(0, 3, 0, "Сохранить вызов в WAV…");
        m.add(0, 4, 0, "Тест PTT");
        m.add(0, 5, 0, "Тест звонка");
        m.add(0, 6, 0, "Тест ответа (мелодия)");
        m.add(0, 7, 0, "Самопроверка кодека");
        m.add(0, 8, 0, "Очистить журнал");
        m.add(0, 9, 0, "О программе");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem it) {
        switch (it.getItemId()) {
            case 1:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            case 2: {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*");
                startActivityForResult(i, REQ_OPEN);
                return true;
            }
            case 3: {
                pendingSaveTo = toId.getText().toString();
                pendingSaveMsg = msg.getText().toString().trim();
                String name = "selcall_" + Selcall.digits(prefs.myId()) + "_to_" + Selcall.digits(pendingSaveTo) + ".wav";
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("audio/wav").putExtra(Intent.EXTRA_TITLE, name);
                startActivityForResult(i, REQ_SAVE);
                return true;
            }
            case 4:
                if (svc != null) svc.testPtt();
                return true;
            case 5:
                if (svc != null) svc.ring(false);
                return true;
            case 6:
                if (svc != null) svc.ring(true);
                return true;
            case 7:
                selfTest();
                return true;
            case 8:
                new AlertDialog.Builder(this).setMessage("Очистить журнал?")
                        .setPositiveButton("Да", (d, w) -> {
                            if (svc != null) svc.clearJournal();
                            adapter.notifyDataSetChanged();
                        })
                        .setNegativeButton("Нет", null).show();
                return true;
            case 9:
                new AlertDialog.Builder(this).setTitle("HF Selcall " + BuildConfig.VERSION_NAME)
                        .setMessage("КВ-селкол CCIR 493-4, совместимый с CODAN (Barrett, Q-MAC, Vertex).\n\n"
                                + "Android-версия ra4pvj/SELCALL.\nhttps://github.com/ra4pvj/selcall-android")
                        .setPositiveButton("OK", null).show();
                return true;
        }
        return super.onOptionsItemSelected(it);
    }

    private void selfTest() {
        if (svc == null) return;
        svc.info("Самопроверка кодека…");
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            boolean ok;
            try {
                ok = SelfTest.run(null, s -> out.append(s).append('\n'));
            } catch (Exception e) {
                ok = false;
                out.append(e);
            }
            final boolean fok = ok;
            h.post(() -> {
                if (svc != null) svc.add("Самопроверка: " + (fok ? "OK" : "ОШИБКА"), fok ? "info" : "error", out.toString().trim(), null);
            });
        }).start();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null || svc == null) return;
        Uri uri = data.getData();
        final SelcallService s = svc;
        if (req == REQ_OPEN) {
            s.info("Декодирование файла…");
            new Thread(() -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    Wav w = Wav.read(in);
                    List<Call> calls = Selcall.decode(w.samples, w.fs, prefs.afc() ? null : prefs.fixedCenter());
                    if (calls.isEmpty()) s.info("В файле вызовов не найдено");
                    for (Call c : calls) s.handleRx(c, 0, true);
                } catch (Exception e) {
                    s.error("Ошибка чтения WAV: " + e.getMessage());
                }
            }).start();
        } else if (req == REQ_SAVE) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                Wav.write(out, s.build(pendingSaveTo, pendingSaveMsg, 48000), 48000);
                s.info("Вызов сохранён в WAV");
            } catch (Exception e) {
                s.error("Ошибка записи WAV: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ журнал
    private final class LogAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return svc == null ? 0 : svc.log.size();
        }

        @Override
        public Object getItem(int p) {
            return svc.log.get(p);
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(int p, View v, ViewGroup parent) {
            if (v == null) v = getLayoutInflater().inflate(R.layout.log_item, parent, false);
            SelcallService.Entry e = svc.log.get(p);
            TextView t = v.findViewById(R.id.text), d = v.findViewById(R.id.detail);
            t.setText(SelcallService.time(e.when) + "  " + e.text);
            int col;
            switch (e.tag) {
                case "mine": col = 0xFF1565C0; break;
                case "rx": col = 0xFF2E7D32; break;
                case "rx_bad": col = 0xFF9E9E9E; break;
                case "tx": col = 0xFFC62828; break;
                case "error": col = 0xFFD84315; break;
                default: col = Color.GRAY;
            }
            t.setTextColor(col);
            t.setTypeface(null, "mine".equals(e.tag) ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            d.setText(e.detail);
            d.setVisibility(e.detail.isEmpty() ? View.GONE : View.VISIBLE);
            return v;
        }
    }
}
