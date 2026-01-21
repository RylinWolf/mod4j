package com.wolfhouse.mod4j.exception;

/**
 * Modbus SDK 基础异常
 *
 * @author Rylin Wolf
 */
public class ModbusException extends RuntimeException {
    /**
     * 构造函数
     *
     * @param message 异常详细信息
     */
    public ModbusException(String message) {
        super(message);
    }

    /**
     * 构造函数
     *
     * @param message 异常详细信息
     * @param cause   异常原因
     */
    public ModbusException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 构造函数
     *
     * @param cause 异常原因
     */
    public ModbusException(Throwable cause) {
        super(cause);
    }
}
