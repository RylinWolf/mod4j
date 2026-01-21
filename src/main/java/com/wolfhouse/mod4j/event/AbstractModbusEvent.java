package com.wolfhouse.mod4j.event;

import com.wolfhouse.mod4j.device.ModbusDevice;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Modbus 事件基类
 *
 * @author Rylin Wolf
 */
@Getter
public abstract class AbstractModbusEvent {
    private final String        deviceId;
    private final ModbusDevice  device;
    private final LocalDateTime timestamp;

    public AbstractModbusEvent(ModbusDevice device) {
        this.device    = device;
        this.deviceId  = device.getDeviceId();
        this.timestamp = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return String.format("[mod4j] %s - Device: %s, Time: %s",
                getClass().getSimpleName(), deviceId, timestamp);
    }
}
