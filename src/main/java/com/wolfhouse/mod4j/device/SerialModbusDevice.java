package com.wolfhouse.mod4j.device;

import com.fazecast.jSerialComm.SerialPort;
import com.wolfhouse.mod4j.exception.ModbusException;
import com.wolfhouse.mod4j.exception.ModbusIOException;
import com.wolfhouse.mod4j.exception.ModbusTimeoutException;
import com.wolfhouse.mod4j.facade.ModbusClient;
import com.wolfhouse.mod4j.utils.ModbusProtocolUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
    /**
     * 波特率
     */
    private              int          baudRate;
    /**
     * 超时时间
     */
    private              int          timeout         = DEFAULT_TIMEOUT;
    /**
     * jSerialComm 串口对象
     */
    private              SerialPort   serialPort;

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
     * 默认构造函数
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
        // 解析参数：params[0] 为端口名, params[1] 为波特率
        this.portName = (String) config.params()[0];
        this.baudRate = (int) config.params()[1];
        this.timeout  = config.timeout();
        System.out.println("[mod4j] 正在连接串口: " + portName + " 波特率: " + baudRate);

        this.serialPort = SerialPort.getCommPort(portName);
        this.serialPort.setBaudRate(baudRate);
        this.serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, this.timeout, 0);

        if (this.serialPort.openPort()) {
            this.inputStream  = this.serialPort.getInputStream();
            this.outputStream = this.serialPort.getOutputStream();
            System.out.println("[mod4j] 串口连接成功");
        } else {
            throw new ModbusIOException("[mod4j] 无法打开串口: " + portName);
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

        inputStream  = null;
        outputStream = null;
        serialPort   = null;

        if (firstException != null) {
            throw firstException;
        }
    }

    @Override
    public synchronized void refresh() throws ModbusException {
        disconnect();
        if (serialPort != null) {
            connect(new DeviceConfig(com.wolfhouse.mod4j.enums.DeviceType.RTU, new Object[]{portName, baudRate}, timeout));
        }
    }

    @Override
    public boolean isConnected() {
        if (serialPort != null) {
            return serialPort.isOpen();
        }
        return inputStream != null && outputStream != null;
    }

    @Override
    public synchronized byte[] sendRawRequest(byte[] command) throws ModbusException {
        if (!isConnected()) {
            throw new ModbusException("[mod4j] 串口未连接");
        }
        try {
            outputStream.write(command);
            outputStream.flush();

            // 串口读取通常需要更复杂的逻辑，比如根据功能码判断长度
            // 这里我们先进行阻塞读取，并增加超时检测
            return readResponse();
        } catch (IOException e) {
            throw new ModbusIOException("[mod4j] 串口通信异常: " + e.getMessage(), e);
        }
    }

    /**
     * 读取串口响应
     *
     * @return 响应报文
     * @throws IOException     IO 异常
     * @throws ModbusException Modbus 异常
     */
    private byte[] readResponse() throws IOException, ModbusException {
        // 对于 RTU，响应长度是不固定的。
        // 标准做法是先读取 3 个字节（从站 ID, 功能码, 异常码/字节数）来判断后续长度。
        // 但为了简单且健壮，可以利用串口的超时机制和可用字节数进行读取。

        long startTime = System.currentTimeMillis();
        // 预设一个足够大的缓冲区
        byte[] buffer = new byte[512];
        int    totalRead;

        // 第一次读取，会阻塞直到超时或有数据
        int read = inputStream.read(buffer, 0, buffer.length);
        if (read < 0) {
            throw new ModbusIOException("[mod4j] 串口连接已关闭");
        }
        if (read == 0) {
            throw new ModbusTimeoutException("[mod4j] 串口通信超时，未收到响应");
        }
        totalRead = read;

        // 循环读取，直到没有更多数据或总时间超过 2 倍超时时间（防止死循环）
        // 串口数据可能会分片到达
        while (System.currentTimeMillis() - startTime < timeout * 2L) {
            int available = inputStream.available();
            if (available > 0) {
                int nextRead = inputStream.read(buffer, totalRead, Math.min(available, buffer.length - totalRead));
                if (nextRead > 0) {
                    totalRead += nextRead;
                }
            } else {
                // 如果当前没有可用数据，稍微等待一下看看是否还有后续
                try {
                    TimeUnit.MILLISECONDS.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (inputStream.available() == 0) {
                    // 确实没有后续数据了，认为读取完成
                    break;
                }
            }
        }

        byte[] response = new byte[totalRead];
        System.arraycopy(buffer, 0, response, 0, totalRead);
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
        return doAsync(() -> sendRawRequest(command));
    }

    @Override
    public CompletableFuture<byte[]> sendRequestAsync(int slaveId, int funcCode, int address, int quantity) {
        return doAsync(() -> sendRequest(slaveId, funcCode, address, quantity));
    }

    private CompletableFuture<byte[]> doAsync(Supplier<byte[]> supplier) {
        Executor executor = (client != null) ? client.getOperationExecutor() : ForkJoinPool.commonPool();
        return CompletableFuture.supplyAsync(supplier, executor).exceptionally(e -> {
            System.err.println("[mod4j] 线程池执行异步任务失败! " + e.getMessage());
            throw new ModbusException(e);
        });
    }

    @Override
    public int getTimeout() {
        return timeout;
    }

    @Override
    public void setTimeout(int timeout) {
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
     * 获取 RTU 设备标识符
     *
     * @return 格式为 "RTU:PortName" 的字符串
     */
    @Override
    public String getDeviceId() {
        return "RTU:" + portName;
    }
}
