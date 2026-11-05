package com.wolfhouse.mod4j.utils;

import lombok.Getter;
import lombok.Setter;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Modbus TCP 模拟器工具类，用于测试目的。
 * <p>
 * 该模拟器会启动一个 ServerSocket 监听指定端口，并对接收到的 Modbus TCP 请求返回模拟响应。
 *
 * @author Rylin Wolf
 */
public class ModbusTcpSimulator implements AutoCloseable {
    private final InetSocketAddress bindAddress;
    private final boolean isTcpStrategy;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private ExecutorService simulatorStarterService;
    private ExecutorService executorService;
    /** 虚拟响应结果封装, 主机号 - 响应结果封装 */
    private final Map<String, ArrayList<MockRespPair>> mockRespMap      = new ConcurrentHashMap<>();
    /** 虚拟响应延时配置映射, 主机号 - 延时配置封装 */
    private final Map<String, TimeoutOpt>              timeoutOptMap    = new ConcurrentHashMap<>();
    /** 全局虚拟延时配置，生效范围当前实例(端口)，优先级低于主机号配置 */
    @Getter
    @Setter
    private       TimeoutOpt                           globalTimeoutOpt = new TimeoutOpt(0, 0, 1.0);
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
        this(new InetSocketAddress(port), isTcpStrategy);
    }

    public ModbusTcpSimulator(InetSocketAddress bindAddress, boolean isTcpStrategy) {
        this.bindAddress = bindAddress;
        this.isTcpStrategy = isTcpStrategy;
    }

    /**
     * 获取实际监听端口。传入端口 0 时由系统分配。
     *
     * @return 实际端口，未启动时为 -1
     */
    public synchronized int getLocalPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
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

    private byte[] getBytes(byte[] pdu) throws InterruptedException {
        // 该方法接受的 pdu 是完整的 modbus 报文
        // 返回的报文从功能码开始，不包括主机位
        byte slaveAddr         = pdu[0];
        byte functionCode      = pdu[1];
        int  registerAddrValue = ((pdu[2] & 0xFF) << 8) | (pdu[3] & 0xFF);

        int quantity = 1;
        if (pdu.length >= 6) {
            // 读取请求的数量字段（连读时需要）
            quantity = ((pdu[4] & 0xFF) << 8) | (pdu[5] & 0xFF);
        }

        return getMockResp(slaveAddr, registerAddrValue, functionCode, quantity);
    }

    private byte[] getMockResp(byte slaveAddr, int registerAddrValue, byte functionCode, int quantity) throws InterruptedException {
        String hostKey = String.format("%02x", slaveAddr & 0xFF);
        // 延时配置
        TimeoutOpt timeoutOpt = timeoutOptMap.get(hostKey);
        if (timeoutOpt == null) {
            timeoutOpt = globalTimeoutOpt;
        }
        if (!RandomUtils.boolWithProbability(timeoutOpt.successRate)) {
            // 概率配置：响应不成功
            return null;
        }
        // 通过主机位(从机地址)确定要返回的虚拟数据列表
        ArrayList<MockRespPair> pairs = mockRespMap.get(hostKey);
        if (pairs == null || pairs.isEmpty()) {
            return new byte[]{};
        }

        // 过滤出功能码匹配的响应
        List<MockRespPair> matchedPairs = pairs.stream()
                                               .filter(p -> p.functionCode() == (functionCode & 0xFF))
                                               .sorted()
                                               .toList();

        if (matchedPairs.isEmpty()) {
            return new byte[]{};
        }

        // 构建数据并返回
        // Modbus 读响应格式: [功能码(1B)] + [字节数(1B)] + [数据(N*B)]
        // 注意：01, 02 功能码返回的是位数据，此处通用逻辑暂按字节处理（主要是 03, 04）
        int    byteCount = quantity * 2;
        byte[] response  = new byte[2 + byteCount];
        response[0] = functionCode;
        response[1] = (byte) byteCount;

        // 填充数据，默认全为0
        // 基于请求的寄存器地址和寄存器数量，匹配列表中的虚拟数据
        int startAddr = registerAddrValue;
        int endAddr   = registerAddrValue + quantity;

        for (MockRespPair pair : matchedPairs) {
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
        // 模拟器响应延迟
        TimeUnit.MILLISECONDS.sleep((long) RandomUtils.getGaussian(timeoutOpt.mean, timeoutOpt.std));
        return response;
    }

    /**
     * 启动模拟器
     *
     * @throws IOException 如果无法启动服务器
     */
    public synchronized void start() throws IOException {
        if (serverSocket != null) {
            return;
        }
        ServerSocket listener = new ServerSocket();
        try {
            listener.bind(bindAddress);
        } catch (IOException | RuntimeException e) {
            listener.close();
            throw e;
        }

        serverSocket = listener;
        simulatorStarterService = Executors.newSingleThreadExecutor();
        executorService = new ThreadPoolExecutor(6, 24, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>());
        simulatorStarterService.execute(() -> acceptClients(listener));
        log("[mod4j] Modbus TCP 模拟器已启动，监听端口: " + listener.getLocalPort());
    }

    private void acceptClients(ServerSocket listener) {
        while (!listener.isClosed()) {
            try {
                Socket client = listener.accept();
                synchronized (this) {
                    if (serverSocket == listener) {
                        clients.add(client);
                        try {
                            executorService.execute(() -> handleClient(client, listener));
                            continue;
                        } catch (RejectedExecutionException e) {
                            clients.remove(client);
                            logErr("[mod4j] 模拟器连接数已达上限");
                        }
                    }
                }
                client.close();
            } catch (IOException e) {
                if (!listener.isClosed()) {
                    logErr("[mod4j] 模拟器接受连接异常: " + e);
                }
            }
        }
    }

    /**
     * 停止模拟器并关闭已有客户端连接。
     */
    public synchronized void stop() {
        if (serverSocket == null) {
            return;
        }
        closeSocket(serverSocket);
        serverSocket = null;
        clients.forEach(this::closeSocket);
        clients.clear();
        simulatorStarterService.shutdownNow();
        executorService.shutdownNow();
        log("[mod4j] Modbus TCP 模拟器已停止");
    }

    private void closeSocket(java.io.Closeable socket) {
        try {
            socket.close();
        } catch (IOException e) {
            logErr("[mod4j] 关闭模拟器 Socket 异常: " + e.getMessage());
        }
    }

    private void handleClient(Socket socket, ServerSocket listener) {
        try (socket; InputStream is = socket.getInputStream();
             OutputStream os = socket.getOutputStream()) {
            byte[] buffer = new byte[1024];
            while (!listener.isClosed()) {
                byte[] request = isTcpStrategy ? readTcpRequest(is) : readRtuRequest(is, buffer);
                if (request == null) {
                    break;
                }

                StringBuilder sb = new StringBuilder();
                for (byte b : request) {
                    sb.append(String.format("%02x", b));
                }
                log(String.format("[%s] 收到请求: %s", listener.getLocalPort(), sb));
                byte[] response;
                if (isTcpStrategy) {
                    response = handleTcpRequest(request);
                } else {
                    response = handleRtuRequest(request);
                }
                if (response == null) {
                    logErr("响应为空，可能是响应不成功(成功率配置不为 1) 或请求有误，跳过处理");
                    continue;
                }
                // 写入响应并刷新
                os.write(response);
                os.flush();

                StringBuilder respSb = new StringBuilder();
                for (byte b : response) {
                    respSb.append(String.format("%02x", b));
                }
                log(String.format("[%s] 发送响应: %s", listener.getLocalPort(), respSb));
            }
        } catch (IOException e) {
            if (!listener.isClosed()) {
                logErr(String.format("[mod4j] 模拟器处理客户端连接异常: %s", e.getMessage()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            logErr(String.format("[mod4j] 模拟器发生异常: %s", e.getMessage()));
        } finally {
            clients.remove(socket);
        }
    }

    private byte[] readTcpRequest(InputStream input) throws IOException {
        byte[] header = input.readNBytes(6);
        if (header.length == 0) {
            return null;
        }
        if (header.length != 6) {
            throw new EOFException("TCP 请求头不完整");
        }
        int length = ((header[4] & 0xFF) << 8) | (header[5] & 0xFF);
        if (length < 2 || length > 254 || header[2] != 0 || header[3] != 0) {
            throw new IOException("TCP 请求头无效");
        }
        byte[] request = Arrays.copyOf(header, header.length + length);
        if (input.readNBytes(request, header.length, length) != length) {
            throw new EOFException("TCP 请求数据不完整");
        }
        return request;
    }

    private byte[] readRtuRequest(InputStream input, byte[] buffer) throws IOException {
        int size = input.read(buffer);
        return size == -1 ? null : Arrays.copyOf(buffer, size);
    }

    private byte[] handleTcpRequest(byte[] request) throws InterruptedException {
        // MBAP(6) + slave + PDU
        byte[] pdu = new byte[request.length - 6];
        System.arraycopy(request, 6, pdu, 0, pdu.length);
        byte[] responsePdu = getBytes(pdu);
        if (responsePdu == null) {
            // 概率响应不成功
            return null;
        }

        byte[] response = new byte[7 + responsePdu.length];
        System.arraycopy(request, 0, response, 0, 4); // TID, PID
        int newLength = 1 + responsePdu.length;
        response[4] = (byte) ((newLength >> 8) & 0xFF);
        response[5] = (byte) (newLength & 0xFF);
        response[6] = request[6]; // Unit ID
        System.arraycopy(responsePdu, 0, response, 7, responsePdu.length);
        return response;
    }

    private byte[] handleRtuRequest(byte[] request) throws InterruptedException {
        // 不符合长度要求
        if (request.length < 4) {
            return null;
        }
        // CRC 校验
        int receivedCrc   = ((request[request.length - 1] & 0xFF) << 8) | (request[request.length - 2] & 0xFF);
        int calculatedCrc = ModbusProtocolUtils.calculateCrc(request, 0, request.length - 2);
        if (receivedCrc != calculatedCrc) {
            logErr("[mod4j] 模拟器收到错误的 RTU CRC");
            // return null; // Or handle it
        }
        byte[] responseInnerPdu = getBytes(request);
        if (responseInnerPdu == null) {
            return null;
        }

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
        // 限制主机位为一位（两个Hex）
        host = "%02x".formatted(Integer.parseInt(host));
        ArrayList<MockRespPair> list = mockRespMap.computeIfAbsent(host, _ -> new ArrayList<>());
        list.add(pair);
        return list;
    }

    /**
     * 为指定主机号添加虚拟延时配置
     *
     * @param host       主机号
     * @param timeoutOpt 延时配置
     */
    public void addTimeoutOpt(String host, TimeoutOpt timeoutOpt) {
        timeoutOptMap.put(host, timeoutOpt);
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
     * @param functionCode 功能码
     */
    public record MockRespPair(int registerAddr,
                               int dataSize,
                               boolean randData,
                               byte[] data,
                               int functionCode) implements Comparable<MockRespPair> {

        public MockRespPair(int registerAddr, int dataSize, boolean randData, byte[] data) {
            this(registerAddr, dataSize, randData, data, 0x03);
        }

        @Override
        public int compareTo(MockRespPair o) {
            return Integer.compare(registerAddr, o.registerAddr);
        }
    }

    /**
     * 延时配置
     *
     * @param mean        延时均值
     * @param std         延时标准差
     * @param successRate 延时成功率
     */
    public record TimeoutOpt(int mean, int std, double successRate) {
        public TimeoutOpt {
            if (mean < 0 || std < 0 || successRate < 0 || successRate > 1) {
                throw new IllegalArgumentException("延时参数配置有误：" + this);
            }
        }
    }
}
