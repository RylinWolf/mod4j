package com.wolfhouse.mod4j.device.conf;

import com.wolfhouse.mod4j.enums.DeviceType;
import lombok.Builder;

/**
 * Modbus 设备连接配置记录类
 *
 * @param type    设备类型 (RTU, TCP)
 * @param timeout 超时时间（毫秒）
 * @param config  设备具体配置信息 {@link AbstractDeviceConfig}
 * @author Rylin Wolf
 */
@Builder
public record DeviceConfig(DeviceType type, int timeout, AbstractDeviceConfig config) {
    public DeviceConfig {
        if (config == null) {
            throw new IllegalArgumentException("[mod4j-DeviceConfig] 设备配置信息不能为空");
        }
    }

    /**
     * 获取设备标识符
     *
     * @return 设备标识符字符串
     */
    public String getDeviceId() {
        return switch (type) {
            case RTU -> "RTU:" + ((SerialDeviceConfig) config).getPort();
            case TCP -> "TCP:" + ((TcpDeviceConfig) config).getIp() + ":" + ((TcpDeviceConfig) config).getPort();
            case TCP_RTU -> "TCP_RTU:" + ((TcpDeviceConfig) config).getIp() + ":" + ((TcpDeviceConfig) config).getPort();
        };
    }
}
