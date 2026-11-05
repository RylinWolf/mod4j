package com.wolfhouse.mod4j.device;

import com.wolfhouse.mod4j.exception.ModbusException;
import com.wolfhouse.mod4j.utils.ModbusProtocolUtils;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;

/**
 * 按 RTU 响应的报文结构读取完整帧，TCP 分段和串口分批读取都不会提前结束响应。
 */
final class RtuResponseReader {
    private static final int MAX_FRAME_LENGTH = 256;

    private RtuResponseReader() {}

    static byte[] read(DataInputStream stream, byte[] command, int timeout) throws IOException {
        byte[] frame = new byte[MAX_FRAME_LENGTH];
        stream.readFully(frame, 0, 2);
        int function = frame[1] & 0xFF;
        int requestedFunction = command[1] & 0xFF;
        if (frame[0] != command[0] || (function != requestedFunction && function != (requestedFunction | 0x80))) {
            throw new ModbusException("[mod4j] RTU 响应从站地址或功能码与请求不匹配");
        }

        int headerLength = 2;
        int length;
        if ((function & 0x80) != 0) {
            length = 5;
        } else {
            length = switch (function) {
                case 1, 2, 3, 4, 12, 17, 20, 21, 23 -> {
                    frame[2] = stream.readByte();
                    headerLength = 3;
                    yield 5 + (frame[2] & 0xFF);
                }
                case 5, 6, 15, 16 -> 8;
                case 7 -> 5;
                case 11 -> 8;
                case 22 -> 10;
                case 24 -> {
                    stream.readFully(frame, 2, 2);
                    headerLength = 4;
                    yield 6 + ((frame[2] & 0xFF) << 8) + (frame[3] & 0xFF);
                }
                default -> 0;
            };
        }

        if (length == 0) {
            return readVariableFrame(stream, frame, timeout);
        }
        if (length > frame.length) {
            throw new ModbusException("[mod4j] RTU 响应超出最大帧长");
        }
        stream.readFully(frame, headerLength, length - headerLength);
        if (!hasValidCrc(frame, length)) {
            throw new ModbusException("[mod4j] RTU 响应 CRC 错误");
        }
        return Arrays.copyOf(frame, length);
    }

    private static byte[] readVariableFrame(DataInputStream stream, byte[] frame, int timeout) throws IOException {
        // 厂商自定义功能码没有通用长度字段。等待读取边界后再校验 CRC，避免把有效 CRC 前缀当成整帧。
        if (timeout == 0) {
            throw new ModbusException("[mod4j] 未知长度的 RTU 响应需要配置正数读取超时");
        }
        int length = 2;
        try {
            while (length < frame.length) {
                int value = stream.read();
                if (value < 0) {
                    break;
                }
                frame[length++] = (byte) value;
            }
        } catch (InterruptedIOException e) {
            if (Thread.currentThread().isInterrupted() || length < 4 || !hasValidCrc(frame, length)) {
                throw e;
            }
            return Arrays.copyOf(frame, length);
        }
        if (length < 4 || !hasValidCrc(frame, length)) {
            throw new ModbusException("[mod4j] RTU 响应长度或 CRC 错误");
        }
        return Arrays.copyOf(frame, length);
    }

    private static boolean hasValidCrc(byte[] frame, int length) {
        int received = ((frame[length - 1] & 0xFF) << 8) | (frame[length - 2] & 0xFF);
        return received == ModbusProtocolUtils.calculateCrc(frame, 0, length - 2);
    }
}
