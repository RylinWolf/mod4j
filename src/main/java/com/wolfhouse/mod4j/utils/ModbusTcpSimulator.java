package com.wolfhouse.mod4j.utils;

import lombok.Setter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Modbus TCP 模拟器工具类，用于测试目的。
 * <p>
 * 该模拟器会启动一个 ServerSocket 监听指定端口，并对接收到的 Modbus TCP 请求返回模拟响应。
 *
 * @author Rylin Wolf
 */
public class ModbusTcpSimulator implements AutoCloseable {
    private final int                                  port;
    /** 是否为 TCP 通信方式（非 RTU） */
    private final boolean                              isTcpStrategy;
    private final ExecutorService                      executorService;
    private final AtomicBoolean                        running     = new AtomicBoolean(false);
    /** 虚拟响应结果封装, 主机号 - 响应结果封装 */
    private final Map<String, ArrayList<MockRespPair>> mockRespMap = new ConcurrentHashMap<>();
    private       ServerSocket                         serverSocket;
    @Setter
    private       Consumer<String>                     logConsumer;


    /**
     * 构造函数
     *
     * @param port 监听端口
     */
    public ModbusTcpSimulator(int port) {
        this(port, true);
    }

    public ModbusTcpSimulator(int port, boolean isTcpStrategy) {
        this.isTcpStrategy   = isTcpStrategy;
        this.port            = port;
        this.executorService = new ThreadPoolExecutor(
                // 核心线程数
                1,
                // 最大线程数
                1,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                Executors.defaultThreadFactory());
    }

    private void log(String message) {
        if (logConsumer != null) {
            logConsumer.accept(message);
        }
        System.out.println(message);
    }

    private void logErr(String message) {
        if (logConsumer != null) {
            logConsumer.accept("ERR: " + message);
        }
        System.err.println(message);
    }

    private byte[] getBytes(byte[] pdu) {
        // 该方法接受的 pdu 是完整的 modbus 报文
        // 返回的报文从功能码开始，不包括主机位
        byte   slaveAddr    = pdu[0];
        byte   functionCode = pdu[1];
        byte[] registerAddr = Arrays.copyOfRange(pdu, 2, 4);
        int    registerAddrValue;
        try {
            registerAddrValue = ByteBuffer.wrap(registerAddr).order(ByteOrder.BIG_ENDIAN).getInt();
        } catch (BufferUnderflowException e) {
            if (registerAddr.length >= 4) {
                throw e;
            }
            // 数组长度不足4字节
            // 创建4字节数组并补0
            byte[] padded = new byte[4];
            // 大端序：补0在前面
            System.arraycopy(registerAddr, 0, padded, 4 - registerAddr.length, registerAddr.length);
            registerAddrValue = ByteBuffer.wrap(padded).order(ByteOrder.BIG_ENDIAN).getInt();
        }

        int quantity = 1;
        if (pdu.length >= 6) {
            // 读取请求的数量字段（连读时需要）
            quantity = ((pdu[4] & 0xFF) << 8) | (pdu[5] & 0xFF);
        }

        return getMockResp(slaveAddr, registerAddrValue, functionCode, quantity);
    }

    private byte[] getMockResp(byte slaveAddr, int registerAddrValue, byte functionCode, int quantity) {
        // 只考虑功能码是 03 (读保持寄存器) 的情况
        if (functionCode != 0x03) {
            return null;
        }

        // 通过主机位(从机地址)确定要返回的虚拟数据列表
        String                  hostKey = String.valueOf(slaveAddr & 0xFF);
        ArrayList<MockRespPair> pairs   = mockRespMap.get(hostKey);
        if (pairs == null || pairs.isEmpty()) {
            return null;
        }

        // 虚拟数据已经实现了 comparable，意味着列表是可以基于寄存器地址排序的
        // 确保列表有序，以便按地址顺序提取数据
        pairs.sort(null);

        // 构建数据并返回
        // Modbus 03 功能码响应格式: [功能码(1B)] + [字节数(1B)] + [寄存器数据(N*2B)]
        int    byteCount = quantity * 2;
        byte[] response  = new byte[2 + byteCount];
        response[0] = functionCode;
        response[1] = (byte) byteCount;

        // 填充数据，默认全为0
        // 基于请求的寄存器地址和寄存器数量，匹配列表中的虚拟数据
        int startAddr = registerAddrValue;
        int endAddr   = registerAddrValue + quantity;

        for (MockRespPair pair : pairs) {
            // 检查该 MockRespPair 是否在请求范围内
            // 假设 MockRespPair 的 dataSize 是字节数，Modbus 寄存器是2字节
            int pairStart = pair.registerAddr();
            int pairEnd   = pairStart + (pair.dataSize() + 1) / 2; // 向上取整计算占用的寄存器数

            // 寻找交集
            int intersectStart = Math.max(startAddr, pairStart);
            int intersectEnd   = Math.min(endAddr, pairEnd);

            if (intersectStart < intersectEnd) {
                // 有交集，计算在 response 中的偏移量
                // response[2] 是数据开始位置
                for (int addr = intersectStart; addr < intersectEnd; addr++) {
                    int responseOffset = (addr - startAddr) * 2;
                    int dataOffset     = (addr - pairStart) * 2;

                    if (pair.randData()) {
                        // 如果是随机数据，这里简单填充随机字节
                        ThreadLocalRandom.current().nextBytes(new byte[2]); // 占位
                        response[2 + responseOffset]     = (byte) ThreadLocalRandom.current().nextInt(256);
                        response[2 + responseOffset + 1] = (byte) ThreadLocalRandom.current().nextInt(256);
                    } else if (pair.data() != null) {
                        // 从预设数据中提取
                        if (dataOffset < pair.data().length) {
                            response[2 + responseOffset] = pair.data()[dataOffset];
                        }
                        if (dataOffset + 1 < pair.data().length) {
                            response[2 + responseOffset + 1] = pair.data()[dataOffset + 1];
                        }
                    }
                }
            }
        }

        return response;
    }

