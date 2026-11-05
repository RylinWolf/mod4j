package com.wolfhouse.mod4j.facade;

import com.wolfhouse.mod4j.utils.ModbusTcpSimulator;
import com.wolfhouse.mod4j.utils.ModbusTcpSimulator.MockRespPair;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HexFormat;

import static org.junit.Assert.*;

public class TcpSimulatorTest {
    private static final String LOOPBACK = "127.0.0.1";
    private static final HexFormat HEX = HexFormat.of();

    @Test(timeout = 5000)
    public void simulatorServesSplitAndCoalescedRequestsWithoutExternalDevices() throws Exception {
        try (var simulator = simulator()) {
            simulator.addMockResp("1", new MockRespPair(0, 2, false, new byte[]{0, 42}));
            simulator.start();
            try (var socket = new Socket(LOOPBACK, simulator.getLocalPort())) {
                socket.setSoTimeout(1500);
                byte[] request = HEX.parseHex("000100000006010300000001");
                byte[] second = HEX.parseHex("000200000006010300000001");
                var out = socket.getOutputStream();
                out.write(request, 0, 4);
                Thread.sleep(50);
                // 一次写入前一帧尾部与下一帧。
                byte[] tail = new byte[request.length - 4 + second.length];
                System.arraycopy(request, 4, tail, 0, request.length - 4);
                System.arraycopy(second, 0, tail, request.length - 4, second.length);
                out.write(tail);

                assertArrayEquals(HEX.parseHex("000100000005010302002a000200000005010302002a"),
                        socket.getInputStream().readNBytes(22));
            }
        }
    }

    @Test(timeout = 5000)
    public void stopClosesIdleClientsAndAllowsRestart() throws Exception {
        try (var simulator = simulator()) {
            simulator.addMockResp("1", new MockRespPair(0, 2, false, new byte[]{0, 42}));
            simulator.start();
            try (var socket = new Socket(LOOPBACK, simulator.getLocalPort())) {
                socket.setSoTimeout(1500);
                socket.getOutputStream().write(HEX.parseHex("000100000006010300000001"));
                assertEquals(11, socket.getInputStream().readNBytes(11).length);
                simulator.stop();
                assertEquals(-1, socket.getInputStream().read());
                assertEquals(-1, simulator.getLocalPort());
            }
            simulator.start();
            assertTrue(simulator.getLocalPort() > 0);
            try (var socket = new Socket(LOOPBACK, simulator.getLocalPort())) {
                socket.setSoTimeout(1500);
                socket.getOutputStream().write(HEX.parseHex("000200000006010300000001"));
                assertArrayEquals(HEX.parseHex("000200000005010302002a"), socket.getInputStream().readNBytes(11));
            }
        }
    }

    private static ModbusTcpSimulator simulator() {
        return new ModbusTcpSimulator(new InetSocketAddress(LOOPBACK, 0), true);
    }
}
