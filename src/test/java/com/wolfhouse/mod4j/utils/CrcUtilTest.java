package com.wolfhouse.mod4j.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * @author Rylin Wolf
 */
public class CrcUtilTest {
    /**
     * 计算 CRC16 (Modbus 常用算法)
     * * @param buf    字节数组
     *
     * @return int 类型的 CRC16 结果 (模拟 unsigned short)
     */
    int crc(byte[] buf) {
        // Java 中没有 unsigned short，用 int 替代
        int crc = 0xFFFF;

        for (byte b : buf) {
            // 将 byte 转换为无符号 int 处理：buf[i] & 0xFF
            crc ^= (b & 0xFF);

            for (int k = 0; k < 8; k++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >>> 1) ^ 0xA001;
                } else {
                    crc >>>= 1;
                }
            }
        }

        // 最后确保返回的是 16 位的值
        return crc & 0xFFFF;
    }

    @Test
    public void crcTest() {
        byte[] data = {0x00, 0x00, 0x08, 0x00, 0x01, 0x01};
        assertEquals(0x2BC2, ModbusProtocolUtils.calculateCrc(data, 0, data.length));
        assertEquals(crc(data), ModbusProtocolUtils.calculateCrc(data, 0, data.length));
    }

    @Test
    public void crcUsesOffsetAndByteCount() {
        byte[] data = {99, 1, 3, 0, 0, 0, 1, 99};
        assertEquals(0x0A84, ModbusProtocolUtils.calculateCrc(data, 1, 6));
        assertEquals(0xFFFF, ModbusProtocolUtils.calculateCrc(data, data.length, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> ModbusProtocolUtils.calculateCrc(data, 3, 6));
    }
}
