package com.wolfhouse.mod4j.device;

import com.wolfhouse.mod4j.device.conf.DeviceConfig;
import com.wolfhouse.mod4j.device.conf.TcpDeviceConfig;
import com.wolfhouse.mod4j.enums.CommunicationType;
import com.wolfhouse.mod4j.enums.ModDeviceType;
import com.wolfhouse.mod4j.exception.ModbusException;
import com.wolfhouse.mod4j.exception.ModbusIOException;
import com.wolfhouse.mod4j.exception.ModbusTimeoutException;
import com.wolfhouse.mod4j.facade.ModbusClient;
import com.wolfhouse.mod4j.utils.ModbusProtocolUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * TCP 设备实现
 *
 * @author Rylin Wolf
 */
public class TcpModbusDevice implements ModbusDevice {
    /**
     * 默认超时时间 3000ms
     */
    private static final int DEFAULT_TIMEOUT = 3000;

    /**
     * 设备 IP 地址
     */
    private String ip;

    /**
     * 设备端口号
     */
    private int port;

    /**
     * 设备类型 (TCP 或 SERIAL)
     */
    private ModDeviceType modDeviceType;

    /** 通信类型 (RTU 或 TCP) */
    private CommunicationType communicationType;

    /**
     * 超时时间
     */
    private int timeout = DEFAULT_TIMEOUT;

    /**
     * TCP Socket
     */
    private Socket socket;

    /**
     * 网络输入流
     */
    private InputStream inputStream;

    /**
     * 网络输出流
     */
    private OutputStream outputStream;

    /**
     * 是否开启心跳检测
     */
    private boolean           heartbeatEnabled  = true;
    /**
     * 心跳策略，默认为读取 0 号寄存器
     */
    private HeartbeatStrategy heartbeatStrategy = device -> device.sendRequest(1, 3, 0, 1);
    private ModbusClient      client;

    /**
     * 默认构造函数（建议通过 ModbusClient 连接）
     */
    public TcpModbusDevice() {
    }

