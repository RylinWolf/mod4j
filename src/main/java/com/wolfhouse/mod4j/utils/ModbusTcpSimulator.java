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
    private final Map<Integer, ArrayList<MockRespPair>> mockRespMap      = new ConcurrentHashMap<>();
    /** 虚拟响应延时配置映射, 主机号 - 延时配置封装 */
    private final Map<Integer, TimeoutOpt>              timeoutOptMap    = new ConcurrentHashMap<>();
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
        int unitId = pdu[0] & 0xFF;
        byte functionCode = pdu[1];
        if (pdu.length < 6) {
            return exceptionResponse(functionCode, 0x03);
        }
        int address = ((pdu[2] & 0xFF) << 8) | (pdu[3] & 0xFF);
        int quantity = ((pdu[4] & 0xFF) << 8) | (pdu[5] & 0xFF);
        return getMockResp(unitId, address, functionCode, quantity);
    }

    private byte[] getMockResp(int unitId, int registerAddrValue, byte functionCode, int quantity) throws InterruptedException {
        TimeoutOpt timeoutOpt = timeoutOptMap.getOrDefault(unitId, globalTimeoutOpt);
        if (!RandomUtils.boolWithProbability(timeoutOpt.successRate)) {
            return null;
        }
        // 通过主机位(从机地址)确定要返回的虚拟数据列表
        ArrayList<MockRespPair> pairs = mockRespMap.get(unitId);
        if (pairs == null || pairs.isEmpty()) {
            return exceptionResponse(functionCode, 0x02);
        }

        // 过滤出功能码匹配的响应
        List<MockRespPair> matchedPairs = pairs.stream()
                                               .filter(p -> p.functionCode() == (functionCode & 0xFF))
                                               .sorted()
                                               .toList();

        if (matchedPairs.isEmpty()) {
            return exceptionResponse(functionCode, 0x01);
        }

        boolean bitRead = functionCode == 0x01 || functionCode == 0x02;
        int maxQuantity = ModbusProtocolUtils.maxReadQuantity(functionCode & 0xFF);
        if (maxQuantity == 0) {
            maxQuantity = 125;
        }
        if (quantity < 1 || quantity > maxQuantity) {
            return exceptionResponse(functionCode, 0x03);
        }

        TimeUnit.MILLISECONDS.sleep((long) RandomUtils.getGaussian(timeoutOpt.mean, timeoutOpt.std));
        if (bitRead) {
            return bitResponse(functionCode, registerAddrValue, quantity, matchedPairs);
        }

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
        return response;
    }

    private byte[] bitResponse(byte functionCode, int address, int quantity, List<MockRespPair> pairs) {
        int byteCount = (quantity + 7) / 8;
        byte[] response = new byte[2 + byteCount];
        response[0] = functionCode;
        response[1] = (byte) byteCount;
        for (MockRespPair pair : pairs) {
            byte[] data = pair.data();
            int byteLength = pair.randData() ? pair.dataSize()
                    : data == null ? 0 : Math.min(pair.dataSize(), data.length);
            for (int bit = 0; bit < quantity; bit++) {
                long sourceBit = (long) address + bit - pair.registerAddr();
                if (sourceBit < 0 || sourceBit >= 8L * byteLength) {
                    continue;
                }
                boolean set = pair.randData() ? ThreadLocalRandom.current().nextBoolean()
                        : (data[(int) (sourceBit / 8)] & (1 << (sourceBit % 8))) != 0;
                int index = 2 + bit / 8;
                int mask = 1 << (bit % 8);
                response[index] = (byte) (set ? response[index] | mask : response[index] & ~mask);
            }
        }
        return response;
    }

    private byte[] exceptionResponse(byte functionCode, int exceptionCode) {
        return new byte[]{(byte) (functionCode | 0x80), (byte) exceptionCode};
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
            while (!listener.isClosed()) {
                byte[] request = isTcpStrategy ? readTcpRequest(is) : RtuSimulatorFrames.readRequest(is);
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
        if (!RtuSimulatorFrames.hasValidCrc(request)) {
            logErr("[mod4j] 模拟器收到错误的 RTU CRC");
            return null;
        }
        byte[] pdu = getBytes(Arrays.copyOf(request, request.length - 2));
        return pdu == null ? null : RtuSimulatorFrames.response(request[0], pdu);
    }

    /**
     * 为指定主机号添加虚拟响应结果
     *
     * @param host 十进制从站地址（0 至 255）
     * @param pair 虚拟响应结果
     */
    public List<MockRespPair> addMockResp(String host, MockRespPair pair) {
        ArrayList<MockRespPair> list = mockRespMap.computeIfAbsent(parseUnitId(host), _ -> new ArrayList<>());
        list.add(pair);
        return list;
    }

    /**
     * 为指定主机号添加虚拟延时配置
     *
     * @param host       十进制从站地址（0 至 255）
     * @param timeoutOpt 延时配置
     */
    public void addTimeoutOpt(String host, TimeoutOpt timeoutOpt) {
        timeoutOptMap.put(parseUnitId(host), timeoutOpt);
    }

    private int parseUnitId(String host) {
        int unitId = Integer.parseInt(host);
        if (unitId < 0 || unitId > 255) {
            throw new IllegalArgumentException("从站地址必须在 0 至 255 之间");
        }
        return unitId;
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
     * @param registerAddr 起始寄存器、线圈或离散输入地址
     * @param dataSize     数据长度（字节数）
     * @param randData     是否随机数据
     * @param data         数据，位数据从最低有效位开始打包
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
