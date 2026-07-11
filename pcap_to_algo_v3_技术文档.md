# pcap_to_algo.py v3 — PCAP协议逆向技术文档

> 三通道 (b9/b11/b13/b15) + 完整时序 + 多用户指纹 | 2026-07-11

---

## 1. 概述

`pcap_to_algo.py` 是 NLS 动态平衡仪的原版疗愈算法引擎。它从 USBPcap 抓包文件中逆向提取真实 NLS 软件的疗愈协议指纹，生成 `algo_fingerprint.py` 供 Web 版和 APK 版调用。

### 1.1 工作流

```
PCAP抓包 → parse_cure_pcap() → analyze_cure() → generate_module() → algo_fingerprint.py
                                               ↓
                                        ALL_FINGERPRINTS 多人指纹库
```

### 1.2 版本历史

| 版本 | 日期 | 新增 |
|------|------|------|
| v1 | 2026-07-03 | 基础 12 维指纹，单人 |
| v2 | 2026-07-10 | 批量扫描 pcap_data/，多人指纹共存 |
| **v3** | **2026-07-11** | **b13 通道 + b9→b13 偏移矩阵 + 完整时序 + 参考/疗愈分离** |

---

## 2. PCAP 解析层: `parse_cure_pcap()`

### 2.1 USBPcap 格式

```
[0..23]  PCAP Global Header
[24..]   重复: [Timestamp(8B) | CapLen(4B) | OrigLen(4B)] [Packet Data]
```

### 2.2 128B OUT 命令结构

NLS 手环通信中，每次疗愈/检测指令为 **128 字节 Bulk OUT** 包：

| 字节偏移 | 字段 | 含义 | 范围 |
|:---:|------|------|:---:|
| 9 | b9 | **CH1 频率索引** | 1~35 |
| 11 | b11 | **CH1 振幅** | 3~172 |
| 13 | b13 | **CH2 频率索引** | 1~35 |
| 15 | b15 | **CH2 振幅** | 3~172 |

**频率公式**：$f_{\text{CH1}} = 7.3728 / 2^{(b9-14)/2}$ MHz

### 2.3 过滤逻辑

```python
hlen in (27, 28)    # 仅 USB Bulk 传输
dl == 128             # 仅 128 字节命令
1 <= b9 <= 35         # 有效 b9 范围
```

每条命令附带 **微秒级时间戳** ($t = t_{\text{sec}} + t_{\text{usec}} / 10^6$)。

---

## 3. 指纹分析层: `analyze_cure()`

### 3.1 治疗/参考命令分离

```python
treat_cmds = [c for c in cmds if abs(c['b11']-15) > 5 or abs(c['b15']-15) > 5]
ref_cmds   = [c for c in cmds if abs(c['b11']-15) <= 5 and abs(c['b15']-15) <= 5]
```

**判别原理**：参考/心跳命令的振幅固定在 15±5 范围内（b11≈15，b15≈15）。疗愈命令的振幅会显著偏离 15。

**蔡**案例**：7064 条总命令中，3912 条为治疗，3152 条为参考（其中 b9=29 占 2397 条，91.2% 是参考脉冲）。

### 3.2 三通道指纹字段

#### CH1 通道 (b9/b11)

| 字段 | 类型 | 说明 |
|------|:---:|------|
| `b9_lo/hi` | int | b9 范围 |
| `ch1_lo/hi` | int | CH1 振幅范围 |
| `ch1_baseline` | float | 全局 CH1 振幅均值 |
| `ch1_per_b9` | dict | 每个 b9 值的平均 CH1 振幅 |

#### CH2 通道 (b13/b15) — v3 新增

| 字段 | 类型 | 说明 |
|------|:---:|------|
| `b13_lo/hi` | int | b13 范围 |
| `b13_top` | dict | b13 出现频次 Top5 |
| `ch2_lo/hi` | int | CH2 振幅范围 |
| `ch2_per_b13` | dict | 每个 b13 值的平均 CH2 振幅 |
| `ch2_ratio` | float | CH2/CH1 振幅降比 |

#### 双频耦合 (b9→b13) — v3 新增

| 字段 | 类型 | 说明 |
|------|:---:|------|
| `same_pct` | int | 同频比例 (b9==b13) |
| `offset_lo/hi` | int | b13-b9 偏移范围 |
| `b9_to_b13_pct` | dict[dict] | 每个 b9 → 各 b13 的百分比分布 |

