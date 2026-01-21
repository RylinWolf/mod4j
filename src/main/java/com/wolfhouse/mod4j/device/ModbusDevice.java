package com.wolfhouse.mod4j.device;

import com.wolfhouse.mod4j.device.conf.DeviceConfig;
import com.wolfhouse.mod4j.enums.DeviceType;
import com.wolfhouse.mod4j.exception.ModbusException;
import com.wolfhouse.mod4j.facade.ModbusClient;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

/**
 * Modbus 设备接口，定义了连接、断开、刷新以及发送指令的标准行为
 *
 * @author Rylin Wolf
 */
public interface ModbusDevice {
    /**
     * 连接设备
     *
     * @param config 设备配置 {@link DeviceConfig}
     * @throws ModbusException 连接异常
     */
    default void connect(DeviceConfig config) throws ModbusException {
        checkSupported(config);
    }

    /**
     * 断开设备连接
     *
     * @throws ModbusException 断开连接异常
     */
    void disconnect() throws ModbusException;

    /**
     * 刷新设备状态（通常是重连）
     *
     * @throws ModbusException 刷新异常
     */
    void refresh() throws ModbusException;

    /**
     * 检查设备是否已连接
     *
     * @return true 表示已连接，false 表示未连接
     */
    boolean isConnected();

    /**
     * 发送已经构建好的原始字节指令
     *
     * @param command 原始字节指令
     * @return 响应字节数组
     * @throws ModbusException 发送异常
     */
    byte[] sendRawRequest(byte[] command) throws ModbusException;

    /**
     * 参数化发送请求，内部根据协议类型自动构建报文并发送
     *
     * @param slaveId  从站 ID
     * @param funcCode 功能码
     * @param address  寄存器地址
     * @param quantity 寄存器数量/线圈数量
     * @return 响应字节数组
     * @throws ModbusException 发送异常
     */
    byte[] sendRequest(int slaveId, int funcCode, int address, int quantity) throws ModbusException;

    /**
     * 异步发送已经构建好的原始字节指令
     *
     * @param command 原始字节指令
     * @return CompletableFuture，完成时包含响应字节数组
     */
    CompletableFuture<byte[]> sendRawRequestAsync(byte[] command);

    /**
     * 异步参数化发送请求
     *
     * @param slaveId  从站 ID
     * @param funcCode 功能码
     * @param address  寄存器地址
     * @param quantity 数量
     * @return CompletableFuture，完成时包含响应字节数组
     */
    CompletableFuture<byte[]> sendRequestAsync(int slaveId, int funcCode, int address, int quantity);

    /**
     * 获取超时时间（毫秒）
     *
     * @return 超时时间（毫秒）
     */
    int getTimeout();

    /**
     * 设置超时时间
     *
     * @param timeout 超时时间（毫秒）
     */
    void setTimeout(int timeout);

    /**
     * 获取设备标识符（用于在管理池中唯一标识设备）
     *
     * @return 设备标识符字符串
     */
    String getDeviceId();

    /**
     * 向设备发送心跳包/探测包，以验证连接是否仍然有效
     *
     * @throws ModbusException 如果心跳检测失败（如超时或连接断开）
     */
    void ping() throws ModbusException;

    /**
     * 检查心跳检测是否开启
     *
     * @return true 表示已开启，false 表示已关闭
     */
    boolean isHeartbeatEnabled();

    /**
     * 设置是否开启心跳检测
     *
     * @param enabled true 为开启，false 为关闭
     */
    void setHeartbeatEnabled(boolean enabled);

    /**
     * 获取当前心跳检测策略
     *
     * @return 心跳策略
     */
    HeartbeatStrategy getHeartbeatStrategy();

    /**
     * 设置心跳检测策略
     *
     * @param strategy 心跳策略
     */
    void setHeartbeatStrategy(HeartbeatStrategy strategy);

    /**
     * 设置关联的客户端（用于获取线程池和发布事件）
     *
     * @param client ModbusClient 门面类
     */
    default void setClient(ModbusClient client) {
    }

    /**
     * 获取支持的设备类型
     *
     * @return 支持的设备类型数组
     */
    Set<DeviceType> supportedDeviceTypes();

    /**
     * 检查设备配置是否支持当前设备类型
     *
     * @param config 设备配置
     */
    default void checkSupported(DeviceConfig config) {
        DeviceType      type      = config.type();
        Set<DeviceType> supported = this.supportedDeviceTypes();
        if (!supported.contains(type)) {
            throw new ModbusException("[mod4j] 当前 modbus 设备不支持该连接类型： [%s]！支持的类型包括：[%s]".formatted(type, supported));
        }
    }

    /**
     * 执行异步操作，使用提供的客户端的执行器或公共 fork-join 池（如果客户端为 null）。
     * 如果发生错误，将重新抛出 {@code ModbusException}。
     *
     * @param supplier 用于异步执行的供应商函数。不得为 null。
     * @param client   提供操作执行器的 Modbus 客户端。可以为 null，
     *                 在这种情况下使用公共 fork-join 池。
     * @return 一个 {@code CompletableFuture}，它将返回 Supplier 的结果，
     * 或者在执行期间发生错误时抛出 {@code ModbusException}。
     */
    default CompletableFuture<byte[]> doAsync(Supplier<byte[]> supplier, ModbusClient client) {
        Executor executor = (client != null) ? client.getOperationExecutor() : ForkJoinPool.commonPool();
        return CompletableFuture.supplyAsync(supplier, executor).exceptionally(e -> {
            System.err.println("[mod4j] 线程池执行异步任务失败! " + e.getMessage());
            throw new ModbusException(e);
        });
    }
}