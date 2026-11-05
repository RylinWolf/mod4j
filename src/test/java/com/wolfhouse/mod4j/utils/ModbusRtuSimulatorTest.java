package com.wolfhouse.mod4j.utils;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;

public class ModbusRtuSimulatorTest {
    @Test
    public void singleRegisterWriteEchoesTheEntireRequestWithoutDuplicatingUnitId() throws Exception {
        byte[] request = HexFormat.of().parseHex("11060001000a5a9d");
        var received = new CompletableFuture<byte[]>();
        var output = new ByteArrayOutputStream() {
            @Override
            public void flush() {
                received.complete(toByteArray());
            }
        };

        var simulator = new ModbusRtuSimulator(new ByteArrayInputStream(request), output);
        simulator.start();
        try {
            assertArrayEquals(request, received.get(2, TimeUnit.SECONDS));
        } finally {
            simulator.stop();
        }
    }
}
