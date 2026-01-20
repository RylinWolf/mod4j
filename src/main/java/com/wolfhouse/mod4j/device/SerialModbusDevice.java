package com.wolfhouse.mod4j.device;

import com.fazecast.jSerialComm.SerialPort;
import com.wolfhouse.mod4j.exception.ModbusException;
import com.wolfhouse.mod4j.exception.ModbusIOException;
import com.wolfhouse.mod4j.exception.ModbusTimeoutException;
import com.wolfhouse.mod4j.utils.ModbusProtocolUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 串口设备实现
 *
 * @author Rylin Wolf
 */
public class SerialModbusDevice implements ModbusDevice {
    /**
     * 默认超时时间 2000ms
     */
    private static final int DEFAULT_TIMEOUT = 2000;

    /**
     * 串口名称
     */
    private String portName;

    /**
     * 波特率
     */
    private int baudRate;

    /**
     * 超时时间
     */
    private int timeout = DEFAULT_TIMEOUT;

    /**
     * jSerialComm 串口对象
     */
    private SerialPort serialPort;

    /**
     * 串口输入流
     */
    private InputStream inputStream;

    /**
     * 串口输出流
     */
    private OutputStream outputStream;

    @Override
    public synchronized void connect(Object[] params) throws ModbusException {
        // 解析参数：params[0] 为端口名, params[1] 为波特率
        this.portName = (String) params[0];
        this.baudRate = (int) params[1];
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
        try {
            if (inputStream != null) {
                inputStream.close();
            }
            if (outputStream != null) {
                outputStream.close();
            }
            if (serialPort != null) {
                serialPort.closePort();
            }
        } catch (IOException e) {
            throw new ModbusIOException("[mod4j] 断开串口连接异常: " + e.getMessage(), e);
        } finally {
            inputStream  = null;
            outputStream = null;
            serialPort   = null;
        }
    }

    @Override
    public synchronized void refresh() throws ModbusException {
        disconnect();
        connect(new Object[]{portName, baudRate});
    }

    @Override
    public boolean isConnected() {
        return serialPort != null && serialPort.isOpen();
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
        // 对于 RTU，响应长度是不固定的，通常取决于请求的功能码
        // 在没有完整协议解析器的情况下，我们使用一个缓冲区并利用超时机制
        byte[] buffer    = new byte[256];
        int    readCount = inputStream.read(buffer);

        if (readCount < 0) {
            throw new ModbusIOException("[mod4j] 串口连接已关闭");
        }
        if (readCount == 0) {
            throw new ModbusTimeoutException("[mod4j] 串口通信超时，未收到响应");
        }

        byte[] response = new byte[readCount];
        System.arraycopy(buffer, 0, response, 0, readCount);
        return response;
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