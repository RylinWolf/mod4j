package com.wolfhouse.mod4j.device.conf;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;

/**
 * 串口设备配置类
 *
 * @author Rylin Wolf
 */
@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
public class SerialDeviceConfig extends AbstractDeviceConfig {
    /** 串口端口 */
    private String port;
    /** 波特率 */
    private int    baudRate;
    /** 数据位 */
    private int    dataBits;
    /** 停止位 */
    private int    stopBits;
    /** 校验位 */
    private int    parity;

    /**
     * 获取标准 9600 波特率的串口配置，提供默认值
     * - 数据位 8
     * - 停止位 1
     * - 校验位 0
     *
     * @param port 串口名
     * @return 串口配置对象
     */
    public static SerialDeviceConfig standard9600Serial(String port) {
        return standardSerial(port, 9600);
    }

    /**
     * 获取标准串口配置，提供默认值
     * - 数据位 8
     * - 停止位 1
     * - 校验位 0
     *
     * @param port     串口名
     * @param baudRate 波特率
     * @return 串口配置对象
     */
    public static SerialDeviceConfig standardSerial(String port, int baudRate) {
        return SerialDeviceConfig.builder()
                                 .port(port)
                                 .baudRate(baudRate)
                                 .dataBits(8)
                                 .stopBits(1)
                                 .parity(0)
                                 .build();
    }
}