    /**
     * 启动模拟器
     *
     * @throws IOException 如果无法启动服务器
     */
    public void start() throws IOException {
        if (running.compareAndSet(false, true)) {
            serverSocket = new ServerSocket(port);
            log("[mod4j] Modbus TCP 模拟器已启动，监听端口: " + port);

            executorService.execute(() -> {
                while (running.get()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        executorService.execute(() -> handleClient(clientSocket));
                    } catch (IOException e) {
                        if (running.get()) {
                            logErr("[mod4j] 模拟器接受连接异常: " + e.getMessage());
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
                logErr("[mod4j] 关闭模拟器 ServerSocket 异常: " + e.getMessage());
            }
            executorService.shutdownNow();
            log("[mod4j] Modbus TCP 模拟器已停止");
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

                StringBuilder sb = new StringBuilder();
                for (byte b : request) {
                    sb.append(String.format("%02x", b));
                }
                log(String.format("[%s] 收到请求: %s", this.port, sb));
                byte[] response;
                if (isTcpStrategy) {
                    response = handleTcpRequest(request);
                } else {
                    response = handleRtuRequest(request);
                }
                if (response == null) {
                    logErr("响应为空，可能是请求有误，跳过处理");
                    continue;
                }
                // 写入响应并刷新
                os.write(response);
                os.flush();

                StringBuilder respSb = new StringBuilder();
                for (byte b : response) {
                    respSb.append(String.format("%02x", b));
                }
                log(String.format("[%s] 发送响应: %s", this.port, respSb));
            }
        } catch (IOException e) {
            if (running.get()) {
                log(String.format("[mod4j] 模拟器处理客户端连接异常: %s", e.getMessage()));
            }
        }
    }

    private byte[] handleTcpRequest(byte[] request) {
        // MBAP(6) + slave + PDU
        byte[] pdu = new byte[request.length - 6];
        System.arraycopy(request, 6, pdu, 0, pdu.length);
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
        // 不符合长度要求
        if (request.length < 4) {
            return null;
        }
        // CRC 校验
        int receivedCrc   = ((request[request.length - 1] & 0xFF) << 8) | (request[request.length - 2] & 0xFF);
        int calculatedCrc = ModbusProtocolUtils.calculateCrc(request, 0, request.length - 2);
        if (receivedCrc != calculatedCrc) {
            System.err.println("[mod4j] 模拟器收到错误的 RTU CRC");
            // return null; // Or handle it
        }
        byte[] responseInnerPdu = getBytes(request);

        // RTU 响应: SlaveID(1) + PDU + CRC(2)
        byte[] response = new byte[1 + responseInnerPdu.length + 2];
        response[0] = request[0];
        System.arraycopy(responseInnerPdu, 0, response, 1, responseInnerPdu.length);
        int crc = ModbusProtocolUtils.calculateCrc(response, 0, 1 + responseInnerPdu.length);
        response[response.length - 2] = (byte) (crc & 0xFF);
        response[response.length - 1] = (byte) ((crc >> 8) & 0xFF);
        return response;
    }

    /**
     * 为指定主机号添加虚拟响应结果
     *
     * @param host 主机号
     * @param pair 虚拟响应结果
     */
    public List<MockRespPair> addMockResp(String host, MockRespPair pair) {
        ArrayList<MockRespPair> list = mockRespMap.computeIfAbsent(host, _ -> new ArrayList<>());
        list.add(pair);
        return list;
    }

    /**
     * 清除所有虚拟响应
     */
    public void clearMockResps() {
        mockRespMap.clear();
    }

    @Override
    public void close() {
        this.stop();
    }

    /**
     * 虚拟结果封装
     *
     * @param registerAddr 寄存器地址
     * @param dataSize     数据长度（字节数）
     * @param randData     是否随机数据
     * @param data         数据
     */
    public record MockRespPair(int registerAddr,
                               int dataSize,
                               boolean randData,
                               byte[] data) implements Comparable<MockRespPair> {
        @Override
        public int compareTo(MockRespPair o) {
            return Integer.compare(registerAddr, o.registerAddr);
        }
    }

}
