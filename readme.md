# Mod4J - Modbus SDK

Mod4J 是一个简单易用的 Java Modbus SDK，支持 Modbus TCP 和 Modbus RTU (串口) 协议。它采用门面模式设计，为开发者提供统一的接口来操作不同类型的
Modbus 设备。

## 项目结构

```text
mod4j
├── src
│   └── main
│       └── java
│           ├── device          # 设备模块，包含 TCP 和串口设备的具体实现
│           │   ├── ModbusDevice.java       # 设备抽象接口
│           │   ├── DeviceConfig.java       # 设备配置记录类
│           │   ├── SerialModbusDevice.java # 串口设备实现
│           │   └── TcpModbusDevice.java    # TCP 设备实现
│           ├── enums           # 枚举定义
│           │   └── DeviceType.java         # 设备类型（RTU, TCP）
│           ├── exception       # 异常模块
│           │   ├── ModbusException.java    # 自定义异常基类
│           │   ├── ModbusIOException.java   # IO 异常
│           │   └── ModbusTimeoutException.java # 超时异常
│           ├── facade          # 门面模块
│           │   └── ModbusClient.java       # 统一操作接口
│           └── utils           # 工具类
│               ├── ModbusProtocolUtils.java # 报文构建和校验工具
│               ├── ModbusTcpSimulator.java  # TCP 模拟器
│               └── ModbusRtuSimulator.java  # RTU 模拟器
├── pom.xml                     # 项目依赖配置
└── Modbus 需求分析.md            # 需求文档
```

## 核心特性

- **设备配置抽象**: 引入 `DeviceConfig` 记录类，统一管理设备连接参数。
- **多设备管理**: `ModbusClient` 内部维护连接池，支持同时连接多个 Modbus 设备，并防止对同一设备的重复连接。
- **连接安全保证**: 无论是通过 `ModbusClient` 还是直接操作 `ModbusDevice` 对象，系统均能识别并拦截多余的连接请求。
- **批量异步操作**: 提供 `batchConnectDevices` 和 `batchDisconnectDevices` 方法，利用多线程提升大规模设备管理效率。
- **常连接与自愈**: 支持将设备标记为“常连接”，在网络抖动或设备掉线时自动进行无限次重连尝试，确保关键连接的持久性。
- **异步/同步双支持**: 同时支持传统的阻塞同步调用和现代的 `CompletableFuture` 异步调用，适应不同并发编程模型。
- **并发安全**: 核心通信逻辑采用同步锁机制，确保在多线程环境下对同一设备的指令收发是顺序执行的。
- **阻塞同步响应**: 封装了复杂的 IO 操作，调用者可像调用普通函数一样同步获取从站响应。
- **细粒度异常处理**: 支持捕获超时异常 (`ModbusTimeoutException`) 和 IO 异常 (`ModbusIOException`)。
- **内置模拟器**: 提供 `ModbusTcpSimulator` 和 `ModbusRtuSimulator`，方便在无硬件环境下进行开发测试。
- **自定义心跳检测**: 支持用户自行设置心跳检测方法（通过 `HeartbeatStrategy`），并可独立开启或关闭每个设备的心跳检测。
- **健壮的资源管理**: 优化了 `disconnect` 逻辑，确保即使部分资源关闭异常，其他资源也能被正确清理。
- **完善的串口读取**: 改进了 RTU 模式下的串口读取机制，增加分包等待和可用数据检测，提升通信稳定性。
- **协议支持**:
    - 内置 CRC16 校验，支持标准 Modbus RTU。
    - 支持标准 Modbus TCP 报文格式，自动处理事务标识符。

## 快速开始

### 1. 引入依赖

项目使用了 `jSerialComm` 处理串口通信，确保在 `pom.xml` 中已包含该依赖。

### 2. 连接与管理设备

```java
void fun() {
    ModbusClient client = new ModbusClient();
    try {
        // 定义配置
        DeviceConfig config = new DeviceConfig(DeviceType.TCP, 5000, new TcpDeviceConfig("192.168.1.10", 502));
        // 连接单个设备
        ModbusDevice tcpDevice = client.connectDevice(config);
        // 设置自定义心跳策略（可选）
        tcpDevice.setHeartbeatStrategy(device -> {
            System.out.println("[mod4j] 执行自定义心跳...");
            device.sendRequest(1, 3, 0, 1);
        });
        // 标记为常连接设备（自动重连且不自动移除）
        client.markAsPersistent(tcpDevice.getDeviceId());
        // 可选：关闭心跳检测
        // tcpDevice.setHeartbeatEnabled(false);
        // 发送异步指令
        client.getDevice(tcpDevice.getDeviceId()).sendRequestAsync(1, 3, 0, 10)
              .thenAccept(resp -> System.out.println("[mod4j] 异步获取响应长度: " + resp.length))
              .exceptionally(ex -> {
                  System.err.println("[mod4j] 异步请求失败: " + ex.getMessage());
                  return null;
              });

    } catch (ModbusException e) {
        System.err.println("[mod4j] 错误: " + e.getMessage());
    }
}
```

### 3. 断开连接

```java
void fun() {
    try {
        client.disconnectDevice("TCP:192.168.1.10:502");
    } catch (ModbusException e) {
        e.printStackTrace();
    }
}
```

## 核心重构与优化内容

1. **设备配置 Record**: 引入 `DeviceConfig` 结合 `AbstractDeviceConfig` 体系，替代 Object 数组参数传递，提高类型安全和 IDE
   属性提示。
2. **批量并发执行**: 引入 `ExecutorService` 处理设备生命周期操作，显著缩短多设备初始化时间。
3. **增强型心跳自愈**: 区分普通设备与常连接设备，常连接设备具备无限重连特性，适用于工业级可靠性需求。
4. **日志规范**: 所有系统输出均以 `[mod4j]` 为前缀，方便集成到外部日志系统。

## 开发环境

- Java 23
- Maven 3.x
- 依赖库：jSerialComm
