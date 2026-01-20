package com.wolfhouse.mod4j.exception;

/**
 * Modbus IO 异常
 *
 * @author Rylin Wolf
 */
public class ModbusIOException extends ModbusException {
    public ModbusIOException(String message) {
        super(message);
    }

    public ModbusIOException(String message, Throwable cause) {
        super(message, cause);
    }
}
