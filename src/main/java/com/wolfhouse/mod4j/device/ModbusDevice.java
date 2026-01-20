package com.wolfhouse.mod4j.device;

import com.wolfhouse.mod4j.exception.ModbusException;

/**
 * Modbus 设备接口，定义了连接、断开、刷新以及发送指令的标准行为
 *
 * @author Rylin Wolf
 */
public interface ModbusDevice {
    /**
     * 连接设备
     *
     * @param params 连接参数：
     *               对于 RTU: [String portName, int baudRate]
     *               对于 TCP: [String ip, int port]
     * @throws ModbusException 连接异常
     */
    void connect(Object[] params) throws ModbusException;

    /**
     * 断开设备连接
     *
     * @throws ModbusException 断开连接异常
     */
    void disconnect() throws ModbusException;

    /**
     * 刷新设备状态（通常是重连）
     *
     * @throws ModbusException 刷新异常
     */
    void refresh() throws ModbusException;

    /**
     * 检查设备是否已连接
     *
     * @return true 表示已连接，false 表示未连接
     */
    boolean isConnected();

    /**
     * 发送已经构建好的原始字节指令
     *
     * @param command 原始字节指令
     * @return 响应字节数组
     * @throws ModbusException 发送异常
     */
    byte[] sendRawRequest(byte[] command) throws ModbusException;

    /**
     * 参数化发送请求，内部根据协议类型自动构建报文并发送
     *
     * @param slaveId  从站 ID
     * @param funcCode 功能码
     * @param address  寄存器地址
     * @param quantity 寄存器数量/线圈数量
     * @return 响应字节数组
     * @throws ModbusException 发送异常
     */
    byte[] sendRequest(int slaveId, int funcCode, int address, int quantity) throws ModbusException;

    /**
     * 获取超时时间
     *
     * @return 超时时间（毫秒）
     */
    int getTimeout();

    /**
     * 设置超时时间
     *
     * @param timeout 超时时间（毫秒）
     */
    void setTimeout(int timeout);

    /**
     * 获取设备标识符（用于在管理池中唯一标识设备）
     *
     * @return 设备标识符字符串
     */
    String getDeviceId();
}