**关键发现**：b9=29→b13=26 占 45.7%，b9=31→b13=26 占 79.8%。b26 是隐藏的疗愈锚点频率。

#### 时序 — v3 新增

| 字段 | 类型 | 说明 |
|------|:---:|------|
| `duration_min` | float | 会话总时长 (分钟) |
| `interval_avg` | float | 平均命令间隔 (秒) |
| `interval_p50` | float | 中位间隔 |
| `interval_min/max` | float | 最小/最大间隔 |
| `n_fast` | int | 间隔 < 0.5s (burst 内) |
| `n_pause` | int | 间隔 > 5s (器官切换) |
| `burst_max` | int | 最长连续同频 (仅治疗命令) |
| `burst_avg` | float | 平均连续同频次数 |

**蔡**时序特征**：149.7 分钟，平均 1.27s/条，中位 0.655s，最小 0.494s（NLS 硬件极限，符合铁律 65 安全下限 ≥0.08s）。

#### 命令统计

| 字段 | 蔡** | 说明 |
|------|:---:|------|
| `total_all` | 7064 | 全部命令 |
| `total_treat` | 3912 | 治疗命令 |
| `total_ref` | 3152 | 参考/心跳命令 |

---

## 4. 输出: `algo_fingerprint.py`

### 4.1 数据结构

```python
ALL_FINGERPRINTS = {
    "丽梦儿": { ... },  # v2 兼容格式
    "蔡**": {          # v3 完整格式
        "total_all": 7064,
        "total_treat": 3912,
        "total_ref": 3152,
        "b13_top": {"28": 1764, "26": 768, "27": 292},
        "b9_to_b13_pct": {
            "29": {"26": 45.7, "28": 8.2, "30": 6.9},
            "31": {"26": 79.8, "25": 2.3, "16": 1.9},
            ...
        },
        "timing": {"duration_min": 149.7, "interval_avg": 1.272, "burst_max": 15, ...},
        ...
    }
}
FINGERPRINT = ALL_FINGERPRINTS.get('蔡**', {})  # 默认用户
```

### 4.2 Web 版使用

```python
from algo_fingerprint import ALL_FINGERPRINTS, FINGERPRINT
PERSONS = list(ALL_FINGERPRINTS.keys())  # ["丽梦儿", "蔡**"]
CURRENT_PERSON = PERSONS[0] if PERSONS else "默认"
```

### 4.3 APK 版同步

`algo_fingerprint.py` 更新后，需同步更新 APK 的 `BalancerEngine.kt` 中的 `treatOriginal()` 函数硬编码参数。

---

## 5. 用户可调时序参数 (Web + APK)

| 参数 | 滑条范围 | Step | 默认值 | 来源 |
|------|:---:|:---:|:---:|------|
| ⏱ 最小间隔 | 0.10~1.00s | 0.01s | **0.49s** | PCAP 实测最短间隔 |
| ⏸ 长暂停阈值 | 0.1~6.0s | 0.1s | **1.0s** | PCAP 中位间隔 × 1.5 |
| 🔁 最长 Burst | 1~12 | 1 | **8** | PCAP burst_max=15 的安全余量 |
| 🔊 功率偏移 | +0~+5 | 1 | **0** | 全局叠加 |

这些参数从 PCAP 实测数据中提取默认值，用户在 Web/APK 界面可通过滑条实时调节。

---

## 6. 新增 PCAP 的流程

```bash
# 1. 将 PCAP 文件放入 pcap_data/
cp cmq_cure7.10 pcap_data/蔡**_nls_cmq2cure

# 2. 运行解析 (追加模式，保留已有指纹)
python pcap_to_algo.py pcap_data/蔡**_nls_cmq2cure --name 蔡**

# 3. 重启 Web 版生效
python balancer_web.py
```

---

## 7. 文件清单

| 文件 | 说明 |
|------|------|
| `pcap_to_algo.py` | PCAP 解析 + 指纹生成引擎 (v3) |
| `algo_fingerprint.py` | 生成的多人指纹库 |
| `pcap_data/` | PCAP 源文件存放目录 |
| `balancer_web.py` | Web 版动态平衡仪 (含 v3 扩展) |
| `PCAP喂养指南_多用户指纹系统.md` | 用户操作指南 (v2) |
| **本文档** | 技术原理与数据结构 (v3) |
