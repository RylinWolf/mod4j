package com.wolfhouse.mod4j.device.conf;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;

/**
 * TCP 设备配置类
 *
 * @author Rylin Wolf
 */
@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
public class TcpDeviceConfig extends AbstractDeviceConfig {
    /** IP 地址 */
    private String ip;
    /** 端口号 */
    private int    port;
}
