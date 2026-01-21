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
}
