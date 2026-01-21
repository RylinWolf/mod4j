package com.wolfhouse.mod4j.facade;

import com.wolfhouse.mod4j.device.DeviceConfig;
import com.wolfhouse.mod4j.device.ModbusDevice;
import com.wolfhouse.mod4j.device.SerialModbusDevice;
import com.wolfhouse.mod4j.device.TcpModbusDevice;
import com.wolfhouse.mod4j.enums.DeviceType;
import com.wolfhouse.mod4j.exception.ModbusException;

import java.util.*;
import java.util.concurrent.*;

/**
 * Modbus SDK 门面类，用于管理已连接设备并提供统一的通信入口
 *
 * @author Rylin Wolf
 */
public class ModbusClient {
    /**
     * 已连接设备池，使用 ConcurrentHashMap 保证线程安全
     */
    private final Map<String, ModbusDevice> connectedDevices = new ConcurrentHashMap<>();

    /**
     * 常连接设备 ID 集合
     */
    private final Set<String>              persistentDevices = ConcurrentHashMap.newKeySet();
    /**
     * 业务操作执行线程池（用于批量连接/断开）
     */
    private final ExecutorService          operationExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mod4j-operation");
        t.setDaemon(true);
        return t;
    });
    /**
     * 心跳定时任务线程池
     */
    private       ScheduledExecutorService heartbeatExecutor;

    /**
     * 开启心跳检测
     *
     * @param interval 检查间隔（秒）
     */
    public synchronized void startHeartbeat(int interval) {
        if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
            return;
        }
        heartbeatExecutor = new ScheduledThreadPoolExecutor(1, r -> {
            var t = new Thread(r, "mod4j-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(this::checkDevices, interval, interval, TimeUnit.SECONDS);
        System.out.println("[mod4j] 心跳检测已启动，间隔: " + interval + "s");
    }

    /**
     * 停止心跳检测
     */
    public synchronized void stopHeartbeat() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
            System.out.println("[mod4j] 心跳检测已停止");
        }
    }

    /**
     * 检查所有设备的连接状态
     */
    private void checkDevices() {
        for (ModbusDevice device : connectedDevices.values()) {
            if (!device.isHeartbeatEnabled()) {
                continue;
            }
            String deviceId = device.getDeviceId();
            // 在心跳线程中异步执行，避免一个设备阻塞导致心跳整体延迟
            CompletableFuture.runAsync(() -> {
                try {
                    device.ping();
                } catch (ModbusException e) {
                    System.err.println("[mod4j] 设备心跳丢失: " + deviceId + ", 错误: " + e.getMessage());
                    handleDeviceFailure(device);
                }
            }, operationExecutor);
        }
    }

    /**
     * 处理设备失败
     *
     * @param device 失败的设备
     */
    private void handleDeviceFailure(ModbusDevice device) {
        String deviceId = device.getDeviceId();

        while (connectedDevices.containsKey(deviceId)) {
            boolean isPersistent = persistentDevices.contains(deviceId);
            try {
                System.out.println("[mod4j] 尝试恢复设备连接: " + deviceId);
                device.refresh();
                System.out.println("[mod4j] 设备恢复成功: " + deviceId);
                break;
            } catch (ModbusException re) {
                System.err.println("[mod4j] 设备恢复失败: " + deviceId + ", 错误: " + re.getMessage());
                if (!isPersistent) {
                    System.err.println("[mod4j] 非常连接设备，正在移除: " + deviceId);
                    connectedDevices.remove(deviceId);
                    break;
                }
                // 常连接设备，无限重试（此处加延迟）
                try {
                    TimeUnit.SECONDS.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * 连接设备
     *
     * @param config 设备配置 {@link DeviceConfig}
     * @return 连接成功的设备对象 {@link ModbusDevice}
     * @throws ModbusException 如果连接失败
     */
    public ModbusDevice connectDevice(DeviceConfig config) throws ModbusException {
        String deviceId = config.getDeviceId();
        // 1. 检查当前是否已有该设备
        if (connectedDevices.containsKey(deviceId)) {
            ModbusDevice existingDevice = connectedDevices.get(deviceId);
            if (existingDevice.isConnected()) {
                System.out.println("[mod4j] 设备已连接，返回现有对象: " + deviceId);
                return existingDevice;
            }
            System.out.println("[mod4j] 设备存在但未连接，尝试刷新: " + deviceId);
            existingDevice.refresh();
            return existingDevice;
        }

        // 2. 根据不同设备类型，创建设备实例并连接
        ModbusDevice device;
        if (config.type() == DeviceType.RTU) {
            device = new SerialModbusDevice();
        } else if (config.type() == DeviceType.TCP) {
            device = new TcpModbusDevice();
        } else {
            throw new ModbusException("[mod4j] 不支持的设备类型");
        }

        device.connect(config);
        device.setClient(this);

        connectedDevices.put(device.getDeviceId(), device);
        return device;
    }

    /**
     * 批量连接设备
     *
     * @param configs 设备配置集合
     */
    public void batchConnectDevices(Collection<DeviceConfig> configs) {
        List<CompletableFuture<Void>> futures = configs.stream()
                                                       .map(config -> CompletableFuture.runAsync(() -> {
                                                           try {
                                                               connectDevice(config);
                                                           } catch (ModbusException e) {
                                                               System.err.println("[mod4j] 批量连接设备失败: " + config.getDeviceId() + ", " + e.getMessage());
                                                           }
                                                       }, operationExecutor))
                                                       .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    /**
     * 断开指定设备的连接并从管理池中移除
     *
     * @param deviceId 要断开连接的设备 ID
     * @throws ModbusException 如果断开过程中发生错误
     */
    public void disconnectDevice(String deviceId) throws ModbusException {
        ModbusDevice device = connectedDevices.remove(deviceId);
        persistentDevices.remove(deviceId);
        if (device != null) {
            device.disconnect();
        }
    }

    /**
     * 批量断开并移除设备
     *
     * @param deviceIds 设备 ID 集合
     */
    public void batchDisconnectDevices(Collection<String> deviceIds) {
        List<CompletableFuture<Void>> futures = deviceIds.stream()
                                                         .map(id -> CompletableFuture.runAsync(() -> {
                                                             try {
                                                                 disconnectDevice(id);
                                                             } catch (ModbusException e) {
                                                                 System.err.println("[mod4j] 批量断开设备失败: " + id + ", " + e.getMessage());
                                                             }
                                                         }, operationExecutor))
                                                         .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    /**
     * 将设备标记为常连接设备
     *
     * @param deviceId 设备 ID
     */
    public void markAsPersistent(String deviceId) {
        if (connectedDevices.containsKey(deviceId)) {
            persistentDevices.add(deviceId);
            System.out.println("[mod4j] 设备已标记为常连接: " + deviceId);
        }
    }

    /**
     * 取消设备的常连接标记
     *
     * @param deviceId 设备 ID
     */
    public void unmarkAsPersistent(String deviceId) {
        persistentDevices.remove(deviceId);
        System.out.println("[mod4j] 设备已取消常连接标记: " + deviceId);
    }

    /**
     * 获取已连接的设备
     *
     * @param deviceId 设备 ID
     * @return 设备对象，如果未找到则返回 null
     */
    public ModbusDevice getDevice(String deviceId) {
        return connectedDevices.get(deviceId);
    }

    /**
     * 发送原始字节请求到指定设备
     */
    public byte[] sendRawRequest(String deviceId, byte[] command) throws ModbusException {
        ModbusDevice device = connectedDevices.get(deviceId);
        if (device == null) {
            throw new ModbusException("[mod4j] 设备未连接或不存在: " + deviceId);
        }
        return device.sendRawRequest(command);
    }

    /**
     * 发送参数化请求到指定设备
     */
    public byte[] sendRequest(String deviceId, int slaveId, int funcCode, int address, int quantity) throws ModbusException {
        ModbusDevice device = connectedDevices.get(deviceId);
        if (device == null) {
            throw new ModbusException("[mod4j] 设备未连接或不存在: " + deviceId);
        }
        return device.sendRequest(slaveId, funcCode, address, quantity);
    }

    /**
     * 设置指定设备的超时时间
     */
    public void setTimeout(String deviceId, int timeout) throws ModbusException {
        ModbusDevice device = connectedDevices.get(deviceId);
        if (device == null) {
            throw new ModbusException("[mod4j] 设备未连接或不存在: " + deviceId);
        }
        device.setTimeout(timeout);
    }

    /**
     * 获取所有当前已连接的设备 ID 及其对应对象的映射副本
     */
    public Map<String, ModbusDevice> getConnectedDevices() {
        return new ConcurrentHashMap<>(connectedDevices);
    }

    /**
     * 优雅停机
     */
    public void shutdown() {
        stopHeartbeat();
        batchDisconnectDevices(new ArrayList<>(connectedDevices.keySet()));
        operationExecutor.shutdown();
        try {
            if (!operationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                operationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            operationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 获取业务操作执行线程池
     *
     * @return 线程池
     */
    public ExecutorService getOperationExecutor() {
        return operationExecutor;
    }
}
