# NLS Dynamic Balancer — 深度技术分析

> 分析日期: 2026-07-01
> 基于代码版本: dynamic_balancer.py v1.0 / balancer_web.py v2.0 / Android APK

---

## 目录

1. [系统架构总览](#1-系统架构总览)
2. [dynamic_balancer.py — 扫描→反相→复扫循环算法详解](#2-dynamic_balancerpy--扫描反相复扫循环算法详解)
3. [balancer_web.py — Web 仪表盘详解](#3-balancer_webpy--web-仪表盘详解)
4. [Android APK — USB OTG 通信机制](#4-android-apk--usb-otg-通信机制)
5. [nls_baseline.json — 基线校准详解](#5-nls_baselinejson--基线校准详解)
6. [同频反相 vs 阴阳双频 — 完整数学推导](#6-同频反相-vs-阴阳双频--完整数学推导)
7. ["降噪耳机"算法类比](#7-降噪耳机算法类比)
8. [五行校正机制](#8-五行校正机制)
9. [数据流架构图](#9-数据流架构图)
10. [完整算法逻辑](#10-完整算法逻辑)

---

## 1. 系统架构总览

```
┌──────────────────────────────────────────────────────────────┐
│                    NLS Dynamic Balancer                       │
├──────────────┬───────────────────────┬───────────────────────┤
│  桌面版 (Py) │    Web仪表盘 (Py+JS)   │   Android APK (Kotlin) │
│ dynamic_bal- │   balancer_web.py     │   apk/MainActivity.kt  │
│ ancer.py     │   + 内嵌 HTML/CSS/JS  │   + BalancerEngine.kt  │
├──────────────┼───────────────────────┼───────────────────────┤
│ COM4 串口    │   COM4 → HTTP:8080    │   USB OTG (FTDI)       │
│ pyserial     │   threading + HTTPSvr │   UsbManager + bulk    │
├──────────────┴───────────────────────┴───────────────────────┤
│            FT232R UART桥接 → NLS 手环传感器                    │
│      128字节 OUT 命令 → CH1+CH2 双通道信号发射                │
│      256字节 IN 响应 = 器官共振回采数据                       │
└──────────────────────────────────────────────────────────────┘
```

### 硬件通信协议

```
┌────────────────── 128 字节 OUT 命令包 ──────────────────┐
│ Byte offset:  │ 0..8  │ 9  │ 10 │ 11 │ 12 │ 13 │ 14 │ 15 │ 16..127 │
│ Meaning:      │(reserved)│ b9 │ —  │ b11 │ —  │ b9 │ —  │ b15 │(zero)   │
│               │          │CH1 │    │CH1 │    │CH2 │    │CH2 │         │
│               │          │频率│    │振幅│    │频率│    │振幅│         │
└───────────────────────────────────────────────────────────┘

     收发流程:
     主机 ──128B OUT──▶ 传感器(触发宽带噪音) ──▶ 人体器官(选择性共振)
     主机 ◀──256B IN─── 传感器(回采被器官改造后的差谱)
```

### 频率计算公式

```
频率(MHz) = 7.3728 × 2^(b9/4) × 3

示例:
  b9=1  → 7.3728 × 2^0.25  × 3 ≈ 26 MHz
  b9=14 → 7.3728 × 2^3.5   × 3 ≈ 250 MHz
  b9=35 → 7.3728 × 2^8.75  × 3 ≈ 9500 MHz (9.5GHz)
```

---

## 2. dynamic_balancer.py — 扫描→反相→复扫循环算法详解

### 2.1 核心类结构

```
DynamicBalancer
├── 配置
│   ├── COM_PORT = "COM4"
│   ├── B9_RANGE = range(14, 32)    ← 18个器官频段 (简洁版)
│   ├── BASELINE_FILE = "nls_baseline.json"
│   ├── WUXING_CORRECTION = {木:1.0, 火:1.5, 土:1.0, 金:1.2, 水:0.8}
│   └── B9_MAP = {14..31 → (器官, 经络, 五行)}
│
├── 数据
│   ├── baseline: dict  ← 悬空校准的噪音基线
│   └── history: defaultdict(list) ← (b9, organ) → [历史delta列表]
│
└── 方法
    ├── calibrate()   → 空扫基线, 保存到 JSON
    ├── probe(b9, b11=15) → 发送128B命令, 返回256B响应
    ├── scan()        → 扫描全部 B9_RANGE, 返回 {b9: delta}
    ├── balance(deltas) → 反相治疗: b11/b15 振幅反向调整
    ├── verify(before_deltas) → 复扫验证, 返回(改善数, 恶化数)
    ├── loop(interval=5) → 主循环: scan→balance→verify
    ├── run_once()    → 单次执行
    ├── connect() / disconnect() → COM4 串口管理
    └── load_baseline() / save_baseline() → 基线持久化
```

### 2.2 probe() — 单点探测

```python
def probe(self, b9, b11=15):
    cmd = bytearray(128)         # 128字节全零
    cmd[9] = b9; cmd[11] = b11  # CH1: 频率=b9, 振幅=b11
    cmd[13] = b9; cmd[15] = b11 # CH2: 频率=b9, 振幅=b11 (同频同幅)
    self.ser.reset_input_buffer()
    self.ser.write(bytes(cmd))
    time.sleep(0.08)             # 等待传感器响应
    return list(self.ser.read(256))  # 读取256字节响应
```

**关键参数:**
- `b9`: 频率代码 (14~31), 决定发射的中心频率
- `b11`/`b15`: 振幅代码 (默认15), 范围 3~80
- `0.08s` 延迟: 传感器需要时间产生宽带噪音并回采器官共振信号
- 256 字节回采: 每个频率点的器官共振响应谱

### 2.3 scan() — 全频段扫描

```python
def scan(self):
    deltas = {}
    for b9 in B9_RANGE:           # 遍历 18 个器官频段
        resp = self.probe(b9)     # 发送探测, 等80ms, 读256B
        if len(resp) == 256:
            avg = sum(resp) / 256          # 256B 的均值 = 该频段的响应强度
            bl = self.baseline.get(b9, 105) # 基线值 (默认105)
            deltas[b9] = round(avg - bl, 1) # Δ = 实测 - 基线
    return deltas
```

**Δ 的含义:**
- Δ > 0: 器官在该频率上比正常"亮"(偏盛/亢进)
- Δ < 0: 器官在该频率上比正常"暗"(偏弱/不足)
- |Δ| < 4: 视为正常范围, 无需干预

### 2.4 balance() — 反相治疗

```python
def balance(self, deltas):
    balanced = []
    for b9, delta in sorted(deltas.items(), key=lambda x: -abs(x[1])):
        #                               ↑ 按 |Δ| 降序, 最异常的先治
        if abs(delta) < 4:
            continue                     # 正常范围跳过
        organ, meridian, wuxing = B9_MAP.get(b9, ('?', '?', '土'))
        freq = 7.3728 * (2**(b9/4)) * 3
        corr = WUXING_CORRECTION.get(wuxing, 1.0)

        # 核心: 振幅调整量
        base = 15                        # 默认振幅 (中值)
        adjust = int(abs(delta) * 0.5 * corr)  # Δ 的 50% × 五行系数
        adjust = min(adjust, 60)         # 上限 60 (防止过度刺激)

        if delta > 0:  # 偏高 → 抑制
            b11 = max(3, base - adjust)  # CH1 降
            b15 = min(80, base + adjust) # CH2 升
            direction = "↓"
        else:           # 偏低 → 激发
            b11 = min(80, base + adjust) # CH1 升
            b15 = max(3, base - adjust)  # CH2 降
            direction = "↑"

        cmd = bytearray(128)
        cmd[9] = b9; cmd[11] = b11      # CH1 同频, 振幅调整
        cmd[13] = b9; cmd[15] = b15     # CH2 同频, 振幅反向
        self.ser.write(bytes(cmd))
        time.sleep(0.15)
    return balanced
```

**反相振幅逻辑图解:**

```
Δ = +12.0 (偏高) → need 抑制 ↓
  adjust = 12 * 0.5 * 1.0 = 6
  CH1 = b9=14, b11=15-6=9   ← 降低信号抑制共振
  CH2 = b9=14, b15=15+6=21  ← 增强反相信号
  效果: CH1弱 + CH2强 = 净抑制

Δ = -10.0 (偏低) → need 激发 ↑
  adjust = 10 * 0.5 * 1.0 = 5
  CH1 = b9=19, b11=15+5=20  ← 增强激发信号
  CH2 = b9=19, b15=15-5=10  ← 减弱反相
  效果: CH1强 + CH2弱 = 净激发
```

### 2.5 verify() — 复扫验证

```python
def verify(self, before_deltas):
    for b9 in sorted(before_deltas.keys()):
        before = before_deltas[b9]
        if abs(before) < 3: continue  # 原本正常的跳过

        resp = self.probe(b9)         # 重新探测
        avg = sum(resp) / 256
        after = round(avg - self.baseline.get(b9, 105), 1)

        diff = abs(before) - abs(after)  # 改善量
        if diff > 0.5:  improved += 1    # |Δ| 减小 = 改善
        if diff < -0.5: worsened += 1    # |Δ| 增大 = 恶化

    rate = improved / (improved+worsened) * 100
    return improved, worsened
```

### 2.6 loop() — 主循环

```
开始
  │
  ├── 检查基线 (无则先校准)
  │
  ▼
┌─────────────────────┐
│  第N轮: scan()      │  扫描 18 频段, 获取所有 Δ
│  → deltas{}         │
└──────┬──────────────┘
       │
       ▼
  ┌─────────────┐
  │ 有异常?       │  统计 |Δ|>4 的项数
  └──┬────────┬──┘
     │Yes     │No
     ▼        ▼
  balance()  "全部平衡 ✓"
  verify()   等待 interval 秒
     │        │
     └───┬────┘
         ▼
    等待 interval 秒 → 下一轮
```

---

## 3. balancer_web.py — Web 仪表盘详解

### 3.1 架构

```
balancer_web.py
├── Balancer 类 (比 dynamic_balancer.py 功能更丰富的引擎)
│   ├── 35频段 B9_RANGE=range(1,36)
│   ├── 四种治疗算法: legacy / yinyang / fusion / schumann
│   ├── A/B 对比: 随机交替 → 自动统计改善率 → 生成报告
│   ├── 耦合度计算: schumann_coupling()
│   ├── 阴阳权重预计算: YINYANG_WEIGHT dict
│   ├── 实时状态: status dict (线程安全 _lock)
│   └── loop() 主循环 + _auto_report() 自动报告
│
├── Web API (HTTP 服务器 :8080)
│   ├── GET / → HTML 仪表盘
│   ├── GET /api/status → JSON 状态
│   ├── GET /start → 启动平衡
│   ├── GET /stop → 停止
│   ├── GET /algo?mode= → 切换算法
│   ├── GET /clear_log → 清空日志
│   └── GET /calibrate → 校准基线
│
└── HTML 仪表盘 (内嵌单页应用)
    ├── 连接状态指示器
    ├── 五行雷达图 (Canvas, 自动聚合)
    ├── 频段偏差列表 (Δ 排序, 带阴阳权重)
    ├── 频谱脊线图 (Pictograph, 舒曼谐波参考线)
    ├── 四维算法对比条 (同频/双频/融合/舒曼)
    ├── 耦合度指标 ∿
    ├── 日志面板 + 复制/清空
    └── 1.5s 轮询更新
```

### 3.2 核心扩展功能

#### 耦合度计算 (schumann_coupling)

```python
def schumann_coupling(responses, baseline):
    """
    计算 35 频段响应向量与舒曼谐波分形衍生模式的匹配度
    返回 0~100 的耦合分数

    两个维度:
    1. 方向一致性: 相邻频段符号翻转率
       - 低翻转率 → 有序 (耦合高)
       - 高翻转率 → 无序/噪声主导 (耦合低)

    2. 振幅一致性: 偏差的标准差 vs 均值 (变异系数)
       - 低变异系数 → 各器官偏差幅值接近 (有序)
       - 高变异系数 → 有些器官偏差巨大 (个体异常)
    """
    signs = [1 if d>0 else -1 for d in diffs]
    flips = count sign changes in signs[]
    order = 1 - flips / max_flips

    cv = std(abs_diffs) / mean(abs_diffs)
    coupling = order * (1-min(cv,1)) * 100 + 20
    return clamp(coupling, 0, 100)
```

#### 阴阳权重预计算

```python
SUN_SEQ  = [1, 2, 4, 8, 16, 32]        # 太阳: 2^n
MOON_SEQ = [1, 1, 2, 3, 5, 8, 13, 21, 34]  # 太阴: Fibonacci

for b9 in 1..35:
    d_sun  = min(|b9 - s| for s in SUN_SEQ)
    d_moon = min(|b9 - m| for m in MOON_SEQ)
    w_sun  = 1/(1+d_sun) / (1/(1+d_sun) + 1/(1+d_moon))
    w_moon = 1/(1+d_moon) / (1/(1+d_sun) + 1/(1+d_moon))
```

**35频段的太阳/太阴归属表 (关键样本):**

| b9 | 频率(MHz) | 最近2^n | 最近fib | 太阳权重 | 太阴权重 | 主导 |
|:--:|------|:--:|:--:|:--:|:--:|:--:|
| 1 | 26 | 1 | 1 | 0.50 | 0.50 | 双 |
| 3 | 37 | 2 | 3 | 0.40 | 0.60 | 太阴 |
| 4 | 44 | 4 | 3 | 0.60 | 0.40 | 太阳 |
| 8 | 88 | 8 | 8 | 0.50 | 0.50 | 双 |
| 13 | 250 | 16 | 13 | 0.43 | 0.57 | 太阴 |
| 16 | 297 | 16 | 13 | 0.67 | 0.33 | 太阳 |
| 19 | 420 | 16 | 21 | 0.20 | 0.80 | 太阴 |
| 21 | 500 | 16 | 21 | 0.17 | 0.83 | 太阴 |
| 32 | 5662 | 32 | 34 | 0.60 | 0.40 | 太阳 |

#### 四维 A/B 对比机制

```
┌─────────────────────────────────────────────┐
│  四维对比调度: algo == "ab"                  │
│                                             │
│  每 4 轮 = 一遍 (batch)                     │
│  ┌──────────┬──────────┬──────────┬───────┐ │
│  │ legacy   │ yinyang  │ fusion   │ schum │ │
│  │ 同频反相  │ ☀☽双频   │ ⚡融合    │ 🌍锚  │ │
│  └──────────┴──────────┴──────────┴───────┘ │
│       ↑ 出场顺序每遍随机 shuffle ↑          │
│                                             │
│  累计统计: ab_legacy/ab_yinyang/ab_fusion/  │
│            ab_schumann.imp/.wors/.rounds    │
│                                             │
│  每算法满 16 轮 → 自动生成对比报告:          │
│    ╔═══ NLS 四维算法对比报告 ═══            │
│    ║ 同频反相: 72.5% (16轮)                │
│    ║ ☀☽双频:   81.3% (16轮)                │
│    ║ ⚡融合:    78.8% (16轮)                │
│    ║ 🌍舒曼锚:  68.2% (16轮)                │
│    ║ 🏆 当前最优: ☀☽双频 (81.3%)           │
│    ╚══════════════════════════════          │
│    → 保存到 algo_compare_report.txt         │
└─────────────────────────────────────────────┘
```

### 3.3 Web 仪表盘界面

```
┌─────────────────────────────────────────────┐
│  🧬 NLS 动态平衡                            │
├─────────────────────────────────────────────┤
│  🟢 手环已连接                               │
│  [▶ 启动平衡] [⏹ 停止] [📐 校准基线]          │
├─────────────────────────────────────────────┤
│  ⚖ 四维对比: △/▽ [☀☽] [同频] [⚡融合] [🌍锚] [🔄四维] │
│  同频 ████████████████████ 72%              │
│  双频 ███████████████████████ 81%           │
│  融合 ██████████████████████ 79%            │
│  锚定 ██████████████████ 68%                │
├─────────────────────────────────────────────┤
│  [五行] [列表] [脊线]      ← Tab 切换         │
│                                             │
│  ☯ 五行雷达图 (Canvas)                       │
│           火                                │
│          /|\                                │
│         / | \                               │
│        /  |  \                              │
│   木 ───  · ─── 土                          │
│        \  |  /                              │
│         \ | /                               │
│          \|/                                │
│         金──水                              │
│  📈 频谱脊线图 (Pictograph)                   │
│  ┌──────────────────────────┐               │
│  │ ↑红=偏盛  ↓蓝=不足        │               │
│  │ ▏舒曼谐波参考线           │               │
│  └──────────────────────────┘               │
├─────────────────────────────────────────────┤
│  📝 日志                                    │
│  [📋复制] [🔄重记]                           │
│  ┌──────────────────────────────────┐       │
│  │ [第42轮] 改善12 / 恶化3 [☀☽双频] │       │
│  │   ✅ 骨/基础 ↓2.1               │       │
│  │   ✅ 肝/代谢 ↓1.8               │       │
│  │   ⚠ 心/血管 ↑0.7               │       │
│  └──────────────────────────────────┘       │
└─────────────────────────────────────────────┘
```

---

## 4. Android APK — USB OTG 通信机制

### 4.1 架构

```
MainActivity.kt                    BalancerEngine.kt
┌───────────────────┐             ┌──────────────────────────┐
│ onCreate()        │             │ openDevice()              │
│   engine = new    │──创建──────▶│   UsbManager.openDevice() │
│   BalancerEngine  │             │   claimInterface(iface)   │
│                   │             │   find BULK OUT endpoint  │
│   connect()       │──调用──────▶│                            │
│                   │             │ configureFtdi()           │
│   startBalance()  │──调用──────▶│   controlTransfer ×3      │
│                   │             │   波特率 115200 配置       │
│   onStatus/       │◀─回调──────│                            │
│   onProgress/     │   (UI线程)  │ sendProbe()               │
│   onLog/onRound   │             │   bulkTransfer(epOut)     │
│                   │             │   ↑ FTDI VCP 模拟串口     │
│   destroy()       │──销毁──────▶│                            │
└───────────────────┘             └──────────────────────────┘
```

### 4.2 USB OTG 连接流程

```
1. 枚举 USB 设备
   UsbManager.deviceList.values
   → 过滤: vendorId == 0x0403 (FTDI)
           productId == 0x6001 (FT232R)

2. 获取权限
   usbManager.requestPermission(device, PendingIntent)
   → BroadcastReceiver 接收 PERMISSION_GRANTED

3. 打开设备
   usbManager.openDevice(device) → UsbDeviceConnection

4. 声明接口 & 找到 BULK OUT 端点
   for interface in device.interfaces:
       connection.claimInterface(interface, force=true)
       for endpoint in interface.endpoints:
           if direction==OUT and type==BULK:
               epOut = endpoint; break

5. 配置 FTDI 芯片为 VCP 模式
   controlTransfer(0x40, 0, 0, 1)     ← Reset
   controlTransfer(0x40, 0, 0, 0)     ← Modem control
   controlTransfer(0x40, 0x03, 0x4138, divisor_buf)
       ↑ 设置波特率 115200 (divisor = 3000000/115200)
```

### 4.3 命令发送

```kotlin
// sendProbe — 核心发送
private fun sendProbe(b9: Int, b11: Int, b15: Int) {
    buf.fill(0)
    buf[9] = b9.toByte()
    buf[11] = b11.toByte()
    buf[13] = b9.toByte()    // ← 注意: Android 版 probe 使用 CH2=b9 (同频)
    buf[15] = b15.toByte()
    connection?.bulkTransfer(epOut, buf, 128, 500)
}
```

### 4.4 Android 版关键差异

| 特性 | 桌面版 (Py) | Android 版 (Kotlin) |
|------|-----------|-------------------|
| 通信库 | pyserial (VCP) | UsbManager (BULK) |
| 基线校准 | 真实扫描 → JSON | **无基线**, 使用随机数模拟 |
| Δ 获取 | 真实 256B 回采 | `Math.random()*20-10` (模拟) |
| 算法 | 仅同频反相 (简洁版) | 四种算法完整实现 |
| 验证 | real re-scan | 无验证 |
| 线程 | 单线程 | CoroutineScope(IO) |
| 地址 | bytearray[128] → serial.write | ByteArray[128] → bulkTransfer |

### 4.5 Android 版残损代码分析

`BalancerEngine.kt` 第 241-252 行存在残损代码:

```kotlin
// 这些行在类定义内部, 但不在任何方法体内
try {
    val zero = ByteArray(128)
    connection?.bulkTransfer(epOut, zero, zero.size, 500)
    Thread.sleep(50)
    connection?.bulkTransfer(epOut, zero, zero.size, 500)
} catch (_: Exception) {}
onProgress?.invoke(0, 0)
onStatus?.invoke("⏹ 已停止")
```

推测该代码本应是 `fun stop()` 方法体的一部分, 但因编辑问题导致结构错乱。

---

## 5. nls_baseline.json — 基线校准详解

### 5.1 基线文件结构

```json
{
  "1": 106.4,     // b9=1 (26MHz): 空扫均值 106.4
  "2": 107.3,     // b9=2 (31MHz): 空扫均值 107.3
  "3": 105.0,     // ...
  "4": 105.0,
  ...
  "35": 105.0     // b9=35 (9.5GHz): 空扫均值 105.0
}
```

**关键观察:** 大部分频段基值 = 105.0, 仅 b9=1 (106.4) 和 b9=2 (107.3) 偏高。这说明:

1. 传感器悬空时, 256 字节响应的均值在 ~105 左右 (噪声地板)
2. b9=1~2 的轻微偏高可能是低频电磁干扰 (26~31MHz 接近调频广播频段)
3. 105 这个值的物理含义: 256 字节的均值, 范围 0~255, 105 对应约 41% 的动态范围

### 5.2 校准流程

```
┌─────────────── calibrate() ───────────────┐
│                                            │
│  1. 提示用户: "传感器悬空，不接触皮肤"       │
│                                            │
│  2. 遍历 B9_RANGE (1~35 或 14~31):        │
│     for b9 in range:                      │
│       probe(b9) → 256B 响应               │
│       raw[b9] = avg(256B)                 │
│                                            │
│  3. 保存到 nls_baseline.json              │
│                                            │
│  4. 后续使用:                              │
│     Δ = 贴手腕 avg(256B) - baseline[b9]   │
│     ↑ 减去环境噪音, 得到纯器官共振信号     │
└────────────────────────────────────────────┘
```

### 5.3 校准的物理原理

```
悬空状态 (校准):
  传感器 ──宽带噪音──▶ 空气 ──无谐振──▶ 传感器
  256B回采 = 纯环境本底噪音 (≈105)

贴手腕状态 (测量):
  传感器 ──宽带噪音──▶ 人体器官 ──选择性共振──▶ 传感器
  256B回采 = 环境噪音 + 器官共振信号

  Δ = 贴手腕 - 悬空 = 器官共振信号 (纯信号!)
```

**类比:** 这就像测光时先测暗场 (盖上镜头盖), 然后开盖测, 减去暗场得到纯信号。这是标准的差谱分析技术。

---

## 6. 同频反相 vs 阴阳双频 — 完整数学推导

### 6.1 同频反相 (Legacy)

#### 物理原理: 相干相消干涉

```
  信号1: A₁·sin(ωt)
  信号2: A₂·sin(ωt + π) = -A₂·sin(ωt)
  叠加场: A₁·sin(ωt) - A₂·sin(ωt) = (A₁-A₂)·sin(ωt)

  当 A₁ ≈ A₂ → 叠加场 ≈ 0 → 完全抵消
  当 A₁ > A₂  → 叠加场 = (A₁-A₂)·sin(ωt) → 残留正向
  当 A₁ < A₂  → 叠加场 = (A₂-A₁)·[-sin(ωt)] → 净反相
```

#### 算法数学表达式

```
给定: b9 (频率), Δ (偏差), wuxing (五行), corr (校正系数)

  adjust = min(|Δ| × 0.5 × corr, 60)

  if Δ > 0 (偏高→抑制):
    b11 = max(3, 15 - adjust)   ← CH1 降幅
    b15 = min(80, 15 + adjust)  ← CH2 升幅
    CH1 净振幅 = 15 - adjust   (弱)
    CH2 净振幅 = 15 + adjust   (强)
    → (弱) - (强) = -adjust × 2 = 净抑制

  if Δ < 0 (偏低→激发):
    b11 = min(80, 15 + adjust)  ← CH1 升幅
    b15 = max(3, 15 - adjust)   ← CH2 降幅
    CH1 净振幅 = 15 + adjust   (强)
    CH2 净振幅 = 15 - adjust   (弱)
    → (强) - (弱) = +adjust × 2 = 净激发
```

#### 数值示例

```
b9=14 (骨骼), Δ=+12.0 (偏高), 五行=土, corr=1.0
  adjust = min(12×0.5×1.0, 60) = 6
  b11 = 15-6 = 9  (CH1 降低 40%)
  b15 = 15+6 = 21 (CH2 提高 40%)
  → 净抑制 = (9-21)×振幅单位 = -12

b9=19 (胃部), Δ=-10.0 (偏低), 五行=土, corr=1.0
  adjust = 5
  b11 = 15+5 = 20 (CH1 升高 33%)
  b15 = 15-5 = 10 (CH2 降低 33%)
  → 净激发 = (20-10)×振幅单位 = +10
```

### 6.2 阴阳双频 (Yinyang)

#### 数学基础

```
数列定义:
  太阳序列: S = {2⁰, 2¹, 2², 2³, 2⁴, 2⁵} = {1, 2, 4, 8, 16, 32}
  太阴序列: M = {F₀, F₁, F₂, ..., F₈} = {1, 1, 2, 3, 5, 8, 13, 21, 34}

距离计算:
  d_sun(b9)  = min(|b9 - s|)  for s in S
  d_moon(b9) = min(|b9 - m|)  for m in M

权重计算 (反距离加权):
  w_sun  = 1/(1+d_sun)  / [1/(1+d_sun)  + 1/(1+d_moon)]
  w_moon = 1/(1+d_moon) / [1/(1+d_sun)  + 1/(1+d_moon)]

  w_sun + w_moon = 1.0 (归一化)
```

#### 算法数学表达式

```
给定: b9, Δ, wuxing, corr

  sun_b9  = argmin_s∈S |b9 - s|  ← 最近太阳谐波索引
  moon_b9 = argmin_m∈M |b9 - m| ← 最近太阴谐波索引

  adjust = min(|Δ| × 0.5 × corr, 60)

  if Δ > 0 (偏高→抑制):
    sun_amp  = max(3, 15 - adjust × w_sun)   ← CH1 太阳通道降幅
    moon_amp = min(80, 15 + adjust × w_moon) ← CH2 太阴通道升幅
  else (偏低→激发):
    sun_amp  = min(80, 15 + adjust × w_sun)   ← CH1 太阳通道升幅
    moon_amp = max(3, 15 - adjust × w_moon)  ← CH2 太阴通道降幅

  命令:
    cmd[9]=sun_b9, cmd[11]=sun_amp     ← CH1: 太阳频率 + 太阳振幅
    cmd[13]=moon_b9, cmd[15]=moon_amp   ← CH2: 太阴频率 + 太阴振幅
```

#### 数值示例

```
b9=14 (骨骼), Δ=+12.0 (偏高), 五行=土, corr=1.0

  d_sun(14): |14-16|=2, |14-8|=6 → min=2
  d_moon(14): |14-13|=1, |14-21|=7 → min=1
  w_sun  = 1/(1+2) / [1/3 + 1/2] = 0.333/0.833 = 0.40
  w_moon = 1/(1+1) / [1/3 + 1/2] = 0.500/0.833 = 0.60

  sun_b9=16 (最近2^n), moon_b9=13 (最近Fibonacci)

  adjust = 6

  sun_amp  = 15 - 6×0.40 = 15-2 = 13  ← 太阳弱抑制
  moon_amp = 15 + 6×0.60 = 15+4 = 19  ← 太阴强反相

  命令: CH1→b9=16(297MHz), amp=13
        CH2→b9=13(208MHz), amp=19

  双频比: 297/208 ≈ 1.428

  vs 同频反相: CH1=b9=14(250MHz)/amp=9, CH2=b9=14(250MHz)/amp=21
              同频比: 1.000 → 纯振幅对抗
```

#### 核心差异: 频率空间 vs 振幅空间

```
同频反相:
  CH1═CH2 ═══b9═══  (同一频率)
  ←──振幅对抗──→
  效果: |A₁-A₂|

阴阳双频:
  CH1────────sun_b9────────  (太阳谐波)
        ╲   差拍   ╱
         ╲  |f₁-f₂|╱
          ╲  ╱
  CH2────moon_b9────  (太阴谐波)
  效果: 谐波共鸣 + 共振牵引
```

### 6.3 融合算法 (Fusion)

```python
def balance_fusion(self, deltas):
    """
    双层控制:
    底层 (反相力): b11/b15 标准反相 → 推力正比于 Δ
    顶层 (数列轨): CH1=太阳b9±2, CH2=太阴b9±2 → 方向沿数学梯度
    """
    b11 = 15 ± adjust  # 同频反相振幅 (归零推力)
    b15 = 15 ∓ adjust

    ch1_b9 = clamp(nearest_sun(b9), b9-2, b9+2)   # 太阳频率微调
    ch2_b9 = clamp(nearest_moon(b9), b9-2, b9+2)   # 太阴频率微调

    # 振幅=同频反相(强), 频率=阴阳双频(微偏移≤±2)
    cmd[9]=ch1_b9, cmd[11]=b11
    cmd[13]=ch2_b9, cmd[15]=b15
```

**物理类比: PID 控制器**
```
  反相力    = P (比例控制: 力∝偏差)
  数列偏移  = D (微分控制: 沿数学梯度方向引导)
  → 不是直线拉回, 而是沿 Fibonacci/2ⁿ 轨道归零
```

### 6.4 舒曼锚定算法 (Schumann)

```python
def balance_schumann(self, deltas):
    """
    只处理 |Δ| 最大的前 3 个频段
    CH1 → 低频段 (b9-8, 范围 1~10), 弱信号 15
    CH2 → 低频段 (sch_b9 + s_idx + 1), 弱信号 15
    目标: 用 7.83Hz 谐波重置生物节律锚点
    """
    sch_b9 = clamp(b9 - 8, 1, 10)           # 映射到低频区
    s_idx = min(4, |Δ|/5)                      # 偏差越大, 谐波越丰富
    CH1 = b9=sch_b9, amp=15 (弱)
    CH2 = b9=sch_b9+s_idx+1, amp=15 (弱)
```

**原理:** 不直接对抗异常频率, 而是发射低频舒曼谐波 (26~220MHz 范围对应 b9=1~10), 类似用低频节奏信号"拖拽"整个系统回同步态。

---

## 7. "降噪耳机"算法类比

### 7.1 类比映射

```
降噪耳机                          同频反相算法
────────                          ──────────
麦克风采样环境噪音              扫描Δ = 实测 - 基线
                       ↕                       ↕
DSP 生成反相波形                 CH1 降/CH2 升
                       ↕                       ↕
扬声器发射反相噪音               CH1/CH2 双通道发射
                       ↕                       ↕
耳道内: 噪音+反噪音≈0             器官: 正偏差+负偏差→归零
```

### 7.2 数学同构

```
降噪耳机:
  噪音 = N(t) = A·sin(ωt)
  反相 = -N(t) = -A·sin(ωt)
  叠加 = N(t) + (-N(t)) = 0  ← 相消干涉

NLS 同频反相:
  偏差信号 (模拟) ≈ Δ(ω)
  CH1 = A₁·sin(ωt)
  CH2 = A₂·sin(ωt + π) = -A₂·sin(ωt)
  当 A₂-A₁ ∝ Δ → 净效应 ∝ -Δ → 归零驱动
```

### 7.3 隐喻的局限

| 降噪耳机 | NLS 手环 | 差异 |
|---------|---------|------|
| 麦克风实时采样 | 扫描 35 频段 (离散) | NLS 是离散频点 |
| 电声换能 (扬声器→耳膜) | 电磁辐射 (天线→人体) | 完全不同的物理机制 |
| 声波叠加在空气中 | ??? 在人体中 | NLS 的"反相"机制未被物理学验证 |
| ANC 延迟 <1ms | 扫描间隔 >80ms | NLS 远慢于实时要求 |

**关键结论:** 降噪耳机的类比提供了一个**直觉桥梁**, 但不能作为科学依据。NLS 建立在"人体器官是宽带噪音的选择性谐振器"这个核心假设上, 该假设源自 Nesterov 的论文, 但尚未被主流生物物理学广泛验证。

---

## 8. 五行校正机制

### 8.1 校正系数定义

```python
WUXING_CORRECTION = {
    "木": 1.0,   # 木行: 标准响应, 无缩放
    "火": 1.5,   # 火行: 衰减快, 需要 50% 增强
    "土": 1.0,   # 土行: 标准响应
    "金": 1.2,   # 金行: 轻微衰减, 20% 增强
    "水": 0.8,   # 水行: 响应敏感, 20% 减弱
}
```

### 8.2 五行与器官映射

```
      火 (13 个器官, corr=1.5)
      ▲
     /|\
    / | \
   /  |  \
 木   |   土
(corr=1.0) (corr=1.0)
  \   |   /
   \  |  /
    \ | /
     \|/
    水──金
  (0.8) (1.2)

五行 → 器官分布:
  木(1.0): 肝/代谢, 超高频A/B/C, 中低频A/B/C → 共 6 个
  火(1.5): 淋巴/免疫, 甲状腺, 心血管, 心脏, 神经系统, 脑/中枢,
           下丘脑, 松果体, 过渡频/A, 极高频 → 共 13 个
  土(1.0): 骨骼/基础, 肌肉/结缔, 胃部, 胰腺/内分泌, 脾脏/血液,
           极低频/基础, 极低频/共振, 极低频/调和 → 共 9 个
  金(1.2): 皮肤/皮毛, 消化/肠道, 肺部/呼吸, 低频A/B/C → 共 6 个
  水(0.8): 肾脏/肾上腺, 超低频A/B/C → 共 4 个
```

### 8.3 校正的物理直觉

```
火行系数 1.5 的原因推测:
  - 火行器官 (免疫、心血管、脑) 代谢活性高, 信号变化快
  - 偏离信号可能在回采中衰减更快 (热弛豫)
  - 需要用更强的反相振幅 (×1.5) 来补偿衰减

水行系数 0.8 的原因推测:
  - 水行器官 (肾、肾上腺) 对信号更敏感
  - 过强的反相可能导致矫枉过正
  - 减速 20% 防止过度震荡
```

### 8.4 校正在实际算法中的应用

```python
# 所有算法中的共同模式:
corr = WUXING_CORRECTION.get(wuxing, 1.0)
adjust = int(abs(delta) * 0.5 * corr)

# 火行示例: Δ=+10, corr=1.5
#   adjust = 10 * 0.5 * 1.5 = 7.5 → 7
#   → 比标准土行的 adjust=5 强 40%

# 水行示例: Δ=+10, corr=0.8
#   adjust = 10 * 0.5 * 0.8 = 4.0 → 4
#   → 比标准土行的 adjust=5 弱 20%
```

---

## 9. 数据流架构图

### 9.1 桌面版 (dynamic_balancer.py) 数据流

```
                 ┌─────────────────┐
                 │  nls_baseline.  │
                 │     json        │
                 │  (悬空校准)      │
                 └───────┬─────────┘
                         │ load
                         ▼
┌──────┐   serial    ┌──────────┐   Δ=avg-baseline   ┌──────────┐
│ COM4 │◄──────────►│   scan() │─────────────────►│ balance()│
│ FTDI │  115200    │ 18频段    │   {b9:delta}      │ 反相治疗  │
└──────┘            └──────────┘                    └────┬─────┘
       ▲                                                │
       │ 128B OUT                                       ▼
       │ b9/b11/b15                          ┌──────────────┐
       │                                     │  verify()     │
       │                                     │  复扫验证      │
       │                                     │  改善/恶化     │
       │                                     └──────┬───────┘
       │                                            │
       │ 256B IN                                    ▼
       └──────────────────────────────  loop() 循环, interval=5s
```

### 9.2 Web 版 (balancer_web.py) 数据流

```
┌────────────────────────────────────────────────────────────┐
│                    Balancer.loop() 后台线程                 │
│                                                            │
│  scan() ──▶ balance() ──▶ verify() ──▶ sleep(3s) ──┐     │
│    35频段      4算法任选      改善/恶化               │     │
│                                  │                    │     │
│    ┌─────────────────────────────┘                    │     │
│    │                                                  │     │
│    ▼                                                  │     │
│  status dict (threading.Lock) ◄───────────────────────┘     │
│  { deltas, verify, log, improved, worsened,                │
│    connected, playing, scanning, round,                    │
│    coupling, algo, ab_legacy, ab_yinyang, ... }            │
└──────────────────────────┬─────────────────────────────────┘
                           │
              ┌────────────▼────────────┐
              │  HTTP :8080             │
              │  /api/status → JSON     │
              │  /start → loop()启动    │
              │  /stop → running=False  │
              │  /calibrate → 悬空基线   │
              │  /algo?mode= → 切换算法  │
              │  / → HTML 仪表盘        │
              └────────────┬────────────┘
                           │
              ┌────────────▼────────────┐
              │  Browser (1.5s polling) │
              │  ┌───────────────────┐  │
              │  │ 五行雷达 (Canvas) │  │
              │  │ 频段列表 (Δ排序)  │  │
              │  │ 脊线图 (频谱)     │  │
              │  │ 四维对比条        │  │
              │  │ 耦合度 ∿         │  │
              │  │ 日志面板          │  │
              │  └───────────────────┘  │
              └─────────────────────────┘
```

### 9.3 Android 版数据流

```
┌──────────────────────────────────────────────────────┐
│  MainActivity.kt (UI 线程)                            │
│  ┌────────────────────────────────────────────────┐  │
│  │  connect() → engine.connect(callback)           │  │
│  │  balanceBtn → engine.startBalance()             │  │
│  │  stopBtn → engine.stop()                       │  │
│  └────────────────────────────────────────────────┘  │
│                      ↕ 回调 (uiHandler.post)          │
│  ┌────────────────────────────────────────────────┐  │
│  │  onStatus / onProgress / onLog / onRound        │  │
│  │  → 更新 UI (statusText, progressBar, log, ...)  │  │
│  └────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────┘
                       │
         ┌─────────────▼─────────────┐
         │  BalancerEngine.kt        │
         │  (CoroutineScope.IO)       │
         │                           │
         │  ┌─────────────────────┐  │
         │  │ USB OTG Setup       │  │
         │  │  UsbManager         │  │
         │  │  claimInterface     │  │
         │  │  find BULK OUT ep   │  │
         │  │  configureFtdi()    │  │
         │  └─────────┬───────────┘  │
         │            │              │
         │  ┌─────────▼───────────┐  │
         │  │ sendProbe(b9,b11,   │  │
         │  │          b15)       │  │
         │  │  buf[9]=b9          │  │
         │  │  buf[11]=b11        │  │
         │  │  buf[13]=b9         │  │
         │  │  buf[15]=b15        │  │
         │  │  bulkTransfer(epOut,│  │
         │  │   buf, 128, 500)    │  │
         │  └─────────────────────┘  │
         └───────────────────────────┘
                       │
         ┌─────────────▼─────────────┐
         │  FT232R (USB OTG)         │
         │  128B OUT → CH1+CH2 信号  │
         │  (无回采! Android 用随机数)│
         └───────────────────────────┘
```

---

## 10. 完整算法逻辑

### 10.1 主循环伪代码

```
ALGORITHM: NLS_Dynamic_Balancer_Loop

INPUT: serial_port, baseline_json, interval_seconds
OUTPUT: 持续平衡经络, 日志输出

1. connect(serial_port)
2. IF baseline_json 不存在:
     calibrate()  // 悬空扫描 35 频段, 保存基线

3. WHILE True:
     round += 1

     // === 第1步: 扫描 ===
     deltas = {}
     FOR b9 in B9_RANGE:
       send_probe(b9, b11=15, b15=15)   // 128B OUT
       wait 80ms
       resp = read 256B IN
       avg = mean(resp)
       deltas[b9] = avg - baseline[b9]  // Δ = 实测 - 基线

     abnormal = {b9: delta | abs(delta) > 4}
     IF abnormal is empty:
       print("全部平衡")
       CONTINUE to next round

     // === 第2步: 确定算法 ===
     IF algo_mode == "ab":
       IF algo_queue is empty:
         algo_queue = shuffle(["legacy","yinyang","fusion","schumann"])
       current_algo = algo_queue.pop()
     ELSE:
       current_algo = algo_mode

     // === 第3步: 治疗 ===
     SORT abnormal by |delta| DESC  // 最异常的先治
     FOR each (b9, delta) in abnormal:
       organ, meridian, wuxing = B9_MAP[b9]
       corr = WUXING_CORRECTION[wuxing]  // 五行校正
       adjust = min(|delta| * 0.5 * corr, 60)

       SWITCH current_algo:
         CASE "legacy":
           treat_legacy(b9, delta, adjust)
         CASE "yinyang":
           treat_yinyang(b9, delta, adjust)
         CASE "fusion":
           treat_fusion(b9, delta, adjust)
         CASE "schumann":
           treat_schumann(b9, delta, adjust)

       wait 100~150ms

     // === 第4步: 验证 ===
     improved = 0; worsened = 0
     FOR each b9 in abnormal:
       send_probe(b9, 15, 15)
       after_delta = mean(256B) - baseline[b9]
       diff = |before_delta| - |after_delta|
       IF diff > 0.5: improved++
       IF diff < -0.5: worsened++

     log(f"第{round}轮: 改善{improved}/恶化{worsened}")

     // === A/B 统计 ===
     IF algo_mode == "ab":
       ab_stats[current_algo].imp += improved
       ab_stats[current_algo].wors += worsened
       ab_stats[current_algo].rounds += 1

       IF all algorithms >= 16 rounds:
         generate_comparison_report()
         reset all ab_stats

     // === 等待下一轮 ===
     wait interval_seconds
```

### 10.2 四种治疗算法对比

```
╔════════════╦══════════════════╦══════════════════╦══════════════════╦══════════════════╗
║            ║  ① Legacy        ║  ② Yinyang       ║  ③ Fusion        ║  ④ Schumann      ║
║            ║  同频反相         ║  ☀☽双频           ║  ⚡融合           ║  🌍舒曼锚定       ║
╠════════════╬══════════════════╬══════════════════╬══════════════════╬══════════════════╣
║ CH1 频率   ║ b9 (原频段)      ║ sun_b9 (最近2ⁿ)  ║ sun_b9 clamp     ║ b9-8 (低频段)    ║
║            ║                  ║                  ║ [b9-2, b9+2]     ║                  ║
╠════════════╬══════════════════╬══════════════════╬══════════════════╬══════════════════╣
║ CH2 频率   ║ b9 (同 CH1)      ║ moon_b9(Fib)     ║ moon_b9 clamp   ║ sch_b9+s_idx+1   ║
║            ║                  ║                  ║ [b9-2, b9+2]     ║                  ║
╠════════════╬══════════════════╬══════════════════╬══════════════════╬══════════════════╣
║ CH1 振幅   ║ 15 ± adjust      ║ 15 ± adj×w_sun   ║ 15 ± adjust     ║ 15 (弱固定)      ║
╠════════════╬══════════════════╬══════════════════╬══════════════════╬══════════════════╣
║ CH2 振幅   ║ 15 ∓ adjust      ║ 15 ∓ adj×w_moon  ║ 15 ∓ adjust     ║ 15 (弱固定)      ║
╠════════════╬══════════════════╬══════════════════╬══════════════════╬══════════════════╣
║ 频率比     ║ 1:1              ║ sun/moon (1.19~  ║ sun/moon (近1:1) ║ sch/(sch+idx)    ║
║            ║                  ║ 1.68)            ║                  ║ (~0.67~0.91)     ║
╠════════════╬══════════════════╬══════════════════╬══════════════════╬══════════════════╣
║ 物理机制   ║ 振幅相消干涉      ║ 双频差拍+谐波共鸣 ║ 反相力+数列牵引   ║ 低频节奏重置      ║
╠════════════╬══════════════════╬══════════════════╬══════════════════╬══════════════════╣
║ 处理范围   ║ 全部异常频段     ║ 全部异常频段     ║ 全部异常频段     ║ Top-3 |Δ|        ║
╠════════════╬══════════════════╬══════════════════╬══════════════════╬══════════════════╣
║ 类比       ║ 降噪耳机         ║ 双音叉共鸣       ║ PID 控制器       ║ 节拍器            ║
╠════════════╬══════════════════╬══════════════════╬══════════════════╬══════════════════╣
║ 代码复杂度 ║ ★☆☆ (15行)      ║ ★★☆ (40行)      ║ ★★★ (50行)      ║ ★☆☆ (20行)      ║
╚════════════╩══════════════════╩══════════════════╩══════════════════╩══════════════════╝
```

### 10.3 完整治疗决策树

```
┌─────────────────────────────────────────────────────────┐
│  scan() → {b9: delta}                                   │
│              │                                          │
│         ┌────▼────┐                                     │
│         │ 有异常?  │  |Δ| > 4                            │
│         └──┬───┬──┘                                     │
│            │No │Yes                                     │
│            ▼   ▼                                        │
│      "全部平衡"  sorted by |Δ| DESC                      │
│                 │                                       │
│         ┌───────▼────────┐                              │
│         │ 算法选择:       │                              │
│         │  ab? → shuffle  │                              │
│         │  单? → 固定     │                              │
│         └───────┬────────┘                              │
│                 │                                       │
│     ┌───────────┼───────────┬───────────┐               │
│     ▼           ▼           ▼           ▼               │
│  legacy     yinyang     fusion     schumann            │
│  同频=同频    CH1≠CH2     反相+偏移    低频锚定           │
│     │           │           │           │               │
│     │    ┌──────▼──────┐    │    ┌──────▼──────┐        │
│     │    │sun_b9 =     │    │    │b9-8→[1,10] │        │
│     │    │ nearest(2ⁿ) │    │    │s_idx=Δ/5   │        │
│     │    │moon_b9 =    │    │    │CH1=sch_b9  │        │
│     │    │ nearest(Fib)│    │    │CH2=sch_b9+ │        │
│     │    │w_sun,w_moon │    │    │   s_idx+1  │        │
│     │    └──────┬──────┘    │    │弱信号 amp=15│       │
│     │           │           │    └─────────────┘       │
│     │    ┌──────▼──────┐    │                          │
│     │    │amp_sun =    │    │                          │
│     │    │  15±adj*w_sun│   │                          │
│     │    │amp_moon =   │    │                          │
│     │    │  15∓adj*w_moon│  │                          │
│     │    └──────┬──────┘    │                          │
│     ▼           ▼           ▼           ▼               │
│     └───────────┴─────┬─────┴───────────┘               │
│                       │                                 │
│              ┌────────▼────────┐                        │
│              │  128B 命令发射   │                        │
│              │  byte[9]=CH1_b9 │                        │
│              │  byte[11]=CH1_amp│                       │
│              │  byte[13]=CH2_b9│                        │
│              │  byte[15]=CH2_amp│                       │
│              └────────┬────────┘                        │
│                       │                                 │
│              ┌────────▼────────┐                        │
│              │  verify() 复扫   │                        │
│              │  improved/wors  │                        │
│              └────────┬────────┘                        │
│                       │                                 │
│              ┌────────▼────────┐                        │
│              │  sleep(interval)│                        │
│              │  下一轮 → scan   │                        │
│              └─────────────────┘                        │
└─────────────────────────────────────────────────────────┘
```

---

## 附录: 文件清单

| 文件 | 行数 | 职责 |
|------|:---:|------|
| `dynamic_balancer.py` | 283 | 桌面版简洁引擎: 18频段, 同频反相, 串口直驱 |
| `balancer_web.py` | 1036 | Web版全功能引擎: 35频段, 4算法, Web仪表盘 |
| `MainActivity.kt` | 101 | Android UI: 按钮/进度/日志 |
| `BalancerEngine.kt` | 252 | Android 引擎: USB OTG, 4算法 (含残损代码) |
| `README.md` | 57 | 项目说明: 理念、实验结果、使用方式 |
| `NLS动态平衡仪_踩坑记录_2026-07-01.md` | 131 | 10个开发坑: FTDI驱动, JS兼容, 端口僵尸 |
| `阴阳数列谐波疗愈_算法设计.md` | 330 | 算法对比白皮书: 两种算法的完整数学推导 |
| `nls_baseline.json` | 37 | 35频段悬空校准基线数据 |
| `启动动态平衡仪.bat` | 11 | Windows 启动脚本 |

---

## 关键发现总结

1. **核心循环**: scan(探测) → balance(反相治疗) → verify(复扫验证) → sleep → 下一轮
2. **双通道协议**: 128字节 OUT 命令, byte[9,11] 控 CH1, byte[13,15] 控 CH2
3. **四种算法**构成进化路径: 同频反相(基础) → 阴阳双频(数学) → 融合(PID控制) → 舒曼锚(节律)
4. **基线校准**是差谱分析的基础: 悬空采环境噪音, 贴手腕得纯信号
5. **五行校正**通过调整振幅系数补偿不同器官的响应特性
6. **Android版缺少回采**: 使用随机数模拟, 本质上是算法演示而非真实测量
7. **科学基础薄弱**: 核心理念基于 Nesterov 的 NLS 理论, 但缺乏主流生物物理学的验证
