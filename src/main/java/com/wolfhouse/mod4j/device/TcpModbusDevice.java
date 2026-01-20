package com.wolfhouse.mod4j.device;

import com.wolfhouse.mod4j.exception.ModbusException;
import com.wolfhouse.mod4j.exception.ModbusIOException;
import com.wolfhouse.mod4j.exception.ModbusTimeoutException;
import com.wolfhouse.mod4j.utils.ModbusProtocolUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;

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
    private boolean heartbeatEnabled = true;

    @Override
    public synchronized void connect(DeviceConfig config) throws ModbusException {
        try {
            this.ip      = (String) config.params()[0];
            this.port    = (int) config.params()[1];
            this.timeout = config.timeout();
            System.out.println("[mod4j] 正在连接 TCP 设备: " + ip + ":" + port);

            this.socket = new Socket(ip, port);
            this.socket.setSoTimeout(this.timeout);
            this.inputStream  = socket.getInputStream();
            this.outputStream = socket.getOutputStream();
            System.out.println("[mod4j] TCP 设备连接成功");
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
        connect(new DeviceConfig(com.wolfhouse.mod4j.enums.DeviceType.TCP, new Object[]{ip, port}, timeout));
    }

    @Override
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    @Override
    public synchronized byte[] sendRawRequest(byte[] command) throws ModbusException {
        if (!isConnected()) {
            throw new ModbusException("[mod4j] 设备未连接");
        }
        try {
            outputStream.write(command);
            outputStream.flush();

            // 读取并构造完整响应
            return readResponse();
        } catch (SocketTimeoutException e) {
            throw new ModbusTimeoutException("[mod4j] TCP 通信超时: " + e.getMessage());
        } catch (IOException e) {
            throw new ModbusIOException("[mod4j] TCP 通信异常: " + e.getMessage(), e);
        }
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
        byte[] header = readNBytes(7);

        // 第 5-6 字节是后续长度 (包含 Unit ID 和 PDU)
        int length = ((header[4] & 0xFF) << 8) | (header[5] & 0xFF);
        // length 至少应为 1 (Unit ID)
        if (length <= 1) {
            return header;
        }

        // 读取剩余部分 (PDU)
        // 已经读取了 Unit ID (header[6]), 所以还需要读取 length - 1 字节
        byte[] pdu = readNBytes(length - 1);

        byte[] fullResponse = new byte[7 + pdu.length];
        System.arraycopy(header, 0, fullResponse, 0, 7);
        System.arraycopy(pdu, 0, fullResponse, 7, pdu.length);
        return fullResponse;
    }

    /**
     * 阻塞读取指定数量的字节
     *
     * @param n 要读取的字节数
     * @return 读取到的字节数组
     * @throws IOException     IO 异常
     * @throws ModbusException 连接关闭异常
     */
    private byte[] readNBytes(int n) throws IOException, ModbusException {
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
        byte[] command = ModbusProtocolUtils.buildTcpPdu(slaveId, funcCode, address, quantity);
        return sendRawRequest(command);
    }

    @Override
    public void ping() throws ModbusException {
        // 使用 0x03 功能码读取地址 0 的 1 个寄存器作为心跳
        // slaveId 默认为 1
        sendRequest(1, 3, 0, 1);
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
        return "TCP:" + ip + ":" + port;
    }
}