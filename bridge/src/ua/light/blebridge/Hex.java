package ua.light.blebridge;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

final class Hex {
    private Hex() {
    }

    static String encode(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xff));
        return sb.toString();
    }

    /** Accepts "7e0004", "7e 00 04", "7e:00:04". */
    static byte[] decode(String s) {
        String h = s.replaceAll("[\\s:,-]", "").toLowerCase(Locale.US);
        if (h.isEmpty() || h.length() % 2 != 0 || !h.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException("bad hex: " + s);
        }
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    static byte[] trimZeros(byte[] b) {
        if (b == null) return new byte[0];
        int n = b.length;
        while (n > 0 && b[n - 1] == 0) n--;
        return Arrays.copyOf(b, n);
    }

    static String printable(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(x >= 32 && x < 127 ? (char) x : '.');
        return sb.toString();
    }

    /** "fff3" and "0000fff3" expand to the Bluetooth base UUID; full UUIDs pass through. */
    static UUID uuid(String s) {
        String t = s.trim().toLowerCase(Locale.US);
        if (t.matches("[0-9a-f]{4}")) t = "0000" + t;
        if (t.matches("[0-9a-f]{8}")) t = t + "-0000-1000-8000-00805f9b34fb";
        try {
            return UUID.fromString(t);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad uuid: " + s);
        }
    }
}
