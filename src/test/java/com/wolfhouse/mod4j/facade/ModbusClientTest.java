package com.wolfhouse.mod4j.facade;

import com.wolfhouse.mod4j.device.ModbusDevice;
import com.wolfhouse.mod4j.enums.DeviceType;
import com.wolfhouse.mod4j.exception.ModbusException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * ModbusClient 测试类
 *
 * @author Rylin Wolf
 */
public class ModbusClientTest {
    ModbusClient client;

    @Before
    public void setUp() {
        client = new ModbusClient();
    }

    @Test
    public void testConnectAndGet() throws ModbusException {
        // 由于没有真实设备，这里仅演示流程。实际运行会抛出异常或超时
        try {
            ModbusDevice device = client.connectDevice(DeviceType.TCP, new Object[]{"127.0.0.1", 502}, 1000);
            Assert.assertNotNull(device);
            Assert.assertEquals(device, client.getDevice(device.getDeviceId()));
            Assert.assertEquals(1000, device.getTimeout());
        } catch (ModbusException e) {
            System.out.println("[mod4j] 测试连接预期失败 (无真实设备): " + e.getMessage());
        }
    }
}
