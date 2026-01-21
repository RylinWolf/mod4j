package com.wolfhouse.mod4j.device;

import com.wolfhouse.mod4j.enums.DeviceType;

/**
 * Modbus 设备连接配置记录类
 *
 * @param type    设备类型 (RTU, TCP)
 * @param params  连接参数：
 *                对于 RTU: [String portName, int baudRate]
 *                对于 TCP: [String ip, int port]
 * @param timeout 超时时间（毫秒）
 * @author Rylin Wolf
 */
public record DeviceConfig(DeviceType type, int timeout, Object... params) {
    /**
     * 获取设备标识符
     *
     * @return 设备标识符字符串
     */
    public String getDeviceId() {
        return switch (type) {
            case RTU -> "RTU:" + params[0];
            case TCP -> "TCP:" + params[0] + ":" + params[1];
        };
    }
}
