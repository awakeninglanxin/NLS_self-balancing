# PCAP 喂养指南 — 多用户 NLS 原版指纹系统

## 概述

NLS 动态平衡仪的 🔗原版 算法从真实 NLS 软件的 PCAP 抓包反推而来。每换一个人使用 NLS 软件做疗愈，就会产生一套独特的"疗愈指纹"——同频/异频比例、CH1/CH2 振幅范围、频率偏移偏好等。本系统支持**多人指纹共存**，Web 界面下拉选择即可切换。

## 目录结构

```
nls_dynamic_balancer/
├── pcap_data/                 ← 👈 把 PCAP 抓包放这里
│   ├── 丽梦儿_zly_cure       ← 丽梦儿的疗愈抓包
│   ├── 蔡明琦_nls_cmq2cure   ← 蔡明琦的疗愈抓包
│   ├── 张三_z3_cure          ← 张三的
│   └── 李四_l4_cure          ← 李四的
├── pcap_to_algo.py            ← PCAP 分析引擎
├── algo_fingerprint.py        ← 生成的指纹库（多人）
├── balancer_web.py            ← Web 版动态平衡仪
├── 一键生成全部指纹.bat        ← 一键扫描 pcap_data/ 下所有文件
└── PCAP喂养指南_多用户指纹系统.md  ← 本文档
```

## 第一步：准备 PCAP 抓包文件

### 要求
- **必须是疗愈（cure）阶段的抓包**，不是检测（scan）阶段
- 文件格式：USBPcap 原始格式（pcapng 或 pcap）
- 文件名建议：`人名_标识`（如 `丽梦儿_zly_cure`、`蔡明琦_cmq2`）
- 放在 `pcap_data/` 目录下，支持任意数量

### 如何抓包
1. 安装 [USBPcap](https://desowin.org/usbpcap/)
2. 选择 NLS 手环对应的 USB 设备（FTDI FT232）
3. 启动抓包 → 在 NLS 软件中点击疗愈 → 等待疗愈完成 → 停止抓包
4. 保存为 pcapng 文件

### 如何判断是疗愈还是检测
| 特征 | 检测(scan) | 疗愈(cure) |
|------|:---:|:---:|
| 命令总数 | 数百条 | 数千条 |
| b11/b15 偏离中值 15 | 小（±3 以内） | 大（±50 以上） |
| 同频比例 | 高（80%+） | 低（40-60%） |
| 文件大小 | 小（<500KB） | 大（>1MB） |

## 第二步：生成指纹

双击 `一键生成全部指纹.bat`，自动扫描 `pcap_data/` 下所有文件，生成 `algo_fingerprint.py`。

或手动：
```bash
python pcap_to_algo.py pcap_data/丽梦儿_zly_cure --name 丽梦儿
```

生成的指纹包含 12 维参数：
| 参数 | 含义 |
|------|------|
| `same_pct` | 同频比例（%） |
| `offset_lo/hi` | CH2 频差偏移范围 |
| `ch1_lo/hi` | CH1 振幅范围 |
| `ch2_lo/hi` | CH2 振幅范围 |
| `ch2_ratio` | CH2/CH1 振幅降比 |
| `sym_pct` | 振幅对称率 |
| `burst_avg` | 平均连续同频次数 |
| `ch1_baseline` | CH1 全局振幅均值 |
| `ch1_per_b9` | 每频段 CH1 振幅映射 |

## 第三步：启动 Web 版

```bash
python balancer_web.py
```

浏览器打开 `http://localhost:5052`，在 🔗原版 按钮旁边有**人员下拉菜单**，选择不同人的指纹即可切换原版算法的参数。

## 第四步：同步 APK 版

更新 `algo_fingerprint.py` 后，联系开发者同步更新 APK 的 `treatOriginal()` 函数中的硬编码参数，使手机版与 Web 版使用相同的指纹数据。

## 常见问题

**Q: 不同人的指纹有什么差异？**
不同人的 NLS 使用习惯不同（选择的器官、疗愈顺序），软件会自适应调整频率/振幅策略，体现在指纹的 ch1_per_b9 映射和偏移范围上。

**Q: 换人后需要重新校准吗？**
Web 版切换人员后原版算法参数立即生效，但建议重新校准（baseline 会变化）。

**Q: 可以混合多人的 PCAP 做一个"通用"指纹吗？**
可以，把所有 PCAP 文件放在 `pcap_data/` 下，`一键生成全部指纹.bat` 会自动对每个文件生成独立指纹。如需合并，可手动运行：
```bash
python pcap_to_algo.py pcap_data/* --name 通用 --merge
```
