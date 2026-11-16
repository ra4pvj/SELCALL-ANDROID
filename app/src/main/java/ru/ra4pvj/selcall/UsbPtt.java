package ru.ra4pvj.selcall;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.util.ArrayList;
import java.util.List;

/**
 * PTT / CAT через USB-COM (CP210x, FTDI, CH340, PL2303, CDC-ACM) — перенос selcall_ptt.py (HFP).
 * Методы: none, RTS, DTR, RTS+DTR, ICOM (CI-V), YAESU, YAESU-OLD, KENWOOD, ELECRAFT.
 */
final class UsbPtt {
    static final String ACTION_USB_PERMISSION = "ru.ra4pvj.selcall.USB_PERMISSION";

    private final Context ctx;
    private UsbSerialPort port;
    private String openKey = "";
    private boolean keyed = false;

    String method = "none";
    String device = "";          // UsbDevice.getDeviceName()
    int baud = 9600;
    boolean invert = false;
    int icomAddr = 0x94;
    String kenwood = "TX;";

    UsbPtt(Context c) {
        ctx = c.getApplicationContext();
    }

    static final class PortInfo {
        final String name, title;

        PortInfo(String name, String title) {
            this.name = name;
            this.title = title;
        }
    }

    static List<PortInfo> list(Context c) {
        List<PortInfo> out = new ArrayList<>();
        try {
            UsbManager um = (UsbManager) c.getSystemService(Context.USB_SERVICE);
            for (UsbSerialDriver d : UsbSerialProber.getDefaultProber().findAllDrivers(um)) {
                UsbDevice dev = d.getDevice();
                String p = dev.getProductName() != null ? dev.getProductName() : "";
                String cls = d.getClass().getSimpleName().replace("SerialDriver", "");
                out.add(new PortInfo(dev.getDeviceName(),
                        String.format("%s %s (%04X:%04X) %s", cls, p, dev.getVendorId(), dev.getProductId(), dev.getDeviceName())));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static int parseHex(String t) {
        String s = t.trim().toLowerCase().replace("0x", "").replace("h", "");
        int v = Integer.parseInt(s, 16);
        if (v <= 0 || v >= 0xFE) throw new NumberFormatException("CI-V адрес 01..FD");
        return v;
    }

    boolean active() {
        return !"none".equals(method);
    }

    private boolean lineMethod() {
        return "RTS".equals(method) || "DTR".equals(method) || "RTS+DTR".equals(method);
    }

    synchronized void configure(Prefs p) {
        String m = p.pttMethod();
        int addr = 0x94;
        try {
            addr = parseHex(p.icomAddr());
        } catch (Exception ignored) {
        }
        String key = m + "|" + p.pttPort() + "|" + p.pttBaud() + "|" + p.pttInvert();
        method = m;
        device = p.pttPort();
        baud = p.pttBaud();
        invert = p.pttInvert();
        icomAddr = addr;
        kenwood = p.kenwoodVariant();
        if (!key.equals(openKey)) close();
        openKey = key;
    }

    private UsbSerialDriver findDriver(UsbManager um) {
        List<UsbSerialDriver> all = UsbSerialProber.getDefaultProber().findAllDrivers(um);
        for (UsbSerialDriver d : all) if (d.getDevice().getDeviceName().equals(device)) return d;
        // устройство переподключено (имя сменилось) — берём единственный найденный
        if (all.size() == 1) return all.get(0);
        return null;
    }

    private String open() {
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        UsbSerialDriver d = findDriver(um);
        if (d == null) return "USB-COM для PTT не найден — подключите кабель и выберите порт в настройках";
        if (!um.hasPermission(d.getDevice())) {
            int fl = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(ctx.getPackageName());
            um.requestPermission(d.getDevice(), PendingIntent.getBroadcast(ctx, 0, i, fl));
            return "Нет доступа к USB-COM: разрешите доступ и повторите";
        }
        UsbDeviceConnection conn = um.openDevice(d.getDevice());
        if (conn == null) return "Не удалось открыть USB-COM";
        UsbSerialPort pt = d.getPorts().get(0);
        try {
            pt.open(conn);
            pt.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            boolean idle = invert;
            if (lineMethod()) {
                if ("RTS".equals(method) || "RTS+DTR".equals(method)) pt.setRTS(idle); else pt.setRTS(false);
                if ("DTR".equals(method) || "RTS+DTR".equals(method)) pt.setDTR(idle); else pt.setDTR(false);
            } else {
                pt.setRTS(false);
                pt.setDTR(false);
            }
            port = pt;
            return null;
        } catch (Exception e) {
            try {
                pt.close();
            } catch (Exception ignored) {
            }
            return "Ошибка открытия USB-COM: " + e.getMessage();
        }
    }

    static byte[] commandBytes(String method, boolean on, int icomAddr, String kenwood) {
        switch (method) {
            case "ICOM":
                return new byte[]{(byte) 0xFE, (byte) 0xFE, (byte) icomAddr, (byte) 0xE0, 0x1C, 0x00, (byte) (on ? 1 : 0), (byte) 0xFD};
            case "YAESU":
                return (on ? "TX1;" : "TX0;").getBytes();
            case "YAESU-OLD":
                return new byte[]{0, 0, 0, 0, (byte) (on ? 0x08 : 0x88)};
            case "KENWOOD":
            case "ELECRAFT":
                if (!on) return "RX;".getBytes();
                String v = ("TX;".equals(kenwood) || "TX0;".equals(kenwood) || "TX1;".equals(kenwood)) ? kenwood : "TX;";
                return v.getBytes();
            default:
                return null;
        }
    }

    /** null — успех, иначе текст ошибки. */
    synchronized String key(boolean on) {
        if (!active()) return null;
        try {
            if (port == null) {
                if (!on) return null;
                String err = open();
                if (err != null) return err;
            }
            if (lineMethod()) {
                boolean level = on != invert;
                if ("RTS".equals(method) || "RTS+DTR".equals(method)) port.setRTS(level);
                if ("DTR".equals(method) || "RTS+DTR".equals(method)) port.setDTR(level);
            } else {
                byte[] b = commandBytes(method, on, icomAddr, kenwood);
                if (b != null) port.write(b, 1000);
                if (on) Thread.sleep(50);
            }
            keyed = on;
            return null;
        } catch (Exception e) {
            if (!on) keyed = false;
            close();
            return "Ошибка PTT (" + method + "): " + e.getMessage();
        }
    }

    synchronized void unkeySafe() {
        for (int i = 0; i < 2; i++) if (key(false) == null) return;
    }

    synchronized void close() {
        if (port != null) {
            try {
                if (keyed) {
                    if (lineMethod()) {
                        port.setRTS(invert && !"DTR".equals(method));
                        port.setDTR(invert && !"RTS".equals(method));
                    } else {
                        byte[] b = commandBytes(method, false, icomAddr, kenwood);
                        if (b != null) port.write(b, 500);
                    }
                }
            } catch (Exception ignored) {
            }
            try {
                port.close();
            } catch (Exception ignored) {
            }
        }
        port = null;
        keyed = false;
    }
}
