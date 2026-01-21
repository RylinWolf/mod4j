package com.wolfhouse.mod4j.event;

import com.wolfhouse.mod4j.device.ModbusDevice;

/**
 * 设备连接成功事件
 *
 * @author Rylin Wolf
 */
public class DeviceConnectedEvent extends AbstractModbusEvent {
    public DeviceConnectedEvent(ModbusDevice device) {
        super(device);
    }
}
