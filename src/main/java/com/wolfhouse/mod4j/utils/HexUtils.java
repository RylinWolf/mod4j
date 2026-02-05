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
        String[] parts = text.trim().split("\\s+");
        byte[]   data  = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            data[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return data;
    }
}
