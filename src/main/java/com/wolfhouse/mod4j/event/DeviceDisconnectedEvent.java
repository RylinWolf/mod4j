package com.wolfhouse.mod4j.event;

import com.wolfhouse.mod4j.device.ModbusDevice;

/**
 * 设备断开连接事件
 *
 * @author Rylin Wolf
 */
public class DeviceDisconnectedEvent extends AbstractModbusEvent {
    public DeviceDisconnectedEvent(ModbusDevice device) {
        super(device);
    }
}
