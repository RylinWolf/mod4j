package com.wolfhouse.mod4j.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Modbus RTU 模拟器工具类，用于测试目的。
 * <p>
 * 由于串口模拟较为复杂且依赖环境，该模拟器提供基于 InputStream 和 OutputStream 的流处理逻辑，
 * 开发者可以配合 PipedInputStream/PipedOutputStream 来模拟串口通信。
 *
 * @author Rylin Wolf
 */
public class ModbusRtuSimulator {
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final InputStream   inputStream;
    private final OutputStream  outputStream;
    private       Thread        processorThread;

    /**
     * 构造函数
     *
     * @param inputStream  接收请求的输入流
     * @param outputStream 发送响应的输出流
     */
    public ModbusRtuSimulator(InputStream inputStream, OutputStream outputStream) {
        this.inputStream  = inputStream;
        this.outputStream = outputStream;
    }

    /**
     * 启动模拟器
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            processorThread = new Thread(this::process, "ModbusRtuSimulator-Thread");
            processorThread.start();
            System.out.println("[mod4j] Modbus RTU 模拟器逻辑已启动");
        }
    }

    /**
     * 停止模拟器
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (processorThread != null) {
                processorThread.interrupt();
            }
            System.out.println("[mod4j] Modbus RTU 模拟器逻辑已停止");
        }
    }

    private void process() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                byte[] request = RtuSimulatorFrames.readRequest(inputStream);
                if (request == null) {
                    break;
                }
                if (!RtuSimulatorFrames.hasValidCrc(request)) {
                    System.err.println("[mod4j] 模拟器收到 CRC 校验错误的报文");
                    continue;
                }

                outputStream.write(RtuSimulatorFrames.response(request[0], responsePdu(request)));
                outputStream.flush();
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[mod4j] RTU 模拟器处理异常: " + e.getMessage());
                }
                break;
            }
        }
    }

    private byte[] responsePdu(byte[] request) {
        byte function = request[1];
        if (function != 0x01 && function != 0x03) {
            // 单写请求及其他既有回环请求保留原 PDU。
            return Arrays.copyOfRange(request, 1, request.length - 2);
        }

        int address = ((request[2] & 0xFF) << 8) | (request[3] & 0xFF);
        int quantity = ((request[4] & 0xFF) << 8) | (request[5] & 0xFF);
        if (quantity < 1 || quantity > ModbusProtocolUtils.maxReadQuantity(function)) {
            return new byte[]{(byte) (function | 0x80), 0x03};
        }
        if (address + quantity > 0x10000) {
            return new byte[]{(byte) (function | 0x80), 0x02};
        }

        boolean coils = function == 0x01;
        int byteCount = coils ? (quantity + 7) / 8 : quantity * 2;
        byte[] pdu = new byte[2 + byteCount];
        pdu[0] = function;
        pdu[1] = (byte) byteCount;
        if (coils) {
            Arrays.fill(pdu, 2, pdu.length, (byte) 0xFF);
            int remainder = quantity % 8;
            if (remainder != 0) {
                pdu[pdu.length - 1] = (byte) ((1 << remainder) - 1);
            }
        } else {
            for (int i = 3; i < pdu.length; i += 2) {
                pdu[i] = 1;
            }
        }
        return pdu;
    }
}
