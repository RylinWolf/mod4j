package com.wolfhouse.mod4j.device;

import com.wolfhouse.mod4j.exception.ModbusException;

/**
 * 心跳检测策略接口
 *
 * @author Rylin Wolf
 */
@FunctionalInterface
public interface HeartbeatStrategy {
    /**
     * 执行心跳检测逻辑
     *
     * @param device 当前设备对象
     * @throws ModbusException 如果检测失败
     */
    void execute(ModbusDevice device) throws ModbusException;
}
