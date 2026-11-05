package com.wolfhouse.mod4j.facade;

import com.wolfhouse.mod4j.device.conf.DeviceConfig;
import com.wolfhouse.mod4j.device.conf.TcpDeviceConfig;
import com.wolfhouse.mod4j.enums.CommunicationType;
import com.wolfhouse.mod4j.enums.ModDeviceType;
import com.wolfhouse.mod4j.utils.ModbusProtocolUtils;
import com.wolfhouse.mod4j.utils.ModbusTcpSimulator;
import com.wolfhouse.mod4j.utils.ModbusTcpSimulator.MockRespPair;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
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

    @Test(timeout = 5000)
    public void rtuSimulatorWaitsForFullRequestAndDiscardsBadCrc() throws Exception {
        try (var simulator = new ModbusTcpSimulator(new InetSocketAddress(LOOPBACK, 0), false)) {
            simulator.addMockResp("1", new MockRespPair(0, 4, false, new byte[]{0, 42, 0, 43}));
            simulator.start();
            try (var socket = new Socket(LOOPBACK, simulator.getLocalPort())) {
                var out = socket.getOutputStream();
                var in = socket.getInputStream();
                byte[] request = ModbusProtocolUtils.buildRtuPdu(1, 3, 0, 1);
                out.write(request, 0, 4);
                socket.setSoTimeout(250);
                assertThrows(SocketTimeoutException.class, in::read);

                byte[] badCrc = ModbusProtocolUtils.buildRtuPdu(1, 3, 1, 1);
                badCrc[7] ^= 1;
                byte[] next = ModbusProtocolUtils.buildRtuPdu(1, 3, 1, 1);
                byte[] tail = new byte[4 + badCrc.length + next.length];
                System.arraycopy(request, 4, tail, 0, 4);
                System.arraycopy(badCrc, 0, tail, 4, badCrc.length);
                System.arraycopy(next, 0, tail, 4 + badCrc.length, next.length);
                out.write(tail);

                socket.setSoTimeout(1500);
                assertArrayEquals(HEX.parseHex("010302002a399b"), in.readNBytes(7));
                assertArrayEquals(HEX.parseHex("010302002bf85b"), in.readNBytes(7));
                socket.setSoTimeout(250);
                assertThrows(SocketTimeoutException.class, in::read);
            }
        }
    }

    @Test(timeout = 5000)
    public void timeoutAndMockResponsesUseTheSameDecimalUnitId() throws Exception {
        try (var simulator = simulator()) {
            var pair = new MockRespPair(0, 2, false, new byte[]{0, 42});
            simulator.addMockResp("10", pair);
            simulator.addMockResp("16", pair);
            simulator.addTimeoutOpt("10", new ModbusTcpSimulator.TimeoutOpt(0, 0, 0));
            simulator.start();
            try (var socket = new Socket(LOOPBACK, simulator.getLocalPort())) {
                var out = socket.getOutputStream();
                var in = socket.getInputStream();
                socket.setSoTimeout(250);
                out.write(HEX.parseHex("0001000000060a0300000001"));
                assertThrows(SocketTimeoutException.class, in::read);
                socket.setSoTimeout(1500);
                out.write(HEX.parseHex("000200000006100300000001"));
                assertArrayEquals(HEX.parseHex("000200000005100302002a"), in.readNBytes(11));
            }
        }
    }

    @Test(timeout = 5000)
    public void packsCoilsAndDiscreteInputsAsBitsFromTheRequestedAddress() throws Exception {
        try (var simulator = simulator()) {
            byte[] bits = {(byte) 0x82, 0x03};
            simulator.addMockResp("1", new MockRespPair(0, 2, false, bits, 0x01));
            simulator.addMockResp("1", new MockRespPair(0, 2, false, bits, 0x02));
            simulator.start();
            var client = new ModbusClient();
            try {
                var tcpConfig = new TcpDeviceConfig().setIp(LOOPBACK).setPort(simulator.getLocalPort());
                var device = client.connectDevice(new DeviceConfig(ModDeviceType.TCP, CommunicationType.TCP, 1000, tcpConfig));
                byte[] coils = device.sendRequest(1, 0x01, 1, 9);
                assertArrayEquals(HEX.parseHex("0102c101"), Arrays.copyOfRange(coils, 7, coils.length));
                byte[] inputs = device.sendRequest(1, 0x02, 9, 1);
                assertArrayEquals(HEX.parseHex("020101"), Arrays.copyOfRange(inputs, 7, inputs.length));
            } finally {
                client.shutdown();
            }
        }
    }

    private static ModbusTcpSimulator simulator() {
        return new ModbusTcpSimulator(new InetSocketAddress(LOOPBACK, 0), true);
    }
}
