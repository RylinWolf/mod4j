package com.wolfhouse.mod4j.device.conf;

/**
 * 串口设备配置类
 *
 * @author Rylin Wolf
 */
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

    public SerialDeviceConfig(String port, int baudRate, int dataBits, int stopBits, int parity) {
        this.port     = port;
        this.baudRate = baudRate;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.parity   = parity;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public int getBaudRate() {
        return baudRate;
    }

    public void setBaudRate(int baudRate) {
        this.baudRate = baudRate;
    }

    public int getDataBits() {
        return dataBits;
    }

    public void setDataBits(int dataBits) {
        this.dataBits = dataBits;
    }

    public int getStopBits() {
        return stopBits;
    }

    public void setStopBits(int stopBits) {
        this.stopBits = stopBits;
    }

    public int getParity() {
        return parity;
    }

    public void setParity(int parity) {
        this.parity = parity;
    }
}
