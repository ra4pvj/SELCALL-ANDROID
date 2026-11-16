package ru.ra4pvj.selcall;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("deprecation")
public class SettingsActivity extends PreferenceActivity implements SharedPreferences.OnSharedPreferenceChangeListener {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        addPreferencesFromResource(R.xml.prefs);
        fillDevices("in_dev", AudioManager.GET_DEVICES_INPUTS);
        fillDevices("out_dev", AudioManager.GET_DEVICES_OUTPUTS);
        fillPorts();
        updateSummaries(getPreferenceScreen());
    }

    @Override
    protected void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onPause() {
        getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (key == null) return;
        Preference p = findPreference(key);
        if (p != null) summary(p);
    }

    private void updateSummaries(PreferenceGroup g) {
        for (int i = 0; i < g.getPreferenceCount(); i++) {
            Preference p = g.getPreference(i);
            if (p instanceof PreferenceGroup) updateSummaries((PreferenceGroup) p);
            else summary(p);
        }
    }

    private void summary(Preference p) {
        if (p instanceof ListPreference) {
            CharSequence e = ((ListPreference) p).getEntry();
            p.setSummary(e == null ? "" : e.toString().replace("%", "%%"));
        } else if (p instanceof EditTextPreference) {
            p.setSummary(((EditTextPreference) p).getText());
        }
    }

    private static String typeName(int t) {
        switch (t) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "встроенный микрофон";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "динамик";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "разговорный динамик";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "гарнитура (проводная)";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "наушники (проводные)";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB-аудио";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB-гарнитура";
            case AudioDeviceInfo.TYPE_USB_ACCESSORY: return "USB-аксессуар";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "Bluetooth (SCO)";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth (A2DP)";
            case AudioDeviceInfo.TYPE_LINE_ANALOG: return "линейный аналоговый";
            case AudioDeviceInfo.TYPE_LINE_DIGITAL: return "линейный цифровой";
            case AudioDeviceInfo.TYPE_TELEPHONY: return "телефония";
            default: return "тип " + t;
        }
    }

    private void fillDevices(String key, int flag) {
        ListPreference lp = (ListPreference) findPreference(key);
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        List<String> names = new ArrayList<>(), vals = new ArrayList<>();
        names.add("Автоматически (системный)");
        vals.add("0");
        for (AudioDeviceInfo d : am.getDevices(flag)) {
            if (d.getType() == AudioDeviceInfo.TYPE_TELEPHONY || d.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) continue;
            String pn = d.getProductName() == null ? "" : d.getProductName().toString();
            names.add(typeName(d.getType()) + (pn.isEmpty() ? "" : " — " + pn));
            vals.add(Integer.toString(d.getId()));
        }
        lp.setEntries(names.toArray(new String[0]));
        lp.setEntryValues(vals.toArray(new String[0]));
        if (lp.getValue() == null || lp.findIndexOfValue(lp.getValue()) < 0) lp.setValue("0");
    }

    private void fillPorts() {
        ListPreference lp = (ListPreference) findPreference("ptt_port");
        List<String> names = new ArrayList<>(), vals = new ArrayList<>();
        for (UsbPtt.PortInfo p : UsbPtt.list(this)) {
            names.add(p.title);
            vals.add(p.name);
        }
        String cur = lp.getValue();
        if (cur != null && !cur.isEmpty() && !vals.contains(cur)) {
            names.add(cur + " (не подключён)");
            vals.add(cur);
        }
        if (names.isEmpty()) {
            names.add("USB-COM не найден");
            vals.add("");
        }
        lp.setEntries(names.toArray(new String[0]));
        lp.setEntryValues(vals.toArray(new String[0]));
    }
}
