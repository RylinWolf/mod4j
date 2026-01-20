package com.wolfhouse.mod4j.exception;

/**
 * Modbus 通信超时异常
 *
 * @author Rylin Wolf
 */
public class ModbusTimeoutException extends ModbusException {
    public ModbusTimeoutException(String message) {
        super(message);
    }
}
