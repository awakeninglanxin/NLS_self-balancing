# NLS 动态平衡仪 APK — 完整框架检查清单

## ✅ v7.15 最终版 — 完整功能

### 📋 版本迭代全记录

| 版本 | 修复内容 | 结果 |
|:--:|------|:--:|
| v7.0~7.5 | 初始版本、对齐 nls_cure 结构 | ❌ 闪退 |
| **v7.6** | **Button→TextView + ProgressBar 改用 AppCompat** | **✅ 启动** |
| v7.7 | 真实USB探测(epIn+probe) | ❌ 闪退 |
| v7.8 | 三模式看板+校准(XML中`<view>`声明ChartView) | ❌ 闪退 |
| v7.9 | ChartView @JvmOverloads | ❌ 闪退 |
| v7.10 | ChartView 代码创建(FrameLayout占位) | ❌ 闪退 |
| v7.11 | 回退到v7.6纯净代码 | ✅ 启动 |
| v7.12 | 校准+三模式看板(重试) | ❌ 闪退 |
| **v7.13** | **仅加校准按钮(最小改动基准)** | **✅ 启动** |
| v7.14 | 三模式看板纯代码创建(tabHost占位) | ✅ 启动 |
| **v7.15** | **精简2标签(五行/脊线)+删空白频段区** | **✅ 完整功能** |

---

## 🔥 铁律合集

### 铁律 #66: Material 主题下禁止 Button + backgroundTint
- Theme.MaterialComponents 主题自动将 `<Button>` 替换为 MaterialButton
- MaterialButton 不支持 `android:backgroundTint`，XML inflation 阶段崩溃
- **正确**: 用 `<TextView>` + `background` + `clickable=true` 替代按钮
- **正确**: ProgressBar 风格用 `@style/Widget.AppCompat.ProgressBar.Horizontal`

### 铁律 #66-b: XML 中禁止声明自定义 View
- `<view class="com.nls.selfbalancing.ChartView" />` 在 Material 主题下会导致 ClassNotFoundException
- 即使加上 `@JvmOverloads` 构造器也无效
- **正确**: XML 用 `<LinearLayout>`/`<FrameLayout>` 占位，Kotlin 中 `addView()` 动态挂载

### 铁律 #66-c: 代码改动必须基于已知稳定版本，每次只改一处
- w7.7~7.12 连续闪退的根因：同时改了布局+引擎+ChartView
- v7.13 策略成功：从v7.11(稳定)出发，只加一个 📐 按钮
- v7.14 策略成功：从v7.13 出发，只加 tabHost 占位+程序化创建
- **每次改动后必须在手机上验证不闪退，再继续下一步**

---

## 🏗️ 最终项目结构 (v7.15)

```
apk/
├── build.gradle.kts          ← AGP 8.2.0 + Kotlin 1.9.22
├── settings.gradle.kts
├── gradle.properties          ← nonTransitiveRClass=true
├── gradle/wrapper/
└── app/
    ├── build.gradle.kts       ← minSdk 26, targetSdk 34
    │                           ← material 1.12.0, coroutines 1.7.3
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml    ← theme=@style/Theme.NLS, portrait
        ├── res/
        │   ├── layout/activity_main.xml
        │   │   ├── 全部用 TextView (无 Button)
        │   │   ├── tabHost: 空 LinearLayout 壳 (Kotlin 动态填充)
        │   │   ├── 无 bandsContainer (已删除)
        │   │   └── 状态栏含 📐 校准 按钮
        │   ├── values/styles.xml
        │   │   └── Theme.MaterialComponents.DayNight.NoActionBar
        │   └── xml/device_filter.xml
        └── java/com/nls/selfbalancing/
            ├── MainActivity.kt      ← 程序化创建 tabs + ChartView
            ├── BalancerEngine.kt    ← 18频段 + 5算法 + 校准 + probe
            └── ChartView.kt         ← Canvas 五边形雷达 + 频谱脊线
```

---

## 📱 UI 布局说明 (v7.15)

```
┌─────────────────────────────┐
│ NLS 动态平衡仪 v7.15         │
├─────────────────────────────┤
│ ● 手环已连接   📐校准  连接  │  ← 状态栏
├─────────────────────────────┤
│        第0轮  🌍舒曼锚       │  ← 轮次+算法
│ ████████████████░░░░ 12/18  │  ← 进度条
├─────────────────────────────┤
│  [ ☯ 五行雷达 ] [ 📈 频谱脊线 ] │  ← 2个标签 (代码创建)
│ ┌─────────────────────────┐ │
│ │                         │ │  ← ChartView Canvas
│ │  五边形雷达 / 脊线图      │ │
│ │                         │ │
│ └─────────────────────────┘ │
├─────────────────────────────┤
│ 📋 日志                      │
│ ─── 第1遍: 同频 → ☀☽ → …    │  ← 滚动日志
│ 第1轮 — 扫描中…              │
│   ⚡ 5项异常                 │
│ 松果体 Δ=-3.2 7.37MHz       │
├─────────────────────────────┤
│ [ ▶ 启动平衡 ] [ ⏹ 停止 ]   │  ← TextView模拟按钮
└─────────────────────────────┘
```

---

## ⚙️ 功能模块

### BalancerEngine (平衡引擎)
| 模块 | 说明 |
|------|------|
| 18频段 | b9=14..31, 分频器模型 f=7.3728/2^((b9-14)/2) |
| 5算法 | 同频反相 / ☀☽双频 / ⚡融合 / 🌍舒曼锚 / 💧水团簇 |
| AB轮转 | 5算法随机打乱顺序，每遍4轮=20轮/报告 |
| 校准 | 📐 扫18频段建立基线，差值=raw-baseline |
| probe() | USB IN端点读取256字节，计算平均值 |
| USB | FTDI VID=0x0403 PID=0x6001, Bulk OUT/IN |

### ChartView (图表)
| 模式 | 说明 |
|------|------|
| ☯ 五行雷达 | 五边形雷达图，火土金水木五轴 |
| 📈 频谱脊线 | 18频段柱状图，红↑偏盛 蓝↓不足，左高频右低频 |

---

## 🔧 与 nls_cure 的差异

| 项目 | nls_cure (手环疗愈) | nls_balance v7.15 (动态平衡) |
|------|:--:|:--:|
| AGP | 8.2.0 | 8.2.0 |
| Kotlin | 1.9.22 | 1.9.22 |
| minSdk | 26 | 26 |
| 主题 | MaterialComponents | MaterialComponents |
| 按钮 | 程序中动态创建 | **TextView + clickable** |
| 进度条 | 未使用 | **AppCompat.Horizontal** |
| 自定义View | 无 | **ChartView (代码创建)** |
| 图表 | 无 | **五行雷达 + 频谱脊线** |
| 校准 | 无 | **18频段基线校准** |
