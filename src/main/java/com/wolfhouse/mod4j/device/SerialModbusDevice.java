package com.wolfhouse.mod4j.device;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortInvalidPortException;
import com.fazecast.jSerialComm.SerialPortTimeoutException;
import com.wolfhouse.mod4j.device.conf.DeviceConfig;
import com.wolfhouse.mod4j.device.conf.SerialDeviceConfig;
import com.wolfhouse.mod4j.enums.CommunicationType;
import com.wolfhouse.mod4j.enums.ModDeviceType;
import com.wolfhouse.mod4j.exception.ModbusException;
import com.wolfhouse.mod4j.exception.ModbusIOException;
import com.wolfhouse.mod4j.exception.ModbusTimeoutException;
import com.wolfhouse.mod4j.facade.ModbusClient;
import com.wolfhouse.mod4j.utils.ModbusProtocolUtils;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 串口设备实现
 *
 * @author Rylin Wolf
 */
public class SerialModbusDevice implements ModbusDevice {
    /**
     * 默认超时时间 2000ms
     */
    private static final int          DEFAULT_TIMEOUT = 2000;
    /**
     * 串口输入流
     */
    protected            InputStream  inputStream;
    /**
     * 串口输出流
     */
    protected            OutputStream outputStream;
    /**
     * 串口名称
     */
    private              String       portName;
    private SerialDeviceConfig portConfig;
    /**
     * 超时时间
     */
    private volatile     int          timeout         = DEFAULT_TIMEOUT;
    /**
     * jSerialComm 串口对象
     */
    private SerialPort serialPort;
    private final Object connectionLock = new Object();

    /**
     * 是否开启心跳检测
     */
    private volatile boolean heartbeatEnabled = true;
    /**
     * 心跳策略，默认为读取 0 号寄存器
     */
    private volatile HeartbeatStrategy heartbeatStrategy = device -> device.sendRequest(1, 3, 0, 1);
    private ModbusClient      client;

    /**
     * 默认构造函数（建议通过 ModbusClient 连接）
     */
    public SerialModbusDevice() {
    }

    /**
     * 用于测试的构造函数，允许注入流
     *
     * @param inputStream  输入流
     * @param outputStream 输出流
     */
    public SerialModbusDevice(InputStream inputStream, OutputStream outputStream) {
        this.inputStream  = inputStream;
        this.outputStream = outputStream;
        this.portName     = "MockPort";
    }

    @Override
    public void connect(DeviceConfig config) throws ModbusException {
        synchronized (connectionLock) {
            if (isConnected()) {
                return;
            }
            checkSupported(config);
            if (config.comType() != CommunicationType.RTU || config.timeout() < 0) {
                throw new ModbusException("[mod4j] 串口设备需要 RTU 报文格式和非负超时");
            }
            SerialDeviceConfig serialConfig = (SerialDeviceConfig) config.config();
            portConfig = new SerialDeviceConfig(serialConfig.getPort(), serialConfig.getBaudRate(),
                    serialConfig.getDataBits(), serialConfig.getStopBits(), serialConfig.getParity());
            portName = portConfig.getPort();
            timeout = config.timeout();
            openPort(portConfig);
        }
    }

    void openPort(SerialDeviceConfig config) {
        SerialPort candidate;
        try {
            candidate = SerialPort.getCommPort(config.getPort());
        } catch (SerialPortInvalidPortException e) {
            throw new ModbusIOException("[mod4j] 串口不可用: " + config.getPort(), e);
        }

        candidate.setComPortParameters(config.getBaudRate(), config.getDataBits(),
                config.getStopBits(), config.getParity());
        candidate.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
        if (!candidate.openPort()) {
            throw new ModbusIOException("[mod4j] 无法打开串口: " + config.getPort());
        }
        try {
            inputStream = new SerialInput(candidate);
            outputStream = candidate.getOutputStream();
            serialPort = candidate;
        } catch (RuntimeException e) {
            candidate.closePort();
            clearStatements();
            throw new ModbusIOException("[mod4j] 初始化串口流失败: " + config.getPort(), e);
        }
    }

