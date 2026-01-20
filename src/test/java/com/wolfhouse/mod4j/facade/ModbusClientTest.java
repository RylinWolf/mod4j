package com.wolfhouse.mod4j.facade;

import com.wolfhouse.mod4j.device.ModbusDevice;
import com.wolfhouse.mod4j.enums.DeviceType;
import com.wolfhouse.mod4j.exception.ModbusException;
import com.wolfhouse.mod4j.utils.ModbusTcpSimulator;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

/**
 * ModbusClient 测试类
 *
 * @author Rylin Wolf
 */
public class ModbusClientTest {
    ModbusClient       client;
    ModbusTcpSimulator simulator;
    int                testPort = 5502;

    @Before
    public void setUp() throws IOException {
        client    = new ModbusClient();
        simulator = new ModbusTcpSimulator(testPort);
        simulator.start();
    }

    @After
    public void tearDown() {
        if (simulator != null) {
            simulator.stop();
        }
    }

    @Test
    public void testConnectAndRequest() throws ModbusException {
        // 使用模拟器进行真实连接测试
        ModbusDevice device = client.connectDevice(DeviceType.TCP, new Object[]{"127.0.0.1", testPort}, 2000);
        Assert.assertNotNull(device);
        Assert.assertTrue(device.isConnected());

        // 发送请求 (读取保持寄存器 03)
        byte[] response = device.sendRequest(1, 3, 0, 1);
        Assert.assertNotNull(response);
        // MBAP(7) + PDU(4: 03 02 00 01) = 11
        Assert.assertEquals(11, response.length);
        // 验证功能码
        Assert.assertEquals(3, response[7]);
        // 验证返回的数据字节数
        Assert.assertEquals(2, response[8]);
        // 验证数据
        Assert.assertEquals(0, response[9]);
        Assert.assertEquals(1, response[10]);

        client.disconnectDevice(device.getDeviceId());
        Assert.assertFalse(device.isConnected());
    }

    @Test
    public void testConnectAndGet() throws ModbusException {
        ModbusDevice device = client.connectDevice(DeviceType.TCP, new Object[]{"127.0.0.1", testPort}, 1000);
        Assert.assertNotNull(device);
        Assert.assertEquals(device, client.getDevice(device.getDeviceId()));
        Assert.assertEquals(1000, device.getTimeout());
        client.disconnectDevice(device.getDeviceId());
    }
}
