package com.wolfhouse.mod4j.device;

import com.wolfhouse.mod4j.device.conf.DeviceConfig;
import com.wolfhouse.mod4j.device.conf.TcpDeviceConfig;
import com.wolfhouse.mod4j.enums.CommunicationType;
import com.wolfhouse.mod4j.enums.ModDeviceType;
import com.wolfhouse.mod4j.exception.ModbusException;
import com.wolfhouse.mod4j.exception.ModbusIOException;
import com.wolfhouse.mod4j.exception.ModbusTimeoutException;
import com.wolfhouse.mod4j.utils.ModbusProtocolUtils;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class TcpModbusDeviceTest {
    private static final HexFormat HEX = HexFormat.of();

    @Test(timeout = 6000)
    public void timeoutClosesOldSocketAndNextRequestUsesNewConnection() throws Exception {
        try (var listener = newListener(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = executor.submit(() -> {
                try (var oldSocket = accept(listener)) {
                    byte[] first = readRequest(oldSocket, 12);
                    assertEquals(-1, oldSocket.getInputStream().read());
                    // 旧事务的响应即便迟到，也只能发往已关闭的连接。
                    try {
                        oldSocket.getOutputStream().write(tcpReadResponse(first, 0x11));
                    } catch (IOException ignored) {
                    }
                    try (var newSocket = accept(listener)) {
                        byte[] second = readRequest(newSocket, 12);
                        assertEquals(1, second[9]);
                        assertFalse(Arrays.equals(Arrays.copyOf(first, 2), Arrays.copyOf(second, 2)));
                        newSocket.getOutputStream().write(tcpReadResponse(second, 0x22));
                    }
                }
                return null;
            });

            var device = connect(listener, CommunicationType.TCP, 250);
            try {
                assertThrows(ModbusTimeoutException.class, () -> device.sendRequest(1, 3, 0, 1));
                assertFalse(device.isConnected());
                assertEquals(0x22, device.sendRequest(1, 3, 1, 1)[10]);
                server.get(2, TimeUnit.SECONDS);
            } finally {
                device.disconnect();
            }
        }
    }

    @Test(timeout = 6000)
    public void rejectsMismatchedMbapFieldsIndependently() throws Exception {
        assertInvalidResponse(response -> response[1] ^= 1, 7);
        assertInvalidResponse(response -> response[3] = 1, 7);
        assertInvalidResponse(response -> response[6] = 2, 7);
        assertInvalidResponse(response -> response[5] = 1, 7);
        assertInvalidResponse(response -> response[4] = 1, 7);
        assertInvalidResponse(response -> response[7] = 4, 11);
    }

    @Test(timeout = 5000)
    public void outOfRangeAddressNeverReachesTheNetwork() throws Exception {
        try (var listener = newListener()) {
            var device = connect(listener, CommunicationType.TCP, 1000);
            try (var peer = accept(listener)) {
                var failure = assertThrows(ModbusException.class, () -> device.sendRequest(1, 3, 65536, 1));
                assertTrue(failure.getMessage().contains("地址"));
                peer.setSoTimeout(250);
                assertThrows(SocketTimeoutException.class, () -> peer.getInputStream().read());
                assertTrue(device.isConnected());
            } finally {
                device.disconnect();
            }
        }
    }

    @Test(timeout = 6000)
    public void parameterizedTcpReadRejectsMalformedData() throws Exception {
        assertInvalidResponse(response -> response[5] = 2, 8);
        assertInvalidResponse(response -> response[8] = 1, 11);
        assertInvalidResponse(response -> {
            response[5] = 4;
            response[8] = 1;
        }, 10);
    }

    @Test(timeout = 6000)
    public void rawTcpWriteReturnsTheMatchedFrameWithoutValidatingTheEcho() throws Exception {
        byte[] request = HEX.parseHex("002000000006010600000009");
        byte[] response = HEX.parseHex("00200000000601060001000a");
        try (var listener = newListener(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = executor.submit(() -> {
                try (var socket = accept(listener)) {
                    assertArrayEquals(request, readRequest(socket, 12));
                    socket.getOutputStream().write(response);
                }
                return null;
            });

            var device = connect(listener, CommunicationType.TCP, 1500);
            try {
                assertArrayEquals(response, device.sendRawRequest(request));
                server.get(2, TimeUnit.SECONDS);
            } finally {
                device.disconnect();
            }
        }
    }

    @Test(timeout = 6000)
    public void writeIsNeverReplayedAfterPeerReceivesIt() throws Exception {
        assertWriteIsNotReplayed(CommunicationType.TCP, HEX.parseHex("002000000006010600000009"));
        assertWriteIsNotReplayed(CommunicationType.RTU, withCrc(HEX.parseHex("010600000009")));
    }

    private void assertWriteIsNotReplayed(CommunicationType framing, byte[] write) throws Exception {
        try (var listener = newListener(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = executor.submit(() -> {
                try (var socket = accept(listener)) {
                    assertArrayEquals(write, readRequest(socket, write.length));
                }
                listener.setSoTimeout(400);
                assertThrows(SocketTimeoutException.class, listener::accept);
                return null;
            });

            var device = connect(listener, framing, 1500);
            try {
                assertThrows(ModbusIOException.class, () -> device.sendRawRequest(write));
                assertFalse(device.isConnected());
                server.get(2, TimeUnit.SECONDS);
            } finally {
                device.disconnect();
            }
        }
    }

    @Test(timeout = 6000)
    public void standardReadIsRetriedOnceAfterEof() throws Exception {
        try (var listener = newListener(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = executor.submit(() -> {
                byte[] request;
                try (var first = accept(listener)) {
                    request = readRequest(first, 12);
                }
                try (var second = accept(listener)) {
                    byte[] replay = readRequest(second, 12);
                    assertArrayEquals(request, replay);
                    second.getOutputStream().write(tcpReadResponse(replay, 0x42));
                }
                return null;
            });

            var device = connect(listener, CommunicationType.TCP, 1500);
            try {
                assertEquals(0x42, device.sendRequest(1, 3, 0, 1)[10]);
                server.get(2, TimeUnit.SECONDS);
            } finally {
                device.disconnect();
            }
        }
    }

    @Test(timeout = 6000)
    public void rtuOverTcpWaitsForPayloadAndCrcAcrossTcpSegments() throws Exception {
        byte[] response = HEX.parseHex("01030200017984");
        try (var listener = newListener(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = executor.submit(() -> {
                try (var socket = accept(listener)) {
                    assertArrayEquals(HEX.parseHex("010300000001840a"), readRequest(socket, 8));
                    socket.getOutputStream().write(response, 0, 3);
                    Thread.sleep(120);
                    socket.getOutputStream().write(response, 3, response.length - 3);
                }
                return null;
            });

            var device = connect(listener, CommunicationType.RTU, 1500);
            try {
                assertArrayEquals(response, device.sendRequest(1, 3, 0, 1));
                server.get(2, TimeUnit.SECONDS);
            } finally {
                device.disconnect();
            }
        }
    }

    @Test(timeout = 6000)
    public void vendorRtuFrameWithValidCrcPrefixIsNotTruncated() throws Exception {
        byte[] request = withCrc(new byte[]{1, 0x41, 1});
        byte[] prefix = withCrc(new byte[]{1, 0x41});
        byte[] responseBody = Arrays.copyOf(prefix, prefix.length + 1);
        responseBody[prefix.length] = 42;
        byte[] response = withCrc(responseBody);

        try (var listener = newListener(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = executor.submit(() -> {
                try (var socket = accept(listener)) {
                    assertArrayEquals(request, readRequest(socket, request.length));
                    socket.getOutputStream().write(prefix);
                    Thread.sleep(120);
                    socket.getOutputStream().write(response, prefix.length, response.length - prefix.length);
                    assertEquals(-1, socket.getInputStream().read());
                }
                return null;
            });

            var device = connect(listener, CommunicationType.RTU, 750);
            try {
                assertArrayEquals(response, device.sendRawRequest(request));
            } finally {
                device.disconnect();
            }
            server.get(2, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 6000)
    public void truncatedRtuFrameTimesOutAndInvalidatesConnection() throws Exception {
        try (var listener = newListener(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = executor.submit(() -> {
                try (var socket = accept(listener)) {
                    readRequest(socket, 8);
                    socket.getOutputStream().write(HEX.parseHex("010302"));
                    assertEquals(-1, socket.getInputStream().read());
                }
                return null;
            });

            var device = connect(listener, CommunicationType.RTU, 250);
            try {
                assertThrows(ModbusTimeoutException.class, () -> device.sendRequest(1, 3, 0, 1));
                assertFalse(device.isConnected());
                server.get(2, TimeUnit.SECONDS);
            } finally {
                device.disconnect();
            }
        }
    }

    @Test(timeout = 6000)
    public void badRtuCrcIsRejected() throws Exception {
        try (var listener = newListener(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = executor.submit(() -> {
                try (var socket = accept(listener)) {
                    readRequest(socket, 8);
                    socket.getOutputStream().write(HEX.parseHex("01030200017985"));
                    assertEquals(-1, socket.getInputStream().read());
                }
                return null;
            });

            var device = connect(listener, CommunicationType.RTU, 1500);
            try {
                ModbusException failure = assertThrows(ModbusException.class, () -> device.sendRequest(1, 3, 0, 1));
                assertTrue(failure.getMessage().contains("CRC"));
                server.get(2, TimeUnit.SECONDS);
            } finally {
                device.disconnect();
            }
        }
    }

    @Test(timeout = 6000)
    public void tcpExceptionResponseIsReturnedAsRawFrame() throws Exception {
        try (var listener = newListener(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = executor.submit(() -> {
                try (var socket = accept(listener)) {
                    byte[] request = readRequest(socket, 12);
                    byte[] response = HEX.parseHex("000000000003018302");
                    System.arraycopy(request, 0, response, 0, 2);
                    socket.getOutputStream().write(response);
                }
                return null;
            });

            var device = connect(listener, CommunicationType.TCP, 1500);
            try {
                byte[] response = device.sendRequest(1, 3, 0, 1);
                assertArrayEquals(HEX.parseHex("00000003018302"), Arrays.copyOfRange(response, 2, response.length));
                server.get(2, TimeUnit.SECONDS);
            } finally {
                device.disconnect();
            }
        }
    }

    private void assertInvalidResponse(Consumer<byte[]> corrupt, int transmittedLength) throws Exception {
        try (var listener = newListener(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = executor.submit(() -> {
                try (var socket = accept(listener)) {
                    byte[] response = tcpReadResponse(readRequest(socket, 12), 0x42);
                    corrupt.accept(response);
                    socket.getOutputStream().write(response, 0, transmittedLength);
                    assertEquals(-1, socket.getInputStream().read());
                }
                return null;
            });

            var device = connect(listener, CommunicationType.TCP, 1500);
            try {
                ModbusException failure = assertThrows(ModbusException.class, () -> device.sendRequest(1, 3, 0, 1));
                assertTrue(failure.getMessage().contains("不匹配"));
                assertFalse(device.isConnected());
                server.get(2, TimeUnit.SECONDS);
            } finally {
                device.disconnect();
            }
        }
    }

    private static byte[] withCrc(byte[] body) {
        byte[] frame = Arrays.copyOf(body, body.length + 2);
        int crc = ModbusProtocolUtils.calculateCrc(body, 0, body.length);
        frame[body.length] = (byte) crc;
        frame[body.length + 1] = (byte) (crc >> 8);
        return frame;
    }

    private static byte[] tcpReadResponse(byte[] request, int value) {
        return new byte[]{request[0], request[1], 0, 0, 0, 5, request[6], request[7], 2, 0, (byte) value};
    }

    private static ServerSocket newListener() throws IOException {
        var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
        socket.setSoTimeout(2000);
        return socket;
    }

    private static Socket accept(ServerSocket listener) throws IOException {
        Socket socket = listener.accept();
        socket.setSoTimeout(2000);
        return socket;
    }

    private static byte[] readRequest(Socket socket, int length) throws IOException {
        byte[] request = socket.getInputStream().readNBytes(length);
        assertEquals(length, request.length);
        return request;
    }

    private static TcpModbusDevice connect(ServerSocket listener, CommunicationType framing, int timeout) {
        var device = new TcpModbusDevice();
        device.connect(new DeviceConfig(ModDeviceType.TCP, framing, timeout,
                new TcpDeviceConfig().setIp("127.0.0.1").setPort(listener.getLocalPort())));
        return device;
    }
}
