package com.wolfhouse.mod4j.utils;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

public class ModbusRtuSimulatorTest {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    public void singleRegisterWriteEchoesTheEntireRequestWithoutDuplicatingUnitId() throws Exception {
        String request = "11060001000a5a9d";
        assertArrayEquals(HEX.parseHex(request), exchange(HEX.parseHex(request), 8));
    }

    @Test
    public void assemblesSplitFramesAndHandlesBackToBackRequestsAfterBadCrc() throws Exception {
        byte[] input = HEX.parseHex("010300000001840b" + "010300000001840a" + "11060001000a5a9d");
        byte[] responses = exchange(input, 4);
        assertArrayEquals(HEX.parseHex("01030200017984" + "11060001000a5a9d"), responses);
    }

    @Test
    public void readsVariableLengthFileRecordAndNextFrameFromTheSameStream() throws Exception {
        String fileRecord = "01150906000100020001123412f5";
        String writeRegister = "11060001000a5a9d";
        assertArrayEquals(HEX.parseHex(fileRecord + writeRegister), exchange(HEX.parseHex(fileRecord + writeRegister), 3));
    }

    @Test
    public void returnsTheRequestedRegisterAndCoilCounts() throws Exception {
        byte[] registers = ModbusProtocolUtils.buildRtuPdu(1, 3, 0, 2);
        assertArrayEquals(HEX.parseHex("010304000100016a33"), exchange(registers, 4));

        byte[] coils = ModbusProtocolUtils.buildRtuPdu(1, 1, 0, 9);
        assertArrayEquals(HEX.parseHex("010102ff0139cc"), exchange(coils, 4));
    }

    @Test
    public void invalidReadQuantityReturnsAnExceptionFrame() throws Exception {
        // 组帧器拒绝零数量，因此这里使用预先计算好的原始报文。
        byte[] request = HEX.parseHex("01030000000045ca");
        assertArrayEquals(HEX.parseHex("0183030131"), exchange(request, 4));
    }

    private byte[] exchange(byte[] requests, int chunkSize) throws Exception {
        var inputDrained = new CountDownLatch(1);
        var input = new FilterInputStream(new ByteArrayInputStream(requests)) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = super.read(bytes, offset, Math.min(length, chunkSize));
                if (count == -1) {
                    inputDrained.countDown();
                }
                return count;
            }
        };
        var output = new ByteArrayOutputStream();
        var simulator = new ModbusRtuSimulator(input, output);
        simulator.start();
        try {
            assertTrue(inputDrained.await(2, TimeUnit.SECONDS));
            return output.toByteArray();
        } finally {
            simulator.stop();
        }
    }
}
