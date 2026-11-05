package com.wolfhouse.mod4j.utils;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** RTU 模拟器使用的请求定长或长度字段分帧，以及 CRC 处理。 */
final class RtuSimulatorFrames {
    private RtuSimulatorFrames() {}

    static byte[] readRequest(InputStream input) throws IOException {
        byte[] header = input.readNBytes(2);
        if (header.length == 0) {
            return null;
        }
        if (header.length != 2) {
            throw new EOFException("RTU 请求头不完整");
        }

        int function = header[1] & 0xFF;
        int length = switch (function) {
            case 0x07, 0x0B, 0x0C, 0x11 -> 4;
            case 0x16 -> 10;
            case 0x18 -> 6;
            default -> 8;
        };
        int countOffset = switch (function) {
            case 0x0F, 0x10 -> 6;
            case 0x14, 0x15 -> 2;
            case 0x17 -> 10;
            default -> -1;
        };
        if (countOffset >= 0) {
            header = readRemaining(input, header, countOffset + 1);
            length = header.length + (header[countOffset] & 0xFF) + 2;
            if (length > 256) {
                throw new IOException("RTU 请求超出最大帧长");
            }
        }
        return readRemaining(input, header, length);
    }

    private static byte[] readRemaining(InputStream input, byte[] prefix, int length) throws IOException {
        byte[] frame = Arrays.copyOf(prefix, length);
        int remaining = length - prefix.length;
        if (input.readNBytes(frame, prefix.length, remaining) != remaining) {
            throw new EOFException("RTU 请求不完整");
        }
        return frame;
    }

    static boolean hasValidCrc(byte[] frame) {
        int dataLength = frame.length - 2;
        int received = (frame[dataLength] & 0xFF) | ((frame[dataLength + 1] & 0xFF) << 8);
        return received == ModbusProtocolUtils.calculateCrc(frame, 0, dataLength);
    }

    static byte[] response(byte unit, byte[] pdu) {
        byte[] frame = new byte[pdu.length + 3];
        frame[0] = unit;
        System.arraycopy(pdu, 0, frame, 1, pdu.length);
        int crcOffset = frame.length - 2;
        int crc = ModbusProtocolUtils.calculateCrc(frame, 0, crcOffset);
        frame[crcOffset] = (byte) crc;
        frame[crcOffset + 1] = (byte) (crc >> 8);
        return frame;
    }
}
