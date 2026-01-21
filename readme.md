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
│           │   ├── conf                    # 设备配置包
│           │   ├── ModbusDevice.java       # 设备抽象接口
│           │   ├── SerialModbusDevice.java # 串口设备实现
│           │   └── TcpModbusDevice.java    # TCP 设备实现
│           ├── event           # 事件定义
│           │   ├── AbstractModbusEvent.java    # 抽象事件基类
│           │   ├── DeviceConnectedEvent.java   # 设备连接事件
│           │   ├── DeviceDisconnectedEvent.java # 设备断开事件
│           │   └── ModbusEventListener.java # 设备事件监听器
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
└── pom.xml                     # 项目依赖配置
```

## 核心特性

- **设备配置抽象**: 引入 `DeviceConfig` 记录类，统一管理设备连接参数。
- **常连接与自愈**: 支持将设备标记为“常连接”，在网络抖动或设备掉线时自动进行无限次重连尝试，确保关键连接的持久性。
- **并发安全**: 核心通信逻辑采用同步锁机制，确保在多线程环境下对同一设备的指令收发是顺序执行的。
- **可阻塞可异步响应**: 封装了复杂的 IO 操作，调用者可像调用普通函数一样同步获取从站响应，也可通过 Future 异步获取响应
- **内置模拟器**: 提供 `ModbusTcpSimulator` 和 `ModbusRtuSimulator`，方便在无硬件环境下进行开发测试。
- **协议支持**:
    - 内置 CRC16 校验，支持标准 Modbus RTU。
    - 支持标准 Modbus TCP 报文格式，自动处理事务标识符。

## 快速开始

### 1. 引入依赖

项目使用了 `jSerialComm` 处理串口通信，确保在 `pom.xml` 中已包含该依赖。

### 2. 连接与管理设备

```java
import com.wolfhouse.mod4j.device.conf.TcpDeviceConfig;

void fun() {
    ModbusClient client = new ModbusClient();
    try {
        // 定义配置
        DeviceConfig config = new DeviceConfig(DeviceType.TCP, 5000, new TcpDeviceConfig("127.0.0.1", 5502));
        // 连接单个设备
        ModbusDevice tcpDevice = client.connectDevice(config);
        // 批量连接设备
        client.batchConnectDevices(Arrays.asList(config1, config2));
        // 标记为常连接设备（自动重连且不自动移除）
        client.markAsPersistent(tcpDevice.getDeviceId());
        // 发送指令
        byte[] response = tcpDevice.sendRequest(1, 3, 0, 10);
    } catch (ModbusException e) {
        System.err.println("[mod4j] 错误: " + e.getMessage());
    }
}
```

### 3. 断开连接

```java
void fun() {
    try {
        client.disconnectDevice("TCP:192.168.1.10:5502");
    } catch (ModbusException e) {
        e.printStackTrace();
    }
}
```

## 开发环境

- Java 23
- Maven 3.x
- 依赖库
    - jSerialComm
    - lombok
