package com.wolfhouse.mod4j.event;

/**
 * Modbus 事件监听器接口
 *
 * @author Rylin Wolf
 */
@FunctionalInterface
public interface ModbusEventListener {
    /**
     * 当事件发生时触发
     *
     * @param event Modbus 事件
     */
    void onEvent(AbstractModbusEvent event);
}
