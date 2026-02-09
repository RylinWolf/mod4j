package com.wolfhouse.mod4j.utils;

/**
 * 十六进制相关工具
 *
 * @author Rylin Wolf
 */
@SuppressWarnings("all")
public class HexUtils {
    private HexUtils() {
    }

    public static int parseInt(String text) {
        if (text.startsWith("0x") || text.startsWith("0X")) {
            return Integer.parseInt(text.substring(2), 16);
        }
        return Integer.parseInt(text);
    }

    public static byte[] parseHexData(String text) {
        return parseHexData(text, "\\s+");
    }

    public static byte[] parseHexData(String text, String delimiter) {
        String[] parts = text.trim().split(delimiter);
        byte[]   data  = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            data[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return data;
    }

    public static byte[] parseHexData(String text, int length) {
        int    len  = text.length();
        byte[] data = new byte[(len + length - 1) / length];
        for (int i = 0; i < data.length; i++) {
            int start = i * length;
            int end   = Math.min(start + length, len);
            data[i] = (byte) Integer.parseInt(text.substring(start, end), 16);
        }
        return data;
    }
}
