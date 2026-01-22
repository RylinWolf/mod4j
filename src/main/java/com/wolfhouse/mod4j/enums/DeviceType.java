package com.wolfhouse.mod4j.enums;

/**
 * Modbus 设备连接类型枚举
 *
 * @author Rylin Wolf
 */
public enum DeviceType {
    /**
     * 串口 RTU 模式
     */
    RTU,

    /**
     * 以太网 TCP 模式
     */
    TCP,

    /**
     * 以太网 TCP (RTU 报文) 模式
     */
    TCP_RTU
}

