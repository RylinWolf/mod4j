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

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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
    private volatile int timeout = DEFAULT_TIMEOUT;

    /**
     * TCP Socket
     */
    private volatile Socket socket;
    private boolean reconnectOnRequest;

    /**
     * 网络输入流
     */
    private DataInputStream inputStream;

    /**
     * 网络输出流
     */
    private OutputStream outputStream;

    /**
     * 是否开启心跳检测
     */
    private volatile boolean heartbeatEnabled;
    /**
     * 心跳策略，默认为读取 0 号寄存器
     */
    private volatile HeartbeatStrategy heartbeatStrategy;
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
        checkSupported(config);
        TcpDeviceConfig tcpConfig = (TcpDeviceConfig) config.config();
        if (config.comType() == null || config.timeout() < 0) {
            throw new ModbusException("[mod4j] TCP 报文格式或超时配置无效");
        }
        this.ip = tcpConfig.getIp();
        this.port = tcpConfig.getPort();
        this.heartbeatStrategy = tcpConfig.getHeartbeatStrategy();
        this.heartbeatEnabled = tcpConfig.isHeartbeatEnabled();
        this.timeout = config.timeout();
        this.modDeviceType = config.devType();
        this.communicationType = config.comType();

        Socket candidate = new Socket();
        try {
            candidate.connect(new InetSocketAddress(ip, port), timeout);
            candidate.setSoTimeout(timeout);
            inputStream = new DataInputStream(candidate.getInputStream());
            outputStream = candidate.getOutputStream();
            socket = candidate;
            reconnectOnRequest = false;
            System.out.printf("[mod4j] TCP 设备 【%s】 连接成功%n", getDeviceId());
        } catch (IOException | RuntimeException e) {
            try {
                candidate.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw new ModbusIOException("[mod4j] TCP 连接失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void disconnect() throws ModbusException {
        reconnectOnRequest = false;
        Socket previousSocket = socket;
        socket = null;
        inputStream = null;
        outputStream = null;
        if (previousSocket != null) {
            try {
                previousSocket.close();
            } catch (IOException e) {
                throw new ModbusIOException("[mod4j] 关闭 TCP Socket 异常: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public synchronized void refresh() throws ModbusException {
        if (communicationType == null) {
            throw new ModbusException("[mod4j] TCP 设备尚未配置");
        }
        disconnect();
        reconnectOnRequest = true;
        connect(new DeviceConfig(modDeviceType, communicationType, timeout,
                new TcpDeviceConfig(ip, port, heartbeatStrategy, heartbeatEnabled)));
    }

    @Override
    public boolean isConnected() {
        Socket current = socket;
        return current != null && current.isConnected() && !current.isClosed();
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
        if (communicationType == null) {
            throw new ModbusException("[mod4j] TCP 设备尚未配置");
        }
        int functionOffset = communicationType == CommunicationType.TCP ? 7 : 1;
        int minLength = communicationType == CommunicationType.TCP ? 8 : 4;
        if (command == null || command.length < minLength) {
            throw new ModbusException("[mod4j] 请求报文过短");
        }

        byte[] request = command.clone();
        boolean retryRead = isStandardRead(request, functionOffset);
        if (!isConnected()) {
            if (!reconnectOnRequest) {
                throw new ModbusException("[mod4j] TCP 设备未连接");
            }
            refresh();
        }

        try {
            return exchange(request);
        } catch (ModbusIOException e) {
            if (!retryRead) {
                throw e;
            }
            try {
                refresh();
                return exchange(request);
            } catch (ModbusException retryFailure) {
                retryFailure.addSuppressed(e);
                throw retryFailure;
            }
        }
    }

    private boolean isStandardRead(byte[] request, int functionOffset) {
        // 仅重发格式明确的标准读请求。写入或厂商命令的执行结果可能未知。
        int function = request[functionOffset] & 0xFF;
        if (function < 1 || function > 4) {
            return false;
        }
        if (communicationType == CommunicationType.TCP) {
            return request.length == 12
                    && request[2] == 0 && request[3] == 0 && request[4] == 0 && request[5] == 6;
        }
        return request.length == 8
                && (((request[7] & 0xFF) << 8) | (request[6] & 0xFF))
                == ModbusProtocolUtils.calculateCrc(request, 0, 6);
    }

    private byte[] exchange(byte[] command) {
        try {
            outputStream.write(command);
            outputStream.flush();
            return switch (communicationType) {
                case TCP -> readTcpResponse(command);
                case RTU -> RtuResponseReader.read(inputStream, command, timeout);
            };
        } catch (SocketTimeoutException e) {
            var failure = new ModbusTimeoutException("[mod4j] TCP 通信超时: " + e.getMessage());
            invalidateConnection(failure);
            throw failure;
        } catch (IOException e) {
            var failure = new ModbusIOException("[mod4j] TCP 通信异常: " + e.getMessage(), e);
            invalidateConnection(failure);
            throw failure;
        } catch (ModbusException e) {
            invalidateConnection(e);
            throw e;
        }
    }

    private void invalidateConnection(ModbusException failure) {
        try {
            disconnect();
        } catch (ModbusException closeFailure) {
            failure.addSuppressed(closeFailure);
        } finally {
            reconnectOnRequest = true;
        }
    }

    private byte[] readTcpResponse(byte[] command) throws IOException {
        byte[] header = new byte[7];
        inputStream.readFully(header);
        int length = ((header[4] & 0xFF) << 8) | (header[5] & 0xFF);
        if (length < 2 || length > 254 || header[2] != 0 || header[3] != 0
                || header[0] != command[0] || header[1] != command[1] || header[6] != command[6]) {
            throw new ModbusException("[mod4j] TCP 响应 MBAP 头无效或与请求不匹配");
        }

        byte[] response = Arrays.copyOf(header, 6 + length);
        inputStream.readFully(response, header.length, length - 1);
        int function = response[7] & 0xFF;
        int requestedFunction = command[7] & 0xFF;
        if (function == (requestedFunction | 0x80) && length == 3) {
            return response;
        }
        if (function != requestedFunction || (function & 0x80) != 0) {
            throw new ModbusException("[mod4j] TCP 响应功能码与请求不匹配");
        }
        return response;
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
    public synchronized void setTimeout(int timeout) {
        if (timeout < 0) {
            throw new IllegalArgumentException("超时时间不能为负数");
        }
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
    public synchronized byte[] sendRequest(int slaveId, int funcCode, int address, int quantity) throws ModbusException {
        if (communicationType == null) {
            throw new ModbusException("[mod4j] TCP 设备尚未配置");
        }
        byte[] command = switch (communicationType) {
            case TCP -> ModbusProtocolUtils.buildTcpPdu(slaveId, funcCode, address, quantity);
            case RTU -> ModbusProtocolUtils.buildRtuPdu(slaveId, funcCode, address, quantity);
        };
        return sendRawRequest(command);
    }

    @Override
    public void ping() throws ModbusException {
        HeartbeatStrategy strategy = heartbeatStrategy;
        if (strategy != null) {
            strategy.execute(this);
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
     * @return 格式为 "TCP:IP:Port-通信类型" 的字符串
     */
    @Override
    public String getDeviceId() {
        return new DeviceConfig(modDeviceType, communicationType, timeout, new TcpDeviceConfig(ip, port, heartbeatStrategy, isHeartbeatEnabled())).getDeviceId();
    }

    @Override
    public Set<ModDeviceType> supportedDeviceTypes() {
        return Set.of(ModDeviceType.TCP);
    }
}