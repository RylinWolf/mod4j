package com.wolfhouse.mod4j.device;

import com.fazecast.jSerialComm.SerialPort;
import com.wolfhouse.mod4j.device.conf.DeviceConfig;
import com.wolfhouse.mod4j.device.conf.SerialDeviceConfig;
import com.wolfhouse.mod4j.enums.CommunicationType;
import com.wolfhouse.mod4j.enums.ModDeviceType;
import com.wolfhouse.mod4j.exception.ModbusException;
import com.wolfhouse.mod4j.exception.ModbusIOException;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HexFormat;

import static org.junit.Assert.*;

public class SerialModbusDeviceTest {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    public void refreshReopensWithOriginalSerialSettingsAndCurrentTimeout() {
        var device = new TestSerialDevice();
        var config = new SerialDeviceConfig("test-port", 19200, 7, SerialPort.TWO_STOP_BITS, SerialPort.EVEN_PARITY);
        device.connect(new DeviceConfig(ModDeviceType.SERIAL, CommunicationType.RTU, 900, config));
        var originalConfig = device.openedConfig;
        device.setTimeout(600);
        config.setBaudRate(9600);

        device.refresh();
        try {
            assertTrue(device.isConnected());
            assertEquals(2, device.opens);
            assertEquals(originalConfig, device.openedConfig);
            assertEquals(19200, device.openedConfig.getBaudRate());
            assertEquals(7, device.openedConfig.getDataBits());
            assertEquals(SerialPort.TWO_STOP_BITS, device.openedConfig.getStopBits());
            assertEquals(SerialPort.EVEN_PARITY, device.openedConfig.getParity());
            assertEquals(600, device.openTimeout);
        } finally {
            device.disconnect();
        }
    }

    @Test
    public void failedRefreshPropagatesErrorAndCanBeRetried() {
        var device = new TestSerialDevice();
        device.connect(new DeviceConfig(ModDeviceType.SERIAL, CommunicationType.RTU, 900,
                SerialDeviceConfig.standard9600Serial("test-port")));
        device.failOpen = true;
        assertThrows(ModbusIOException.class, device::refresh);
        assertFalse(device.isConnected());
        device.failOpen = false;
        device.refresh();
        assertTrue(device.isConnected());
        device.disconnect();
    }

    @Test
    public void streamBackedRefreshFailsWithoutClosingTheInjectedStreams() {
        var device = new SerialModbusDevice(InputStream.nullInputStream(), OutputStream.nullOutputStream());
        try {
            assertThrows(ModbusException.class, device::refresh);
            assertTrue(device.isConnected());
        } finally {
            device.disconnect();
        }
    }

    @Test
    public void readsRtuExceptionFrameAndReturnsRawResponse() {
        byte[] frame = HEX.parseHex("018302c0f1");
        var requests = new ByteArrayOutputStream();
        var device = new SerialModbusDevice(new ByteArrayInputStream(frame), requests);
        try {
            assertArrayEquals(frame, device.sendRequest(1, 3, 0, 1));
            assertArrayEquals(HEX.parseHex("010300000001840a"), requests.toByteArray());
        } finally {
            device.disconnect();
        }
    }

    private static final class TestSerialDevice extends SerialModbusDevice {
        private int opens;
        private SerialDeviceConfig openedConfig;
        private int openTimeout;
        private boolean failOpen;

        @Override
        void openPort(SerialDeviceConfig config, int timeout) {
            if (failOpen) {
                throw new ModbusIOException("test open failure");
            }
            opens++;
            openedConfig = config;
            openTimeout = timeout;
            inputStream = InputStream.nullInputStream();
            outputStream = OutputStream.nullOutputStream();
        }
    }
}
