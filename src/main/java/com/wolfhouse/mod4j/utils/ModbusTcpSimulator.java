package com.wolfhouse.mod4j.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
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
            responsePdu = new byte[]{0x03, 0x02, 0x31, 0x11};
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
            byte[] buffer = new byte[1024];
            while (running.get()) {
                int read = is.read(buffer);
                if (read == -1) {
                    break;
                }

                byte[] request = new byte[read];
                System.arraycopy(buffer, 0, request, 0, read);
                System.out.printf("收到请求: %s%n", Arrays.toString(request));
                byte[] response;
                // 假设是 RTU 设备
                //if (isTcpPacket(request)) {
                //    response = handleTcpRequest(request);
                //} else {
                //    response = handleRtuRequest(request);
                //}
                response = handleRtuRequest(request);
                if (response != null) {
                    os.write(response);
                    os.flush();
                }
                System.out.printf("发送响应: %s%n", Arrays.toString(response));
            }
        } catch (IOException e) {
            if (running.get()) {
                System.out.println("[mod4j] 模拟器处理客户端连接异常: " + e.getMessage());
            }
        }
    }

    private boolean isTcpPacket(byte[] data) {
        if (data.length < 7) {
            return false;
        }
        // Protocol ID (bytes 2-3) should be 0 for Modbus TCP
        if (data[2] != 0 || data[3] != 0) {
            return false;
        }
        // Length (bytes 4-5)
        int length = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        return length == data.length - 6;
    }

    private byte[] handleTcpRequest(byte[] request) {
        // MBAP(7) + PDU
        byte[] pdu = new byte[request.length - 7];
        System.arraycopy(request, 7, pdu, 0, pdu.length);
        byte[] responsePdu = getBytes(pdu);

        byte[] response = new byte[7 + responsePdu.length];
        System.arraycopy(request, 0, response, 0, 4); // TID, PID
        int newLength = 1 + responsePdu.length;
        response[4] = (byte) ((newLength >> 8) & 0xFF);
        response[5] = (byte) (newLength & 0xFF);
        response[6] = request[6]; // Unit ID
        System.arraycopy(responsePdu, 0, response, 7, responsePdu.length);
        return response;
    }

    private byte[] handleRtuRequest(byte[] request) {
        if (request.length < 4) {
            return null;
        }
        // SlaveID(1) + Func(1) + Data(n) + CRC(2)
        byte[] pdu = new byte[request.length - 3]; // SlaveID + Func + Data
        System.arraycopy(request, 0, pdu, 0, pdu.length);

        // Simple CRC check (optional for simulator but good)
        int receivedCrc   = ((request[request.length - 1] & 0xFF) << 8) | (request[request.length - 2] & 0xFF);
        int calculatedCrc = ModbusProtocolUtils.calculateCrc(request, 0, request.length - 2);
        if (receivedCrc != calculatedCrc) {
            System.err.println("[mod4j] 模拟器收到错误的 RTU CRC");
            // return null; // Or handle it
        }

        // PDU for getBytes starts with Function Code, which is at request[1]
        byte[] innerPdu = new byte[request.length - 4];
        System.arraycopy(request, 1, innerPdu, 0, innerPdu.length);
        // getBytes expects Function Code as first byte
        byte[] funcAndData = new byte[request.length - 3];
        System.arraycopy(request, 1, funcAndData, 0, funcAndData.length);

        byte[] responseInnerPdu = getBytes(funcAndData);

        // RTU Response: SlaveID(1) + PDU + CRC(2)
        byte[] response = new byte[1 + responseInnerPdu.length + 2];
        response[0] = request[0];
        System.arraycopy(responseInnerPdu, 0, response, 1, responseInnerPdu.length);
        int crc = ModbusProtocolUtils.calculateCrc(response, 0, 1 + responseInnerPdu.length);
        response[response.length - 2] = (byte) (crc & 0xFF);
        response[response.length - 1] = (byte) ((crc >> 8) & 0xFF);
        return response;
    }
}
