package com.wolfhouse.mod4j.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
        byte[] buffer = new byte[256];
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                int read = inputStream.read(buffer);
                if (read == -1) {
                    break;
                }

                if (read < 4) {
                    // 至少需要 SlaveID(1) + Func(1) + CRC(2)
                    continue;
                }

                // 简单的 CRC 校验验证（可选，为了模拟真实性）
                int receivedCrc   = ((buffer[read - 1] & 0xFF) << 8) | (buffer[read - 2] & 0xFF);
                int calculatedCrc = ModbusProtocolUtils.calculateCrc(buffer, 0, read - 2);

                if (receivedCrc != calculatedCrc) {
                    System.err.println("[mod4j] 模拟器收到 CRC 校验错误的报文");
                    continue;
                }

                byte slaveId      = buffer[0];
                byte functionCode = buffer[1];

                byte[] responsePdu;
                if (functionCode == 0x03) {
                    // 模拟返回：字节数(2), 数据(00 01)
                    responsePdu = new byte[]{0x03, 0x02, 0x00, 0x01};
                } else if (functionCode == 0x01) {
                    // 模拟返回：字节数(1), 数据(01)
                    responsePdu = new byte[]{0x01, 0x01, 0x01};
                } else {
                    // 其他回环
                    responsePdu = new byte[read - 2];
                    System.arraycopy(buffer, 0, responsePdu, 0, read - 2);
                }

                // 构建带 SlaveID 和 CRC 的响应
                byte[] responseFrame = new byte[responsePdu.length + 3];
                responseFrame[0] = slaveId;
                System.arraycopy(responsePdu, 0, responseFrame, 1, responsePdu.length);

                int crc = ModbusProtocolUtils.calculateCrc(responseFrame, 0, responseFrame.length - 2);
                responseFrame[responseFrame.length - 2] = (byte) (crc & 0xFF);
                responseFrame[responseFrame.length - 1] = (byte) ((crc >> 8) & 0xFF);

                outputStream.write(responseFrame);
                outputStream.flush();

            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[mod4j] RTU 模拟器处理异常: " + e.getMessage());
                }
                break;
            }
        }
    }
}
