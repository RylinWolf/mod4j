package com.wolfhouse.mod4j.facade;

import com.wolfhouse.mod4j.device.DeviceConfig;
import com.wolfhouse.mod4j.device.ModbusDevice;
import com.wolfhouse.mod4j.device.SerialModbusDevice;
import com.wolfhouse.mod4j.enums.DeviceType;
import com.wolfhouse.mod4j.exception.ModbusException;
import com.wolfhouse.mod4j.utils.ModbusRtuSimulator;
import com.wolfhouse.mod4j.utils.ModbusTcpSimulator;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Arrays;

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
        if (client != null) {
            client.shutdown();
        }
    }

    @Test
    public void testConnectAndRequest() throws ModbusException {
        // 使用模拟器进行真实连接测试
        DeviceConfig config = new DeviceConfig(DeviceType.TCP, new Object[]{"127.0.0.1", testPort}, 2000);
        ModbusDevice device = client.connectDevice(config);
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
    public void testBatchOperations() throws ModbusException {
        DeviceConfig config1 = new DeviceConfig(DeviceType.TCP, new Object[]{"127.0.0.1", testPort}, 2000);
        DeviceConfig config3 = new DeviceConfig(DeviceType.TCP, new Object[]{"localhost", testPort}, 2000);

        client.batchConnectDevices(Arrays.asList(config1, config3));
        Assert.assertEquals(2, client.getConnectedDevices().size());

        client.batchDisconnectDevices(Arrays.asList(config1.getDeviceId(), config3.getDeviceId()));
        Assert.assertEquals(0, client.getConnectedDevices().size());
    }

    @Test
    public void testPersistentDevice() throws ModbusException, InterruptedException, IOException {
        DeviceConfig config   = new DeviceConfig(DeviceType.TCP, new Object[]{"127.0.0.1", testPort}, 1000);
        ModbusDevice device   = client.connectDevice(config);
        String       deviceId = device.getDeviceId();

        client.markAsPersistent(deviceId);
        client.startHeartbeat(1);

        // 关闭模拟器
        simulator.stop();

        // 等待心跳检测并重试
        Thread.sleep(2000);

        // 此时设备应该还在池中，因为它是常连接，正在无限重试
        Assert.assertNotNull(client.getDevice(deviceId));

        // 重新启动模拟器
        simulator = new ModbusTcpSimulator(testPort);
        simulator.start();

        // 等待重连成功
        Thread.sleep(7000);

        Assert.assertTrue(client.getDevice(deviceId).isConnected());

        client.unmarkAsPersistent(deviceId);
        simulator.stop();

        // 等待心跳移除
        Thread.sleep(3000);
        Assert.assertNull(client.getDevice(deviceId));
    }

    @Test
    public void testAsyncRequest() throws Exception {
        DeviceConfig config = new DeviceConfig(DeviceType.TCP, new Object[]{"127.0.0.1", testPort}, 2000);
        ModbusDevice device = client.connectDevice(config);

        // 异步发送请求
        var future = device.sendRequestAsync(1, 3, 0, 1);

        // 验证非阻塞
        Assert.assertFalse("请求应该是异步的，不应立即完成", future.isDone());

        // 阻塞等待结果
        byte[] response = future.get(5, java.util.concurrent.TimeUnit.SECONDS);

        Assert.assertNotNull(response);
        Assert.assertEquals(11, response.length);
        Assert.assertEquals(3, response[7]);

        client.disconnectDevice(device.getDeviceId());
    }

    @Test
    public void testHeartbeat() throws ModbusException, InterruptedException {
        DeviceConfig config = new DeviceConfig(DeviceType.TCP, new Object[]{"127.0.0.1", testPort}, 1000);
        ModbusDevice device = client.connectDevice(config);
        Assert.assertTrue(device.isConnected());

        // 验证默认开启心跳
        Assert.assertTrue(device.isHeartbeatEnabled());

        // 关闭心跳检测
        device.setHeartbeatEnabled(false);

        // 启动心跳检测，间隔 1 秒
        client.startHeartbeat(1);

        // 关闭模拟器
        simulator.stop();

        // 等待一段时间，如果心跳检测还在运行且检测了该设备，它应该被移除。
        // 但因为我们关闭了该设备的心跳检测，它应该还在池中（虽然物理连接已断）
        Thread.sleep(3000);
        Assert.assertNotNull(client.getDevice(device.getDeviceId()));

        // 开启心跳检测
        device.setHeartbeatEnabled(true);
        // 再等待心跳检测
        Thread.sleep(3000);
        Assert.assertNull(client.getDevice(device.getDeviceId()));

        client.stopHeartbeat();
    }

    @Test
    public void testCustomHeartbeat() throws ModbusException, InterruptedException {
        DeviceConfig config = new DeviceConfig(DeviceType.TCP, new Object[]{"127.0.0.1", testPort}, 1000);
        ModbusDevice device = client.connectDevice(config);

        // 设置自定义心跳策略：读取 10 号寄存器
        java.util.concurrent.atomic.AtomicInteger pingCount = new java.util.concurrent.atomic.AtomicInteger(0);
        device.setHeartbeatStrategy(d -> {
            pingCount.incrementAndGet();
            d.sendRequest(1, 3, 10, 1);
        });

        client.startHeartbeat(1);
        // 等待至少两次心跳
        Thread.sleep(2500);

        Assert.assertTrue("自定义心跳执行次数应大于 0", pingCount.get() > 0);
        client.stopHeartbeat();
    }

    @Test
    public void testRtuSimulator() throws ModbusException, IOException {
        PipedOutputStream clientOut = new PipedOutputStream();
        PipedInputStream  simIn     = new PipedInputStream(clientOut);
        PipedOutputStream simOut    = new PipedOutputStream();
        PipedInputStream  clientIn  = new PipedInputStream(simOut);

        ModbusRtuSimulator rtuSimulator = new ModbusRtuSimulator(simIn, simOut);
        rtuSimulator.start();

        try {
            // 注入流到 SerialModbusDevice
            SerialModbusDevice rtuDevice = new SerialModbusDevice(clientIn, clientOut);

            // 发送请求 (SlaveID=1, Func=3, Addr=0, Qty=1)
            byte[] response = rtuDevice.sendRequest(1, 3, 0, 1);

            Assert.assertNotNull(response);
            // SlaveID(1) + Func(1) + ByteCount(1) + Data(2) + CRC(2) = 7
            Assert.assertEquals(7, response.length);
            Assert.assertEquals(1, response[0]); // Slave ID
            Assert.assertEquals(3, response[1]); // Function Code
            Assert.assertEquals(2, response[2]); // Data Length
        } finally {
            rtuSimulator.stop();
        }
    }
}
