package com.wolfhouse.mod4j.exception;

/**
 * 心跳监测失败异常
 *
 * @author Rylin Wolf
 */
public class ModbusHeartbeatException extends ModbusException {
    public ModbusHeartbeatException(String message) {
        super(message);
    }
}
