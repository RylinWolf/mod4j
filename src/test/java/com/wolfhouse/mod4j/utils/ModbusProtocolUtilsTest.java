package com.wolfhouse.mod4j.utils;

import com.wolfhouse.mod4j.exception.ModbusException;
import org.junit.Test;

import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

public class ModbusProtocolUtilsTest {
    @Test
    public void requestBuildersRejectFieldsThatWouldWrap() {
        assertRejectedByBoth(1, 3, -1, 1);
        assertRejectedByBoth(1, 3, 65536, 1);
        assertRejectedByBoth(256, 3, 0, 1);
        assertRejectedByBoth(1, 259, 0, 1);
        assertRejectedByBoth(1, 6, 0, -1);
        assertRejectedByBoth(1, 6, 0, 65536);
    }

    @Test
    public void requestBuildersRejectInvalidReadQuantitiesAndAddressSpans() {
        assertRejectedByBoth(1, 1, 0, 0);
        assertRejectedByBoth(1, 2, 0, 2001);
        assertRejectedByBoth(1, 3, 0, 126);
        assertRejectedByBoth(1, 4, 65535, 2);
    }

    @Test
    public void requestBuildersPreserveValidBoundaryValues() {
        assertFields("ff03ffff0001", 255, 3, 65535, 1);
        assertFields("0103ff83007d", 1, 3, 65411, 125);
        assertFields("0102f83007d0", 1, 2, 63536, 2000);
        assertFields("0106ffffffff", 1, 6, 65535, 65535);
    }

    private void assertRejectedByBoth(int unit, int function, int address, int quantity) {
        assertThrows(ModbusException.class, () -> ModbusProtocolUtils.buildRtuPdu(unit, function, address, quantity));
        assertThrows(ModbusException.class, () -> ModbusProtocolUtils.buildTcpPdu(unit, function, address, quantity));
    }

    private void assertFields(String expected, int unit, int function, int address, int quantity) {
        byte[] fields = HexFormat.of().parseHex(expected);
        byte[] rtu = ModbusProtocolUtils.buildRtuPdu(unit, function, address, quantity);
        byte[] tcp = ModbusProtocolUtils.buildTcpPdu(unit, function, address, quantity);
        assertArrayEquals(fields, Arrays.copyOfRange(rtu, 0, 6));
        assertArrayEquals(fields, Arrays.copyOfRange(tcp, 6, 12));
    }
}