    @Override
    public synchronized void connect(DeviceConfig config) throws ModbusException {
        if (isConnected()) {
            System.out.println("[mod4j] 设备已连接，无需重复连接: " + getDeviceId());
            return;
        }
        // 检查是否支持该连接类型
        checkSupported(config);
        try {
            TcpDeviceConfig tcpConfig = (TcpDeviceConfig) config.config();
            this.ip                = tcpConfig.getIp();
            this.port              = tcpConfig.getPort();
            this.timeout           = config.timeout();
            this.modDeviceType     = config.devType();
            this.communicationType = config.comType();
            System.out.println("[mod4j] 正在连接 TCP 设备: " + ip + ":" + port + " 类型: " + communicationType);

            this.socket = new Socket(ip, port);
            this.socket.setSoTimeout(this.timeout);
            this.inputStream  = socket.getInputStream();
            this.outputStream = socket.getOutputStream();
            System.out.printf("[mod4j] TCP 设备 【%s】 连接成功%n", getDeviceId());
        } catch (IOException e) {
            throw new ModbusIOException("[mod4j] TCP 连接失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void disconnect() throws ModbusException {
        System.out.println("[mod4j] 断开 TCP 连接: " + getDeviceId());
        ModbusException firstException = null;
        try {
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (IOException e) {
            firstException = new ModbusIOException("[mod4j] 关闭 TCP 输入流异常: " + e.getMessage(), e);
        }

        try {
            if (outputStream != null) {
                outputStream.close();
            }
        } catch (IOException e) {
            if (firstException == null) {
                firstException = new ModbusIOException("[mod4j] 关闭 TCP 输出流异常: " + e.getMessage(), e);
            }
        }

        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            if (firstException == null) {
                firstException = new ModbusIOException("[mod4j] 关闭 TCP Socket 异常: " + e.getMessage(), e);
            }
        }

        inputStream  = null;
        outputStream = null;
        socket       = null;

        if (firstException != null) {
            throw firstException;
        }
    }

    @Override
    public synchronized void refresh() throws ModbusException {
        disconnect();
        connect(new DeviceConfig(modDeviceType, communicationType, timeout, new TcpDeviceConfig(ip, port)));
    }

    @Override
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /**
     * 发送原始字节指令
     *
     * @param command 原始字节指令
     * @return 设备响应命令
     * @throws ModbusException 失败时的异常
     */
    @Override
    public synchronized byte[] sendRawRequest(byte[] command) throws ModbusException {
        try {
            return trySendRawRequest(command);
        } catch (SocketTimeoutException e) {
            throw new ModbusTimeoutException("[mod4j] TCP 通信超时: " + e.getMessage());
        } catch (IOException | ModbusException e) {
            System.err.printf("[mod4j] TCP 通信异常: %s, 正在重试...%n", e.getMessage());
            return retryOnRefresh(() -> {
                try {
                    return this.trySendRawRequest(command);
                } catch (IOException ex) {
                    throw new ModbusIOException("[mod4j] 重试失败", ex);
                }
            });
        }
    }

    /**
     * 发送原始字节指令
     *
     * @param command 原始字节指令
     * @return 设备响应命令
     * @throws ModbusException 失败时的异常
     * @throws IOException     IO 异常
     */
    private byte[] trySendRawRequest(byte[] command) throws ModbusException, IOException {
        if (!isConnected()) {
            throw new ModbusException("[mod4j] 设备未连接");
        }
        outputStream.write(command);
        outputStream.flush();

        // 根据模式读取并构造完整响应
        return switch (communicationType) {
            case TCP -> readResponse();
            case RTU -> readRtuResponse();
        };
    }


    /**
     * 刷新连接并重试
     *
     * @param supplier 要重试的操作
     * @param <T>      返回类型
     * @return 要重试的操作的返回值
     */
    private <T> T retryOnRefresh(Supplier<T> supplier) {
        try {
            refresh();
        } catch (ModbusException ignored) {
        }
        return supplier.get();
    }

    /**
     * 读取 Modbus TCP 响应
     *
     * @return 完整响应报文
     * @throws IOException     IO 异常
     * @throws ModbusException 协议异常
     */
    private byte[] readResponse() throws IOException, ModbusException {
        // 读取响应头。Modbus TCP 响应头有 7 字节 (MBAP 头)
        // Transaction ID (2), Protocol ID (2), Length (2), Unit ID (1)
        byte[] header = readBytes(7);

        // 第 5-6 字节是后续长度 (包含 Unit ID 和 PDU)
        int length = ((header[4] & 0xFF) << 8) | (header[5] & 0xFF);
        // length 至少应为 1 (Unit ID)
        if (length <= 1) {
            return header;
        }

        // 读取剩余部分 (PDU)
        // 已经读取了 Unit ID (header[6]), 所以还需要读取 length - 1 字节
        byte[] pdu = readBytes(length - 1);

        byte[] fullResponse = new byte[7 + pdu.length];
        System.arraycopy(header, 0, fullResponse, 0, 7);
        System.arraycopy(pdu, 0, fullResponse, 7, pdu.length);
        return fullResponse;
    }

    /**
     * 读取 Modbus RTU (over TCP) 响应
     *
     * @return 完整响应报文
     * @throws IOException IO 异常
     */
    private byte[] readRtuResponse() throws IOException {
        byte[] buffer    = new byte[1024];
        long   startTime = System.currentTimeMillis();
        int    totalRead = 0;

        // 阻塞读取第一个字节
        int firstByte = inputStream.read();
        if (firstByte == -1) {
            throw new ModbusIOException("[mod4j] 连接已关闭，读取数据失败");
        }
        buffer[totalRead++] = (byte) firstByte;

        // 循环读取，直到没有更多数据或总时间超过 2 倍超时时间
        return readBuffer(inputStream, timeout, startTime, buffer, totalRead);
    }

    /**
     * 阻塞读取指定数量的字节
     *
     * @param n 要读取的字节数
     * @return 读取到的字节数组
     * @throws IOException     IO 异常
     * @throws ModbusException 连接关闭异常
     */
    private byte[] readBytes(int n) throws IOException, ModbusException {
        byte[] data      = new byte[n];
        int    totalRead = 0;
        while (totalRead < n) {
            int read = inputStream.read(data, totalRead, n - totalRead);
            if (read == -1) {
                throw new ModbusIOException("[mod4j] 连接已关闭，读取数据失败");
            }
            totalRead += read;
        }
        return data;
    }

    /**
     * 设置关联的客户端（用于获取线程池）
     */
    @Override
    public void setClient(ModbusClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<byte[]> sendRawRequestAsync(byte[] command) {
        return doAsync(() -> sendRawRequest(command), client);
    }

    @Override
    public CompletableFuture<byte[]> sendRequestAsync(int slaveId, int funcCode, int address, int quantity) {
        return doAsync(() -> sendRequest(slaveId, funcCode, address, quantity), client);
    }

    @Override
    public int getTimeout() {
        return timeout;
    }

    @Override
    public void setTimeout(int timeout) {
        this.timeout = timeout;
        if (socket != null && !socket.isClosed()) {
            try {
                socket.setSoTimeout(timeout);
            } catch (IOException e) {
                System.err.println("[mod4j] 设置 Socket 超时失败: " + e.getMessage());
            }
        }
    }

    @Override
    public byte[] sendRequest(int slaveId, int funcCode, int address, int quantity) throws ModbusException {
        byte[] command = switch (communicationType) {
            case TCP -> ModbusProtocolUtils.buildTcpPdu(slaveId, funcCode, address, quantity);
            case RTU -> ModbusProtocolUtils.buildRtuPdu(slaveId, funcCode, address, quantity);
        };
        return sendRawRequest(command);
    }

    @Override
    public void ping() throws ModbusException {
        if (heartbeatStrategy != null) {
            heartbeatStrategy.execute(this);
        } else {
            // 回退到默认逻辑
            sendRequest(1, 3, 0, 1);
        }
    }

    @Override
    public HeartbeatStrategy getHeartbeatStrategy() {
        return heartbeatStrategy;
    }

    @Override
    public void setHeartbeatStrategy(HeartbeatStrategy strategy) {
        this.heartbeatStrategy = strategy;
    }

    @Override
    public boolean isHeartbeatEnabled() {
        return heartbeatEnabled;
    }

    @Override
    public void setHeartbeatEnabled(boolean enabled) {
        this.heartbeatEnabled = enabled;
    }

    /**
     * 获取 TCP 设备标识符
     *
     * @return 格式为 "TCP:IP:Port" 的字符串
     */
    @Override
    public String getDeviceId() {
        return new DeviceConfig(modDeviceType, communicationType, timeout, new TcpDeviceConfig(ip, port)).getDeviceId();
    }

    @Override
    public Set<ModDeviceType> supportedDeviceTypes() {
        return Set.of(ModDeviceType.TCP);
    }
}