package com.wolfhouse.mod4j.utils;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Modbus 协议相关工具类
 *
 * @author Rylin Wolf
 */
public class ModbusProtocolUtils {
    /**
     * TCP 事务标识符计数器
     */
    private static final AtomicInteger TRANSACTION_ID = new AtomicInteger(0x0000);

    /**
     * 事务标识符上限
     */
    private static final Integer TRANSACTION_ID_BOUND = 0xFFFF;

    private ModbusProtocolUtils() {}

    /**
     * 构建 Modbus RTU 请求报文（含 CRC 校验）
     *
     * @param slaveId      从站 ID
     * @param functionCode 功能码
     * @param address      起始地址
     * @param quantity     寄存器数量
     * @return 构建好的完整 RTU 报文
     */
    public static byte[] buildRtuPdu(int slaveId, int functionCode, int address, int quantity) {
        byte[] pdu = new byte[6];
        pdu[0] = (byte) slaveId;
        pdu[1] = (byte) functionCode;
        pdu[2] = (byte) ((address >> 8) & 0xFF);
        pdu[3] = (byte) (address & 0xFF);
        pdu[4] = (byte) ((quantity >> 8) & 0xFF);
        pdu[5] = (byte) (quantity & 0xFF);

        int    crc   = calculateCrc(pdu, 0, 6);
        byte[] frame = new byte[8];
        System.arraycopy(pdu, 0, frame, 0, 6);
        // CRC 低位在前，高位在后
        frame[6] = (byte) (crc & 0xFF);
        frame[7] = (byte) ((crc >> 8) & 0xFF);
        return frame;
    }

    /**
     * 构建 Modbus TCP 请求报文（含 MBAP 报文头）
     *
     * @param slaveId      从站 ID
     * @param functionCode 功能码
     * @param address      起始地址
     * @param quantity     寄存器数量
     * @return 构建好的完整 TCP 报文
     */
    public static byte[] buildTcpPdu(int slaveId, int functionCode, int address, int quantity) {
        byte[] frame = new byte[12];
        int    tid   = nextTransactionId();
        // Transaction ID
        frame[0] = (byte) ((tid >> 8) & 0xFF);
        frame[1] = (byte) (tid & 0xFF);
        // Protocol ID (0)
        frame[2] = 0;
        frame[3] = 0;
        // Length (6 字节)
        frame[4] = 0;
        frame[5] = 6;
        // Unit ID
        frame[6] = (byte) slaveId;
        // PDU
        frame[7]  = (byte) functionCode;
        frame[8]  = (byte) ((address >> 8) & 0xFF);
        frame[9]  = (byte) (address & 0xFF);
        frame[10] = (byte) ((quantity >> 8) & 0xFF);
        frame[11] = (byte) (quantity & 0xFF);
        return frame;
    }

    /**
     * 计算 CRC16 校验码
     *
     * @param data 字节数组
     * @param off  偏移量
     * @param len  长度
     * @return 计算出的 CRC16 值
     */
    public static int calculateCrc(byte[] data, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < len; i++) {
            crc ^= (data[i] & 0xFF);
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >> 1) ^ 0xA001;
                } else {
                    crc >>= 1;
                }
            }
        }
        return crc;
    }

    /**
     * 获取下一个事务标识符，并处理回绕逻辑
     *
     * @return 下一个事务标识符
     */
    public static Integer nextTransactionId() {
        return TRANSACTION_ID.getAndUpdate(i -> (i + 1) & TRANSACTION_ID_BOUND);
    }
}