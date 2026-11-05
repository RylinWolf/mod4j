package com.wolfhouse.mod4j;

import com.wolfhouse.mod4j.device.ModbusDevice;
import com.wolfhouse.mod4j.device.conf.DeviceConfig;
import com.wolfhouse.mod4j.device.conf.TcpDeviceConfig;
import com.wolfhouse.mod4j.enums.CommunicationType;
import com.wolfhouse.mod4j.enums.ModDeviceType;
import com.wolfhouse.mod4j.event.DeviceConnectedEvent;
import com.wolfhouse.mod4j.event.DeviceDisconnectedEvent;
import com.wolfhouse.mod4j.exception.ModbusException;
import com.wolfhouse.mod4j.exception.ModbusHeartbeatException;
import com.wolfhouse.mod4j.facade.ModbusClient;
import com.wolfhouse.mod4j.utils.ModbusTcpSimulator;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ModClientTest {
    private static final String LOOPBACK = "127.0.0.1";

    @Test(timeout = 6000)
    public void batchConnectsAndDisconnectsLoopbackDevices() throws Exception {
        try (var first = newSimulator(); var second = newSimulator()) {
            first.start();
            second.start();
            var client = new ModbusClient();
            try {
                var firstConfig = config(first.getLocalPort());
                var secondConfig = config(second.getLocalPort());
                assertTrue(client.batchConnectDevices(List.of(firstConfig, secondConfig)));
                assertEquals(2, client.getConnectedDevices().size());
                assertTrue(client.isDeviceConnected(firstConfig.getDeviceId()));
                assertTrue(client.isDeviceConnected(secondConfig.getDeviceId()));

                client.batchDisconnectDevices(List.of(firstConfig.getDeviceId(), secondConfig.getDeviceId()));
                assertTrue(client.getConnectedDevices().isEmpty());
            } finally {
                client.shutdown();
            }
        }
    }

    @Test(timeout = 6000)
    public void concurrentConnectsShareOneDeviceAndOneSocket() throws Exception {
        var client = new ModbusClient();
        try (var listener = new ServerSocket(0, 0, InetAddress.getByName(LOOPBACK));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            listener.setSoTimeout(1500);
            var config = config(listener.getLocalPort());
            var ready = new CountDownLatch(16);
            var go = new CountDownLatch(1);
            var events = new LinkedBlockingQueue<DeviceConnectedEvent>();
            client.addEventListener(event -> {
                if (event instanceof DeviceConnectedEvent connected) {
                    events.add(connected);
                }
            });
            var tasks = new ArrayList<Future<ModbusDevice>>();
            for (int i = 0; i < 16; i++) {
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(go.await(2, TimeUnit.SECONDS));
                    return client.connectDevice(config);
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            go.countDown();
            try (var first = listener.accept()) {
                ModbusDevice device = tasks.getFirst().get(2, TimeUnit.SECONDS);
                for (var task : tasks) {
                    assertSame(device, task.get(2, TimeUnit.SECONDS));
                }
                assertSame(device, client.getDevice(config.getDeviceId()));
                assertNotNull(events.poll(2, TimeUnit.SECONDS));
                listener.setSoTimeout(300);
                assertThrows(SocketTimeoutException.class, listener::accept);
                assertTrue(events.isEmpty());
                client.disconnectDevice(config.getDeviceId());
                assertFalse(device.isConnected());
                assertFalse(client.isDeviceConnected(config.getDeviceId()));
                assertThrows(ModbusException.class, () -> device.sendRequest(1, 3, 0, 1));
            }
        } finally {
            client.shutdown();
        }
    }

    @Test(timeout = 6000)
    public void builderEnablesDefaultHeartbeatAndReadsHoldingRegister() throws Exception {
        try (var listener = new ServerSocket(0, 0, InetAddress.getByName(LOOPBACK))) {
            listener.setSoTimeout(3000);
            var client = new ModbusClient();
            var tcpConfig = TcpDeviceConfig.builder().ip(LOOPBACK).port(listener.getLocalPort())
                    .heartbeatEnabled(true).build();
            try {
                var device = client.connectDevice(new DeviceConfig(ModDeviceType.TCP, CommunicationType.TCP, 1000, tcpConfig));
                assertTrue(device.isHeartbeatEnabled());
                assertNotNull(device.getHeartbeatStrategy());
                client.startHeartbeat(1);
                try (var peer = listener.accept()) {
                    peer.setSoTimeout(3000);
                    byte[] request = peer.getInputStream().readNBytes(12);
                    assertEquals(12, request.length);
                    assertArrayEquals(new byte[]{0, 0, 0, 6, 1, 3, 0, 0, 0, 1},
                            Arrays.copyOfRange(request, 2, request.length));
                    peer.getOutputStream().write(new byte[]{request[0], request[1], 0, 0, 0, 5, 1, 3, 2, 0, 42});
                    client.stopHeartbeat();
                }
            } finally {
                client.shutdown();
            }
        }
    }

    @Test(timeout = 6000)
    public void heartbeatRecoversConnectionAndAsyncRequestUsesTheReplacementSocket() throws Exception {
        var client = new ModbusClient();
        var connectedEvents = new LinkedBlockingQueue<DeviceConnectedEvent>();
        var probes = new AtomicInteger();
        client.addEventListener(event -> {
            if (event instanceof DeviceConnectedEvent connected) {
                connectedEvents.add(connected);
            }
        });
        try (var listener = new ServerSocket(0, 0, InetAddress.getByName(LOOPBACK))) {
            listener.setSoTimeout(3000);
            var tcpConfig = new TcpDeviceConfig().setIp(LOOPBACK).setPort(listener.getLocalPort())
                    .setHeartbeatEnabled(true).setHeartbeatStrategy(device -> {
                        if (probes.incrementAndGet() == 1) {
                            throw new ModbusHeartbeatException("force one recovery");
                        }
                    });
            var config = new DeviceConfig(ModDeviceType.TCP, CommunicationType.TCP, 1000, tcpConfig);
            var device = client.connectDevice(config);
            try (var oldPeer = listener.accept()) {
                oldPeer.setSoTimeout(3000);
                assertNotNull(connectedEvents.poll(2, TimeUnit.SECONDS));
                client.startHeartbeat(1);
                assertEquals(-1, oldPeer.getInputStream().read());
                try (var newPeer = listener.accept()) {
                    newPeer.setSoTimeout(1500);
                    assertNotNull(connectedEvents.poll(2, TimeUnit.SECONDS));
                    assertSame(device, client.getDevice(config.getDeviceId()));
                    assertTrue(client.isDeviceConnected(config.getDeviceId()));
                    client.stopHeartbeat();

                    var result = client.sendRequestAsync(config.getDeviceId(), 1, 3, 0, 1);
                    byte[] request = newPeer.getInputStream().readNBytes(12);
                    assertEquals(12, request.length);
                    byte[] response = {request[0], request[1], 0, 0, 0, 5, 1, 3, 2, 0, 42};
                    newPeer.getOutputStream().write(response);
                    assertArrayEquals(response, result.get(2, TimeUnit.SECONDS));
                }
            }
        } finally {
            client.shutdown();
        }
    }

    @Test(timeout = 9000)
    public void slowHeartbeatDoesNotOverlapOrRecoverReplacedDevice() throws Exception {
        var client = new ModbusClient();
        var oldStarted = new CountDownLatch(1);
        var releaseOld = new CountDownLatch(1);
        var oldProbes = new AtomicInteger();
        var duplicateOldProbe = new CountDownLatch(1);
        var newProbed = new CountDownLatch(1);
        var disconnected = new CountDownLatch(1);
        try (var simulator = newSimulator()) {
            simulator.start();
            var tcpConfig = new TcpDeviceConfig().setIp(LOOPBACK).setPort(simulator.getLocalPort())
                    .setHeartbeatEnabled(true).setHeartbeatStrategy(device -> {
                        if (oldProbes.incrementAndGet() > 1) {
                            duplicateOldProbe.countDown();
                        }
                        oldStarted.countDown();
                        awaitRelease(releaseOld);
                        throw new ModbusHeartbeatException("old heartbeat failure");
                    });
            var config = new DeviceConfig(ModDeviceType.TCP, CommunicationType.TCP, 1000, tcpConfig);
            client.addEventListener(event -> {
                if (event instanceof DeviceDisconnectedEvent) {
                    disconnected.countDown();
                }
            });
            ModbusDevice old = client.connectDevice(config);
            client.startHeartbeat(1);
            assertTrue(oldStarted.await(3, TimeUnit.SECONDS));
            assertFalse(duplicateOldProbe.await(2200, TimeUnit.MILLISECONDS));

            client.disconnectDevice(config.getDeviceId());
            assertTrue(disconnected.await(1, TimeUnit.SECONDS));
            tcpConfig.setHeartbeatStrategy(device -> newProbed.countDown());
            ModbusDevice replacement = client.connectDevice(config);
            releaseOld.countDown();
            assertTrue(newProbed.await(3, TimeUnit.SECONDS));

            assertEquals(1, oldProbes.get());
            assertFalse(old.isConnected());
            assertNotSame(old, replacement);
            assertSame(replacement, client.getDevice(config.getDeviceId()));
            assertTrue(replacement.isConnected());
        } finally {
            releaseOld.countDown();
            client.shutdown();
        }
    }

    @Test(timeout = 7000)
    public void failedPersistentRecoveryIsDeduplicatedAndStopEndsBackoff() throws Exception {
        var client = new ModbusClient();
        var failedPing = new CountDownLatch(1);
        var pingCount = new AtomicInteger();
        var duplicatePing = new CountDownLatch(1);
        try (var simulator = newSimulator()) {
            simulator.start();
            var tcpConfig = new TcpDeviceConfig().setIp(LOOPBACK).setPort(simulator.getLocalPort())
                    .setHeartbeatEnabled(true).setHeartbeatStrategy(device -> {
                        if (pingCount.incrementAndGet() > 1) {
                            duplicatePing.countDown();
                        }
                        failedPing.countDown();
                        throw new ModbusHeartbeatException("offline");
                    });
            var config = new DeviceConfig(ModDeviceType.TCP, CommunicationType.TCP, 500, tcpConfig);
            assertTrue(client.batchConnectPersistentDevices(List.of(config)));
            simulator.stop();
            client.startHeartbeat(1);
            assertTrue(failedPing.await(2, TimeUnit.SECONDS));
            assertFalse(duplicatePing.await(2200, TimeUnit.MILLISECONDS));
            assertNotNull(client.getDevice(config.getDeviceId()));
            assertFalse(client.isDeviceConnected(config.getDeviceId()));
            client.stopHeartbeat();
            client.shutdown();
        } finally {
            client.shutdown();
        }
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(6, TimeUnit.SECONDS)) {
                throw new AssertionError("heartbeat release timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static ModbusTcpSimulator newSimulator() {
        return new ModbusTcpSimulator(new InetSocketAddress(LOOPBACK, 0), true);
    }

    private static DeviceConfig config(int port) {
        return new DeviceConfig(ModDeviceType.TCP, CommunicationType.TCP, 1000,
                new TcpDeviceConfig().setIp(LOOPBACK).setPort(port));
    }
}