    @Override
    public void disconnect() throws ModbusException {
        synchronized (connectionLock) {
            SerialPort port = serialPort;
            InputStream input = inputStream;
            OutputStream output = outputStream;
            clearStatements();

            ModbusException failure = null;
            if (port != null) {
                synchronized (port) {
                    if (port.isOpen() && !port.closePort()) {
                        failure = new ModbusIOException("[mod4j] 无法关闭串口: " + portName);
                    }
                    port.notifyAll();
                }
            }
            try (input; output) {
                // 串口先关闭，流随连接一起释放。
            } catch (IOException e) {
                if (failure == null) {
                    failure = new ModbusIOException("[mod4j] 关闭串口流失败: " + portName, e);
                } else {
                    failure.addSuppressed(e);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private void clearStatements() {
        inputStream = null;
        outputStream = null;
        serialPort = null;
    }

    @Override
    public void refresh() throws ModbusException {
        synchronized (connectionLock) {
            if (portConfig == null) {
                throw new ModbusException("[mod4j] 串口设备尚无可用于重连的端口配置");
            }
            disconnect();
            openPort(portConfig);
        }
    }

    @Override
    public boolean isConnected() {
        synchronized (connectionLock) {
            return serialPort != null ? serialPort.isOpen() : inputStream != null && outputStream != null;
        }
    }

    @Override
    public synchronized byte[] sendRawRequest(byte[] command) throws ModbusException {
        if (command == null || command.length < 4) {
            throw new ModbusException("[mod4j] RTU 请求报文过短");
        }

        InputStream input;
        OutputStream output;
        SerialPort port;
        synchronized (connectionLock) {
            if (!isConnected()) {
                throw new ModbusException("[mod4j] 串口未连接");
            }
            input = inputStream;
            output = outputStream;
            port = serialPort;
        }
        try {
            synchronized (port == null ? this : port) {
                output.write(command);
                output.flush();
            }
            return RtuResponseReader.read(new DataInputStream(input), command, timeout);
        } catch (SerialPortTimeoutException e) {
            throw new ModbusTimeoutException("[mod4j] 串口通信超时: " + e.getMessage());
        } catch (EOFException e) {
            throw new ModbusIOException("[mod4j] 串口已断开或 RTU 响应不完整", e);
        } catch (IOException e) {
            throw new ModbusIOException("[mod4j] 串口通信异常: " + e.getMessage(), e);
        }
    }

    private final class SerialInput extends InputStream {
        private static final long POLL_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
        private final SerialPort port;
        private final byte[] singleByte = new byte[1];
        private boolean closed;

        private SerialInput(SerialPort port) {
            this.port = port;
        }

        @Override
        public void close() {
            synchronized (port) {
                closed = true;
                port.notifyAll();
            }
        }

        @Override
        public int read() throws IOException {
            return read(singleByte, 0, 1) < 0 ? -1 : singleByte[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            int readTimeout = timeout;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeout);
            synchronized (port) {
                // 原生读取保持非阻塞。等待期间释放锁，允许断开关闭端口并唤醒读取。
                while (!closed && port.isOpen()) {
                    int count = port.readBytes(buffer, length, offset);
                    if (count != 0) {
                        return count;
                    }
                    long remaining = readTimeout == 0 ? POLL_INTERVAL_NANOS : deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new SerialPortTimeoutException("等待串口数据超时");
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(port, Math.min(remaining, POLL_INTERVAL_NANOS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("串口读取被中断");
                    }
                }
                return -1;
            }
        }
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
        if (timeout < 0) {
            throw new IllegalArgumentException("超时时间不能为负数");
        }
        synchronized (connectionLock) {
            this.timeout = timeout;
        }
    }

    @Override
    public byte[] sendRequest(int slaveId, int funcCode, int address, int quantity) throws ModbusException {
        byte[] command = ModbusProtocolUtils.buildRtuPdu(slaveId, funcCode, address, quantity);
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
     * 获取 RTU 设备标识符
     *
     * @return 格式为 "RTU:PortName-RTU" 的字符串
     */
    @Override
    public String getDeviceId() {
        return "RTU:%s-RTU".formatted(portName);
    }

    @Override
    public Set<ModDeviceType> supportedDeviceTypes() {
        return Set.of(ModDeviceType.SERIAL);
    }
}
