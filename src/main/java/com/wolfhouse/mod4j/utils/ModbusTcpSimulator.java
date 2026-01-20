package com.wolfhouse.mod4j.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Modbus TCP 模拟器工具类，用于测试目的。
 * <p>
 * 该模拟器会启动一个 ServerSocket 监听指定端口，并对接收到的 Modbus TCP 请求返回模拟响应。
 *
 * @author Rylin Wolf
 */
public class ModbusTcpSimulator {
    private final int             port;
    private final ExecutorService executorService;
    private final AtomicBoolean   running = new AtomicBoolean(false);
    private       ServerSocket    serverSocket;

    /**
     * 构造函数
     *
     * @param port 监听端口
     */
    public ModbusTcpSimulator(int port) {
        this.port            = port;
        this.executorService = new ThreadPoolExecutor(
                // 核心线程数
                0,
                // 最大线程数
                200,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                Executors.defaultThreadFactory());
    }

    private static byte[] getBytes(byte[] pdu) {
        byte   functionCode = pdu[0];
        byte[] responsePdu;

        if (functionCode == 0x03) {
            // 模拟返回：字节数(2), 数据(00 01)
            responsePdu = new byte[]{0x03, 0x02, 0x00, 0x01};
        } else if (functionCode == 0x01) {
            // 模拟返回：字节数(1), 数据(01)
            responsePdu = new byte[]{0x01, 0x01, 0x01};
        } else {
            // 其他功能码返回原样（回环测试）
            responsePdu = pdu;
        }
        return responsePdu;
    }

    /**
     * 启动模拟器
     *
     * @throws IOException 如果无法启动服务器
     */
    public void start() throws IOException {
        if (running.compareAndSet(false, true)) {
            serverSocket = new ServerSocket(port);
            System.out.println("[mod4j] Modbus TCP 模拟器已启动，监听端口: " + port);

            executorService.execute(() -> {
                while (running.get()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        executorService.execute(() -> handleClient(clientSocket));
                    } catch (IOException e) {
                        if (running.get()) {
                            System.err.println("[mod4j] 模拟器接受连接异常: " + e.getMessage());
                        }
                    }
                }
            });
        }
    }

    /**
     * 停止模拟器
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            try {
                if (serverSocket != null) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                System.err.println("[mod4j] 关闭模拟器 ServerSocket 异常: " + e.getMessage());
            }
            executorService.shutdownNow();
            System.out.println("[mod4j] Modbus TCP 模拟器已停止");
        }
    }

    /**
     * 处理客户端连接
     *
     * @param socket 客户端 Socket
     */
    private void handleClient(Socket socket) {
        try (socket; InputStream is = socket.getInputStream();
             OutputStream os = socket.getOutputStream()) {
            try {

                byte[] header = new byte[7];
                while (running.get() && is.read(header) != -1) {
                    // 解析长度 (MBAP header: Unit ID 之后的所有内容长度)
                    int length = ((header[4] & 0xFF) << 8) | (header[5] & 0xFF);

                    // 读取 PDU (包含 Unit ID，所以是 length 字节，但 header 已经读了 Unit ID 了吗？)
                    // 修正：TcpModbusDevice.java 中 header 读了 7 字节，包含 Unit ID
                    // 所以还需要读 length - 1 字节
                    byte[] pdu  = new byte[length - 1];
                    int    read = is.read(pdu);
                    if (read == -1) {
                        break;
                    }

                    // 简单的模拟响应：根据功能码返回固定数据
                    // 假设是读取保持寄存器 (03) 或 读取线圈 (01)
                    byte[] responsePdu = getBytes(pdu);

                    // 构建响应报文
                    byte[] response = new byte[7 + responsePdu.length];
                    // 复制 Transaction ID, Protocol ID
                    System.arraycopy(header, 0, response, 0, 4);
                    // 设置新长度 (Unit ID + responsePdu)
                    int newLength = 1 + responsePdu.length;
                    response[4] = (byte) ((newLength >> 8) & 0xFF);
                    response[5] = (byte) (newLength & 0xFF);
                    // Unit ID
                    response[6] = header[6];
                    // PDU
                    System.arraycopy(responsePdu, 0, response, 7, responsePdu.length);

                    os.write(response);
                    os.flush();
                }
            } catch (IOException e) {
                if (running.get()) {
                    System.out.println("[mod4j] 模拟器处理客户端连接异常: " + e.getMessage());
                }
            }
        } catch (IOException ignored) {
        }
    }
}
