package ru.ra4pvj.selcall;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/** Настройки (значения по умолчанию — как в HF Selcall для Windows). Все хранятся строками/булевыми. */
final class Prefs {
    final SharedPreferences sp;

    Prefs(Context c) {
        sp = PreferenceManager.getDefaultSharedPreferences(c);
    }

    String str(String k, String def) {
        try {
            String v = sp.getString(k, def);
            return v == null ? def : v;
        } catch (ClassCastException e) {
            return def;
        }
    }

    double num(String k, double def, double lo, double hi) {
        try {
            double v = Double.parseDouble(str(k, "").replace(',', '.').trim());
            return Math.max(lo, Math.min(hi, v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    boolean bool(String k, boolean def) {
        try {
            return sp.getBoolean(k, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    void put(String k, String v) {
        sp.edit().putString(k, v).apply();
    }

    String myId() { return str("my_id", "1234"); }
    String toId() { return str("to_id", ""); }
    double dotSeconds() { return num("dot_seconds", 6.0, 0.5, 30); }
    double txLevel() { return num("tx_level", 0.5, 0.01, 1.0); }
    boolean translit() { return bool("translit", true); }
    boolean afc() { return bool("afc", true); }
    double fixedCenter() { return num("fixed_center", 1785, 1000, 2600); }
    boolean showAll() { return bool("show_all", true); }
    String audioSource() { return str("audio_source", "voice_recognition"); }
    int inDevice() { return (int) num("in_dev", 0, 0, Integer.MAX_VALUE); }
    int outDevice() { return (int) num("out_dev", 0, 0, Integer.MAX_VALUE); }
    boolean muteRxOnTx() { return bool("mute_rx_on_tx", true); }

    String pttMethod() { return str("ptt_method", "none"); }
    String pttPort() { return str("ptt_port", ""); }
    int pttBaud() { return (int) num("ptt_baud", 9600, 300, 921600); }
    boolean pttInvert() { return bool("ptt_invert", false); }
    String icomAddr() { return str("ptt_icom_addr", "94"); }
    String kenwoodVariant() { return str("ptt_kenwood", "TX;"); }
    double pttDelay() { return num("ptt_delay", 0.5, 0, 5); }
    double pttTail() { return num("ptt_tail", 0.3, 0, 5); }

    boolean ring() { return bool("ring", true); }
    String ringStyle() { return str("ring_style", "codan"); }
    String answerStyle() { return str("answer_style", "kalinka"); }
    double ringVolume() { return num("ring_volume", 0.5, 0.01, 1.0); }
    double ringSeconds() { return num("ring_seconds", 30, 1, 600); }
    boolean ringAir() { return bool("ring_air", true); }
    boolean vibrate() { return bool("vibrate", true); }
}
