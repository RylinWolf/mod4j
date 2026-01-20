package com.wolfhouse.mod4j.facade;

import com.wolfhouse.mod4j.device.ModbusDevice;
import com.wolfhouse.mod4j.device.SerialModbusDevice;
import com.wolfhouse.mod4j.device.TcpModbusDevice;
import com.wolfhouse.mod4j.enums.DeviceType;
import com.wolfhouse.mod4j.exception.ModbusException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modbus SDK 门面类，用于管理已连接设备并提供统一的通信入口
 *
 * @author Rylin Wolf
 */
public class ModbusClient {
    /**
     * 已连接设备池，使用 ConcurrentHashMap 保证线程安全
     */
    private final Map<String, ModbusDevice> connectedDevices = new ConcurrentHashMap<>();

    /**
     * 连接设备，支持自定义超时时间
     *
     * @param type    设备类型枚举 {@link DeviceType}
     * @param params  连接参数：
     *                对于 RTU: [String portName, int baudRate]
     *                对于 TCP: [String ip, int port]
     * @param timeout 超时时间（毫秒）
     * @return 连接成功的设备对象 {@link ModbusDevice}
     * @throws ModbusException 如果连接失败或不支持设备类型
     */
    public ModbusDevice connectDevice(DeviceType type, Object[] params, int timeout) throws ModbusException {
        ModbusDevice device;
        if (type == DeviceType.RTU) {
            device = new SerialModbusDevice();
        } else if (type == DeviceType.TCP) {
            device = new TcpModbusDevice();
        } else {
            throw new ModbusException("[mod4j] 不支持的设备类型");
        }

        device.setTimeout(timeout);
        device.connect(params);
        String deviceId = device.getDeviceId();
        connectedDevices.put(deviceId, device);
        return device;
    }

    /**
     * 连接设备（使用默认超时时间）
     *
     * @param type   设备类型枚举 {@link DeviceType}
     * @param params 连接参数
     * @return 连接成功的设备对象 {@link ModbusDevice}
     * @throws ModbusException 如果连接失败
     */
    public ModbusDevice connectDevice(DeviceType type, Object[] params) throws ModbusException {
        // TCP 默认 3000ms, RTU 默认 2000ms, 这里统一传 3000ms 或让实现类自行决定（如果传 -1）
        // 既然我们增加了参数，最好提供一个默认值。
        return connectDevice(type, params, 3000);
    }

    /**
     * 获取已连接的设备
     *
     * @param deviceId 设备 ID (例如 "TCP:127.0.0.1:502" 或 "RTU:COM1")
     * @return 设备对象，如果未找到则返回 null
     */
    public ModbusDevice getDevice(String deviceId) {
        return connectedDevices.get(deviceId);
    }

    /**
     * 断开指定设备的连接并从管理池中移除
     *
     * @param deviceId 要断开连接的设备 ID
     * @throws ModbusException 如果断开过程中发生错误
     */
    public void disconnectDevice(String deviceId) throws ModbusException {
        ModbusDevice device = connectedDevices.remove(deviceId);
        if (device != null) {
            device.disconnect();
        }
    }

    /**
     * 发送原始字节请求到指定设备
     *
     * @param deviceId 设备 ID
     * @param command  原始 Modbus 报文
     * @return 响应字节数组
     * @throws ModbusException 如果设备未连接或通信失败
     */
    public byte[] sendRawRequest(String deviceId, byte[] command) throws ModbusException {
        ModbusDevice device = connectedDevices.get(deviceId);
        if (device == null) {
            throw new ModbusException("[mod4j] 设备未连接或不存在: " + deviceId);
        }
        return device.sendRawRequest(command);
    }

    /**
     * 发送参数化请求到指定设备
     *
     * @param deviceId 设备 ID
     * @param slaveId  从站 ID
     * @param funcCode 功能码
     * @param address  寄存器地址
     * @param quantity 寄存器数量
     * @return 响应字节数组
     * @throws ModbusException 如果设备未连接或通信失败
     */
    public byte[] sendRequest(String deviceId, int slaveId, int funcCode, int address, int quantity) throws ModbusException {
        ModbusDevice device = connectedDevices.get(deviceId);
        if (device == null) {
            throw new ModbusException("[mod4j] 设备未连接或不存在: " + deviceId);
        }
        return device.sendRequest(slaveId, funcCode, address, quantity);
    }

    /**
     * 设置指定设备的超时时间
     *
     * @param deviceId 设备 ID
     * @param timeout  超时时间（毫秒）
     * @throws ModbusException 如果设备不存在
     */
    public void setTimeout(String deviceId, int timeout) throws ModbusException {
        ModbusDevice device = connectedDevices.get(deviceId);
        if (device == null) {
            throw new ModbusException("[mod4j] 设备未连接或不存在: " + deviceId);
        }
        device.setTimeout(timeout);
    }

    /**
     * 获取所有当前已连接的设备 ID 及其对应对象的映射副本
     *
     * @return 设备 ID 到设备对象的映射
     */
    public Map<String, ModbusDevice> getConnectedDevices() {
        return new ConcurrentHashMap<>(connectedDevices);
    }
}