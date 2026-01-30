package com.wolfhouse.mod4j.facade;

import com.wolfhouse.mod4j.utils.ModbusTcpSimulator;
import com.wolfhouse.mod4j.utils.ModbusTcpSimulator.MockRespPair;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * @author Rylin Wolf
 */
public class TcpSimulatorTest {
    List<ModbusTcpSimulator> simulators = new ArrayList<>();

    @After
    public void tearDown() {
        simulators.forEach(ModbusTcpSimulator::stop);
    }

    @Test
    public void start() {
        // 5502
        simulators.add(start5502());
        // 5503
        simulators.add(start5503());
        join();
    }

    ModbusTcpSimulator start5502() {
        return startPort(5502, "1", List.of(
                new MockRespPair(0x0000, 8, false, new byte[]{0x01, (byte) 0x82, 0, 0, 0x02, (byte) 0x43, 0, 0}),
                new MockRespPair(10003, 4, true, null)));
    }

    ModbusTcpSimulator start5503() {
        return startPort(5503, "1", List.of(
                new MockRespPair(10030, 2, false, new byte[]{0x00, 0x01}),
                new MockRespPair(10031, 2, false, new byte[]{0x00, 0x01}),
                new MockRespPair(10051, 400, true, null)));
    }

    ModbusTcpSimulator startPort(int port, String slaveAddr, List<ModbusTcpSimulator.MockRespPair> pairs) {
        ModbusTcpSimulator simulator = new ModbusTcpSimulator(port);
        simulator.addMockResp(slaveAddr, pairs.getFirst())
                 .addAll(pairs.subList(1, pairs.size()));
        try {
            simulator.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return simulator;
    }

    void join() {
        Scanner scanner = new Scanner(System.in);
        while (!scanner.nextLine().equals("y")) {
        }
    }
}
