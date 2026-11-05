package com.wolfhouse.mod4j.facade;

import com.wolfhouse.mod4j.device.ModbusDevice;
import com.wolfhouse.mod4j.device.SerialModbusDevice;
import com.wolfhouse.mod4j.device.TcpModbusDevice;
import com.wolfhouse.mod4j.device.conf.DeviceConfig;
import com.wolfhouse.mod4j.event.AbstractModbusEvent;
import com.wolfhouse.mod4j.event.DeviceConnectedEvent;
import com.wolfhouse.mod4j.event.DeviceDisconnectedEvent;
import com.wolfhouse.mod4j.event.ModbusEventListener;
import com.wolfhouse.mod4j.exception.ModbusException;
import lombok.Getter;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Modbus SDK 门面类，用于管理已连接设备并提供统一的通信入口
 *
 * @author Rylin Wolf
 */
@SuppressWarnings({"unused", "UnusedReturnValue"})
public class ModbusClient {
    /**
     * 已连接设备池，使用 ConcurrentHashMap 保证线程安全
     */
    private final Map<String, ModbusDevice> connectedDevices  = new ConcurrentHashMap<>();
    /**
     * 事件监听器集合
     */
    private final List<ModbusEventListener> listeners         = new CopyOnWriteArrayList<>();
    /**
     * 常连接设备 ID 集合
     */
    private final Set<String>               persistentDevices = ConcurrentHashMap.newKeySet();
    /**
     * 业务操作执行线程池（用于批量连接/断开）
     */
    @Getter
    private final ExecutorService           operationExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mod4j-operation");
        t.setDaemon(true);
        return t;
    });
    /**
     * 心跳定时任务线程池
     */
    private ScheduledExecutorService heartbeatExecutor;
    private final Set<String> heartbeatsInFlight = ConcurrentHashMap.newKeySet();
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    private volatile boolean closed;

    /**
     * 开启心跳检测
     *
     * @param interval 检查间隔（秒）
     */
    public synchronized void startHeartbeat(int interval) {
        if (closed) {
            throw new ModbusException("[mod4j] 客户端已关闭");
        }
        if (interval <= 0) {
            throw new IllegalArgumentException("心跳间隔必须大于零");
        }
        if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
            return;
        }
        heartbeatExecutor = new ScheduledThreadPoolExecutor(1, r -> {
            var t = new Thread(r, "mod4j-heartbeat");
            t.setDaemon(true);
            return t;
        });
        ScheduledExecutorService monitor = heartbeatExecutor;
        monitor.scheduleAtFixedRate(() -> checkDevices(monitor), interval, interval, TimeUnit.SECONDS);
        System.out.println("[mod4j] 心跳检测已启动，间隔: " + interval + "s");
    }

    /**
     * 停止心跳检测
     */
    public synchronized void stopHeartbeat() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
            notifyAll();
            System.out.println("[mod4j] 心跳检测已停止");
        }
    }

    /**
     * 检查所有设备的连接状态
     */
    private void checkDevices(ScheduledExecutorService monitor) {
        connectedDevices.forEach((deviceId, device) -> {
            if (monitor.isShutdown() || !device.isHeartbeatEnabled() || !heartbeatsInFlight.add(deviceId)) {
                return;
            }
            try {
                operationExecutor.execute(() -> checkDevice(deviceId, device, monitor));
            } catch (RejectedExecutionException e) {
                heartbeatsInFlight.remove(deviceId);
            }
        });
    }

    private void checkDevice(String deviceId, ModbusDevice device, ScheduledExecutorService monitor) {
        try {
            if (!monitor.isShutdown() && connectedDevices.get(deviceId) == device) {
                device.ping();
            }
        } catch (ModbusException e) {
            System.err.println("[mod4j] 设备心跳丢失: " + deviceId + ", 错误: " + e.getMessage());
            recoverDevice(deviceId, device, monitor);
        } finally {
            heartbeatsInFlight.remove(deviceId);
        }
    }

    private void recoverDevice(String deviceId, ModbusDevice device, ScheduledExecutorService monitor) {
        var retry = new AtomicBoolean();
        do {
            retry.set(false);
            // 与同 ID 的显式连接、断开互斥，并核对设备实例，避免旧任务恢复已移除的设备。
            connectedDevices.computeIfPresent(deviceId, (id, current) -> {
                if (current != device || monitor.isShutdown()) {
                    return current;
                }
                try {
                    device.refresh();
                    publishEvent(new DeviceConnectedEvent(device));
                    return device;
                } catch (ModbusException e) {
                    System.err.println("[mod4j] 设备恢复失败: " + id + ", 错误: " + e.getMessage());
                    if (persistentDevices.contains(id)) {
                        retry.set(true);
                        return device;
                    }
                    return null;
                }
            });
        } while (retry.get() && awaitRecoveryRetry(deviceId, device, monitor));
    }

    private synchronized boolean awaitRecoveryRetry(String deviceId, ModbusDevice device,
                                                    ScheduledExecutorService monitor) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (!monitor.isShutdown() && connectedDevices.get(deviceId) == device) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return true;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    /**
     * 连接设备
     *
     * @param config 设备配置 {@link DeviceConfig}
     * @return 连接成功的设备对象 {@link ModbusDevice}
     * @throws ModbusException 如果连接失败
     */
    public ModbusDevice connectDevice(DeviceConfig config) throws ModbusException {
        return connectDevice(config, false);
    }

    private ModbusDevice connectDevice(DeviceConfig config, boolean persistent) {
        lifecycleLock.readLock().lock();
        try {
            if (closed) {
                throw new ModbusException("[mod4j] 客户端已关闭");
            }
            String deviceId = config.getDeviceId();
            var created = new AtomicBoolean();
            ModbusDevice device = connectedDevices.compute(deviceId, (id, existing) -> {
                if (existing == null) {
                    existing = switch (config.devType()) {
                        case SERIAL -> new SerialModbusDevice();
                        case TCP -> new TcpModbusDevice();
                    };
                    existing.connect(config);
                    existing.setClient(this);
                    created.set(true);
                } else if (!existing.isConnected()) {
                    existing.refresh();
                }
                if (persistent) {
                    persistentDevices.add(id);
                }
                return existing;
            });
            if (created.get()) {
                publishEvent(new DeviceConnectedEvent(device));
            }
            return device;
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * 批量连接设备
     *
     * @param configs 设备配置集合
     */
    public boolean batchConnectDevices(Collection<DeviceConfig> configs) {
        AtomicBoolean success = new AtomicBoolean(true);
        List<CompletableFuture<Void>> futures = configs.stream()
                                                       .map(config -> CompletableFuture.runAsync(() -> {
                                                           try {
                                                               connectDevice(config);
                                                           } catch (ModbusException e) {
                                                               System.err.println("[mod4j] 批量连接设备失败: " + config.getDeviceId() + ", " + e.getMessage());
                                                               success.set(false);
                                                           }
                                                       }, operationExecutor))
                                                       .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return success.get();
    }

    /**
     * 批量连接 常连接设备
     *
     * @param configs 设备配置集合
     */
    public boolean batchConnectPersistentDevices(Collection<DeviceConfig> configs) {
        AtomicBoolean success = new AtomicBoolean(true);
        List<CompletableFuture<Void>> futures = configs.stream()
                                                       .map(config -> CompletableFuture.runAsync(() -> {
                                                           try {
                                                               connectDevice(config, true);
                                                           } catch (ModbusException e) {
                                                               System.err.println("[mod4j] 批量连接设备失败: " + config.getDeviceId() + ", " + e.getMessage());
                                                               success.set(false);
                                                           }
                                                       }, operationExecutor))
                                                       .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return success.get();
    }

    /**
     * 断开指定设备的连接并从管理池中移除
     *
     * @param deviceId 要断开连接的设备 ID
     * @throws ModbusException 如果断开过程中发生错误
     */
    public void disconnectDevice(String deviceId) throws ModbusException {
        var disconnected = new AtomicReference<ModbusDevice>();
        var failure = new AtomicReference<ModbusException>();
        connectedDevices.computeIfPresent(deviceId, (id, device) -> {
            persistentDevices.remove(id);
            try {
                device.disconnect();
                disconnected.set(device);
            } catch (ModbusException e) {
                failure.set(e);
            }
            return null;
        });
        if (failure.get() != null || disconnected.get() != null) {
            synchronized (this) {
                notifyAll();
            }
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        if (disconnected.get() != null) {
            publishEvent(new DeviceDisconnectedEvent(disconnected.get()));
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
        connectedDevices.computeIfPresent(deviceId, (id, device) -> {
            persistentDevices.add(id);
            System.out.println("[mod4j] 设备已标记为常连接: " + id);
            return device;
        });
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
     * 添加事件监听器
     *
     * @param listener 事件监听器
     */
    public void addEventListener(ModbusEventListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * 移除事件监听器
     *
     * @param listener 事件监听器
     */
    public void removeEventListener(ModbusEventListener listener) {
        listeners.remove(listener);
    }

    /**
     * 发布事件
     *
     * @param event Modbus 事件
     */
    private void publishEvent(AbstractModbusEvent event) {
        if (listeners.isEmpty()) {
            return;
        }
        try {
            operationExecutor.execute(() -> {
                for (ModbusEventListener listener : listeners) {
                    try {
                        listener.onEvent(event);
                    } catch (Exception e) {
                        System.err.printf("[mod4j] 事件 {%s} 处理异常: %s%n", event, e.getMessage());
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            if (!closed) {
                throw e;
            }
        }
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
     * 检查设备是否已连接
     *
     * @param deviceId 设备 ID
     * @return 如果设备已连接则返回 true，否则返回 false
     */
    public boolean isDeviceConnected(String deviceId) {
        ModbusDevice device = connectedDevices.get(deviceId);
        return device != null && device.isConnected();
    }

    /**
     * 发送原始字节请求到指定设备
     */
    public byte[] sendRawRequest(String deviceId, byte[] command) throws ModbusException {
        ModbusDevice device = getModbusDevice(deviceId);
        return device.sendRawRequest(command);
    }

    /**
     * 发送参数化请求到指定设备
     */
    public byte[] sendRequest(String deviceId, int slaveId, int funcCode, int address, int quantity) throws ModbusException {
        ModbusDevice device = getModbusDevice(deviceId);
        return device.sendRequest(slaveId, funcCode, address, quantity);
    }

    /**
     * 异步发送原始字节请求到指定设备
     *
     * @param deviceId 设备 ID
     * @param command  请求字节数组
     * @return CompletableFuture<byte[]> 异步请求结果
     * @throws ModbusException Modbus 异常
     */
    public CompletableFuture<byte[]> sendRawRequestAsync(String deviceId, byte[] command) throws ModbusException {
        ModbusDevice device = getModbusDevice(deviceId);
        return device.sendRawRequestAsync(command);
    }

    /**
     * 异步发送参数化请求到指定设备
     *
     * @param deviceId 设备 ID
     * @param slaveId  从站 ID
     * @param funcCode 功能码
     * @param address  地址
     * @param quantity 寄存器数量
     * @return CompletableFuture<byte[]> 异步请求结果
     * @throws ModbusException Modbus 异常
     */
    public CompletableFuture<byte[]> sendRequestAsync(String deviceId, int slaveId, int funcCode, int address, int quantity) throws ModbusException {
        ModbusDevice device = getModbusDevice(deviceId);
        return device.sendRequestAsync(slaveId, funcCode, address, quantity);
    }

    /**
     * 根据设备 ID 获取设备，不存在则抛出异常
     *
     * @param deviceId 设备 ID
     * @return 设备对象
     */
    private ModbusDevice getModbusDevice(String deviceId) {
        ModbusDevice device = connectedDevices.get(deviceId);
        if (device == null) {
            throw new ModbusException("[mod4j] 设备未连接或不存在: " + deviceId);
        }
        return device;
    }

    /**
     * 设置指定设备的超时时间
     */
    public void setTimeout(String deviceId, int timeout) throws ModbusException {
        ModbusDevice device = getModbusDevice(deviceId);
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
        lifecycleLock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
        } finally {
            lifecycleLock.writeLock().unlock();
        }
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
}
