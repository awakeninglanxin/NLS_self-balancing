# NLS 原版算法：人物替换教程

> **一句话**：网页里那个 `🔗原版` 按钮，背后是一组从某人疗愈 PCAP 提取的 12 维参数。换一个人的 PCAP，原版算法就"变成"那个人。

---

## 1. 背景：原版算法的本质

`balancer_web.py` 里的 `balance_original_hybrid()` 不是凭空写的——它是从蔡明琦的疗愈抓包文件（`nls_cmq2cure`，3,505 条 USB 命令）逆推出来的。

```
原版 NLS 软件 → 写 USB 命令 → USBPcap 抓包 → PCAP 文件
                                              ↓
                                   pcap_to_algo.py 提取 12 维指纹
                                              ↓
                                   balance_original_hybrid()
```

**换 PCAP 文件 = 换原版背后的"人"**。

| 人物 | PCAP 文件 | 疗愈命令数 | 同频:异频 | 特点 |
|------|-----------|:--:|:--:|------|
| 蔡明琦 | `我的pdf/明锜/nls_cmq2cure` | 3,505 | 47:53 | 当前默认，皮肤(b9=20) CH1=97.9 高推力 |
| 丽梦儿 | `我的pdf/丽梦儿/zly_cure` | 5,480 | 45:55 | 命令更多，比例相似 |
| 你的 | 由原版软件对你的 XCH 报告执行疗愈时抓取 | — | — | 需要你再次抓包 |

---

## 2. 工具：pcap_to_algo.py

位置：`D:\AAA我的文件\nls_dynamic_balancer\pcap_to_algo.py`

### 2.1 三个命令

```bash
# ① 提取单份 PCAP 的 12 维指纹（纯阅读）
python pcap_to_algo.py "D:\...\某人的_cure"

# ② 对比两份 PCAP 的差异
python pcap_to_algo.py "D:\...\蔡的_cure" --diff "D:\...\丽梦儿的_cure"

# ③ 从 PCAP 生成可替换的算法代码
python pcap_to_algo.py "D:\...\某人的_cure" "D:\...\输出文件.py"
```

### 2.2 12 维指纹详解

每份 PCAP 提取以下四组共 12 维参数：

```
══ CH1（推力通道）══
CH1 平均振幅    — 该人物原版对 CH1 的典型振幅值
CH1 振幅范围    — 最小值 ~ 最大值
CH1 主导率      — CH1 振幅 > CH2 的比例
按b9的CH1均值   — 不同频段的振幅偏好（如 b9=21 心血管推力特别大）

══ CH2（拉力通道）══
CH2 平均振幅    — CH2 典型振幅
CH2 振幅范围    — 最小值 ~ 最大值
异频偏移        — CH2 频率偏移步数（正 = 比 CH1 高频）
CH2 降比        — 异频时 CH2 振幅是 CH1 的几分之一

══ 交互维度 ══
同频比例        — CH1=CH2 的概率（vs CH1≠CH2）
对称反相率      — 同频时振幅是否镜像（b11-15 ≈ 15-b15）
平均 burst      — 同一器官连续发几条命令

══ 全局 ══
频段覆盖        — 最小 b9 ~ 最大 b9
频段数          — 用了几个不同的 b9
被治疗器官排名  — 哪些器官治疗次数最多
```

---

## 3. 两步替换流程（模块化，零手动编辑）

### 架构说明

```
pcap_to_algo.py → 生成 algo_fingerprint.py（纯数据模块, 12维字典）
                                ↓
balancer_web.py  → from algo_fingerprint import FINGERPRINT → 自动加载
```

**不再需要手动复制粘贴函数！** `algo_fingerprint.py` 是独立的数据文件，`balancer_web.py` import 它。换人只需要重新生成这个文件。

### 第一步：生成新指纹

```bash
cd "D:\AAA我的文件\nls_dynamic_balancer"

# 丽梦儿版
python pcap_to_algo.py "D:\AAA我的文件\中健国康 NLS细胞检测\我的pdf\丽梦儿\zly_cure" --name 丽梦儿

# 蔡明琦版（默认）
python pcap_to_algo.py "D:\AAA我的文件\中健国康 NLS细胞检测\我的pdf\明锜\nls_cmq2cure" --name 蔡明琦
```

输出：
```
✅ 指纹模块已更新: algo_fingerprint.py
   👤 丽梦儿 / zly_cure (4187条)
📊 12维指纹:
   ══ CH1 ══  均值71.9 ...
   ══ CH2 ══  降比3.39 ...
   b9=28: CH1=122.0 ████████████████████████
📋 重启 balancer_web.py 即可生效。
```

### 第二步：重启

关掉正在运行的 `balancer_web.py`，重新启动。`🔗原版` 按钮自动使用新指纹。
```

输出提示：
```
✅ 算法代码已保存: D:\...\zly_algo.py
📊 PCAP 指纹: zly_cure
   疗愈命令: 4187条
   同频: 45% / 异频: 55%
   CH1平均振幅: 71.9
   ...
```

### 安全原理

`balancer_web.py` 只 import 一个纯数据字典，不执行任何 PCAP 生成的代码。即使 `algo_fingerprint.py` 损坏，`FINGERPRINT.get("same_pct", 50)` 的默认值能保证算法不崩溃。

---

## 6. 快捷命令速查

---

## 6. 快捷命令速查

```bash
# 切换到丽梦儿版
python pcap_to_algo.py "D:\...\丽梦儿\zly_cure" --name 丽梦儿

# 切换到蔡明琦版（默认）
python pcap_to_algo.py "D:\...\明锜\nls_cmq2cure" --name 蔡明琦

# 对比两人差异
python pcap_to_algo.py "D:\...\明锜\nls_cmq2cure" --diff "D:\...\丽梦儿\zly_cure"
```

每次运行后，**重启** `balancer_web.py` 即可。不需要编辑任何代码。

---

## 附录：文件地图

```
D:\AAA我的文件\nls_dynamic_balancer\
├── balancer_web.py          ← 主程序（import algo_fingerprint，无需手动改）
├── algo_fingerprint.py      ← 指纹模块（pcap_to_algo.py 自动生成，纯数据）
├── pcap_to_algo.py          ← 转换工具（输入PCAP，输出 algo_fingerprint.py）
├── nls_phase.json           ← 相位校准数据
└── nls_baseline.json        ← 基线校准数据
```
