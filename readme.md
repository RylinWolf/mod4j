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
│               └── ModbusProtocolUtils.java # 报文构建和校验工具
├── pom.xml                     # 项目依赖配置
└── Modbus 需求分析.md            # 需求文档
```

## 核心特性

- **多设备管理**: `ModbusClient` 内部维护连接池，支持同时连接多个 Modbus 设备。
- **并发安全**: 核心通信逻辑采用同步锁机制，确保在多线程环境下对同一设备的指令收发是顺序执行的。
- **阻塞同步响应**: 封装了复杂的 IO 操作，调用者可像调用普通函数一样同步获取从站响应。
- **细粒度异常处理**: 支持捕获超时异常 (`ModbusTimeoutException`) 和 IO 异常 (`ModbusIOException`)。
- **超时机制**: 所有 IO 操作均可配置超时时间。
- **协议支持**:
    - 内置 CRC16 校验，支持标准 Modbus RTU。
    - 支持标准 Modbus TCP 报文格式，自动处理事务标识符。

## 快速开始

### 1. 引入依赖

项目使用了 `jSerialComm` 处理串口通信，确保在 `pom.xml` 中已包含该依赖。

### 2. 连接与管理设备

```java
ModbusClient client = new ModbusClient();

try {
    // 连接 TCP 设备，支持设置超时时间（毫秒）
    ModbusDevice tcpDevice = client.connectDevice(DeviceType.TCP, new Object[]{"192.168.1.10", 502}, 5000);

    // 连接 RTU 设备
    ModbusDevice rtuDevice = client.connectDevice(DeviceType.RTU, new Object[]{"COM1", 9600});

    // 发送指令并处理可能的超时或 IO 异常
    byte[] response = tcpDevice.sendRequest(1, 3, 0, 10);
} catch (ModbusTimeoutException e) {
    System.err.println("[mod4j] 通信超时: " + e.getMessage());
} catch (ModbusIOException e) {
    System.err.println("[mod4j] 网络或串口错误: " + e.getMessage());
} catch (ModbusException e) {
    System.err.println("[mod4j] 其他 Modbus 错误: " + e.getMessage());
}
```

### 3. 断开连接

```java
try{
        client.disconnectDevice("TCP:192.168.1.10:502"); 
}catch(
ModbusException e){
        e.

printStackTrace();
}
```

## 核心重构与优化内容

1. **IO 超时机制**: 所有连接和通信操作均支持可配置的超时时间，避免了在网络故障或设备无响应时无限期阻塞。
2. **细粒度异常控制**: 引入了 `ModbusTimeoutException` 和 `ModbusIOException`，方便上层业务进行针对性重试或告警处理。
3. **职责分离**: 对核心通信类进行了重构，将大型 IO 读取方法拆解为更小、单一职责的方法，提高了代码的可维护性。
4. **并发优化**: 优化了 `ModbusProtocolUtils` 中的事务标识符生成算法，使用无锁的 `AtomicInteger.getAndUpdate` 替代同步锁。

## 开发环境

- Java 23
- Maven 3.x
- 依赖库：jSerialComm
