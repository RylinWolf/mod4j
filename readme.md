# Mod4J

Mod4J 是面向工业设备采集和联调的 Java Modbus 客户端库。通过 `ModbusClient`，应用可以用同一套 API 连接串口设备、TCP 从站和透传 RTU 报文的网关。

- **请求收发** · 支持按从站地址、功能码、起始地址和数量构造读请求，也支持发送完整的原始报文。两种方式都提供同步和 `CompletableFuture<byte[]>` 异步接口。
- **设备管理** · 支持批量连接和断开、连接与断开事件，以及通过心跳触发连接恢复。
- **开发联调** · 提供可配置读寄存器响应的 TCP 模拟器，以及基于输入输出流的 RTU 模拟器。

## 通信模式

`DeviceConfig` 分别用 `ModDeviceType` 指定连接方式，用 `CommunicationType` 指定报文格式。

| 模式 | `ModDeviceType` | `CommunicationType` | 设备配置 |
| --- | --- | --- | --- |
| Modbus TCP | `TCP` | `TCP` | `TcpDeviceConfig`，发送带 MBAP 头的报文 |
| Modbus RTU over TCP | `TCP` | `RTU` | `TcpDeviceConfig`，在 TCP 连接上传输带 CRC 的 RTU 报文 |
| Modbus RTU | `SERIAL` | `RTU` | `SerialDeviceConfig`，通过串口收发 RTU 报文 |

## 安装

需要 JDK 23 和 Maven 3。将源码构建并安装到本地 Maven 仓库。

```sh
git clone https://github.com/RylinWolf/mod4j.git
cd mod4j
mvn -DskipTests install
```

在应用的 `pom.xml` 中添加依赖，将 `MOD4J_VERSION` 替换为所构建版本在本项目 [pom.xml](pom.xml) 中声明的版本号。串口通信依赖 jSerialComm，由 Maven 一并引入。

```xml
<dependency>
    <groupId>com.wolfhouse</groupId>
    <artifactId>mod4j</artifactId>
    <version>MOD4J_VERSION</version>
</dependency>
```

## 快速开始

将以下示例保存为 Maven 应用中的 `ReadHoldingRegister.java`。示例连接 `127.0.0.1:5502` 上的 Modbus TCP 从站，读取从站 1 的一个保持寄存器。请先启动监听该端口的从站或模拟器，或将地址、端口和寄存器参数改为实际设备的配置。

```java
import com.wolfhouse.mod4j.device.ModbusDevice;
import com.wolfhouse.mod4j.device.conf.DeviceConfig;
import com.wolfhouse.mod4j.device.conf.TcpDeviceConfig;
import com.wolfhouse.mod4j.enums.ModDeviceType;
import com.wolfhouse.mod4j.enums.CommunicationType;
import com.wolfhouse.mod4j.facade.ModbusClient;

import java.util.HexFormat;

public class ReadHoldingRegister {
    public static void main(String[] args) {
        var client = new ModbusClient();
        try {
            var config = new DeviceConfig(
                    ModDeviceType.TCP,
                    CommunicationType.TCP,
                    2_000,
                    new TcpDeviceConfig().setIp("127.0.0.1").setPort(5502)
            );

            ModbusDevice device = client.connectDevice(config);
            // 从站 1，功能码 0x03，报文中的起始地址 0，读取 1 个寄存器
            byte[] response = device.sendRequest(1, 0x03, 0, 1);
            System.out.println(HexFormat.ofDelimiter(" ").formatHex(response));
        } finally {
            client.shutdown();
        }
    }
}
```

`DeviceConfig` 的超时参数以毫秒计，用于 TCP 建连和读取，以及串口读取。寄存器地址使用设备报文中的地址偏移，请根据设备手册换算。参数化请求在发送前拒绝超范围地址和非法读数量。SDK 校验 TCP 响应头或 RTU CRC。参数化 TCP 读请求还会校验响应数据长度是否与请求数量相符。响应以原始字节数组返回，由应用解码设备数据和处理 Modbus 异常响应。RTU 厂商扩展若没有 SDK 可识别的帧长，需要配置正数读取超时作为报文边界。

对于需要携带额外数据的写请求，例如 `0x0F` 或 `0x10`，应自行组帧后调用 `sendRawRequest`。TCP 连接异常时，SDK 只会对格式明确的标准读请求重试一次。写请求只发送一次，若收不到响应，执行结果由调用方核实。

## 设备管理

- **改用串口** · 将连接方式和报文格式改为 `ModDeviceType.SERIAL` 与 `CommunicationType.RTU`，并传入 `SerialDeviceConfig.standard9600Serial("COM3")`。替换为实际串口名，该工厂方法使用 9600 波特、8 数据位、无校验、1 停止位。
- **异步请求** · `device.sendRequestAsync(1, 0x03, 0, 1)` 返回 `CompletableFuture<byte[]>`。同一设备上的收发操作串行执行。
- **心跳与恢复** · TCP 设备可通过 `TcpDeviceConfig` 设置并启用 `HeartbeatStrategy`，随后调用 `client.startHeartbeat(5)` 每 5 秒调度检测。可用 `client.markAsPersistent(device.getDeviceId())` 将已连接设备标记为常连接，供心跳触发的恢复流程持续重试。
- **断开与事件** · 通过 `client.addEventListener(...)` 订阅异步设备事件。用 `client.disconnectDevice(device.getDeviceId())` 断开单个设备，用 `client.shutdown()` 停止客户端并释放设备连接和执行器。

## 模拟器与测试

- [`ModbusTcpSimulator`](src/main/java/com/wolfhouse/mod4j/utils/ModbusTcpSimulator.java) 可监听 TCP 端口，配置十进制从站地址、功能码、寄存器或按位打包的数据、响应延迟和响应成功率。默认使用 Modbus TCP，构造时传入 `false` 可切换为 RTU over TCP，按功能码读取完整 RTU 请求并校验 CRC。未识别的请求布局按 8 字节固定格式处理。
- [`ModbusRtuSimulator`](src/main/java/com/wolfhouse/mod4j/utils/ModbusRtuSimulator.java) 接收输入输出流，可配合 Java 管道流模拟 RTU 收发。支持分批读入请求，`0x01` 和 `0x03` 按请求数量返回模拟数据。
- `mvn test` 运行回归测试。测试使用本机回环端口和内存流，无需连接外部设备。
