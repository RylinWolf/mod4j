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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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
    private volatile     SerialPort   serialPort;

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
    public synchronized void connect(DeviceConfig config) throws ModbusException {
        if (isConnected()) {
            System.out.println("[mod4j] 设备已连接，无需重复连接: " + getDeviceId());
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
        openPort(portConfig, timeout);
    }

    void openPort(SerialDeviceConfig config, int timeout) {
        SerialPort candidate;
        try {
            candidate = SerialPort.getCommPort(config.getPort());
        } catch (SerialPortInvalidPortException e) {
            throw new ModbusIOException("[mod4j] 串口不可用: " + config.getPort(), e);
        }

        candidate.setComPortParameters(config.getBaudRate(), config.getDataBits(),
                config.getStopBits(), config.getParity());
        candidate.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, timeout, 0);
        if (!candidate.openPort()) {
            throw new ModbusIOException("[mod4j] 无法打开串口: " + config.getPort());
        }
        try {
            inputStream = candidate.getInputStream();
            outputStream = candidate.getOutputStream();
            serialPort = candidate;
        } catch (RuntimeException e) {
            candidate.closePort();
            clearStatements();
            throw new ModbusIOException("[mod4j] 初始化串口流失败: " + config.getPort(), e);
        }
    }

    @Override
    public synchronized void disconnect() throws ModbusException {
        System.out.println("[mod4j] 断开串口连接: " + getDeviceId());
        ModbusException firstException = null;

        try {
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (IOException e) {
            firstException = new ModbusIOException("[mod4j] 关闭串口输入流异常: " + e.getMessage(), e);
        }

        try {
            if (outputStream != null) {
                outputStream.close();
            }
        } catch (IOException e) {
            if (firstException == null) {
                firstException = new ModbusIOException("[mod4j] 关闭串口输出流异常: " + e.getMessage(), e);
            }
        }

        if (serialPort != null) {
            serialPort.closePort();
        }
        // 清理串口状态
        clearStatements();

        if (firstException != null) {
            throw firstException;
        }
    }

    /**
     * 清理串口相关状态，注意应在关闭连接后再使用
     */
    private void clearStatements() {
        inputStream  = null;
        outputStream = null;
        serialPort   = null;
    }

    @Override
    public synchronized void refresh() throws ModbusException {
        if (portConfig == null) {
            throw new ModbusException("[mod4j] 串口设备尚无可用于重连的端口配置");
        }
        disconnect();
        openPort(portConfig, timeout);
    }

    @Override
    public boolean isConnected() {
        SerialPort current = serialPort;
        if (current != null) {
            return current.isOpen();
        }
        return inputStream != null && outputStream != null;
    }

    @Override
    public synchronized byte[] sendRawRequest(byte[] command) throws ModbusException {
        if (!isConnected()) {
            throw new ModbusException("[mod4j] 串口未连接");
        }
        if (command == null || command.length < 4) {
            throw new ModbusException("[mod4j] RTU 请求报文过短");
        }
        try {
            outputStream.write(command);
            outputStream.flush();
            return RtuResponseReader.read(new DataInputStream(inputStream), command, timeout);
        } catch (SerialPortTimeoutException e) {
            throw new ModbusTimeoutException("[mod4j] 串口通信超时: " + e.getMessage());
        } catch (IOException e) {
            throw new ModbusIOException("[mod4j] 串口通信异常: " + e.getMessage(), e);
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
    public synchronized void setTimeout(int timeout) {
        if (timeout < 0) {
            throw new IllegalArgumentException("超时时间不能为负数");
        }
        this.timeout = timeout;
        if (serialPort != null && serialPort.isOpen()) {
            serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, timeout, 0);
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
