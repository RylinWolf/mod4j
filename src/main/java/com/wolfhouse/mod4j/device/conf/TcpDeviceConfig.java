package com.wolfhouse.mod4j.device.conf;

/**
 * TCP 设备配置类
 *
 * @author Rylin Wolf
 */
public class TcpDeviceConfig extends AbstractDeviceConfig {
    /** IP 地址 */
    private String ip;
    /** 端口号 */
    private int    port;

    public TcpDeviceConfig(String ip, int port) {
        this.ip   = ip;
        this.port = port;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }
}
