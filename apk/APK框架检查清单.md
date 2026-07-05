# NLS 动态平衡仪 APK — 完整框架检查清单

## ✅ v7.6 成功启动！闪退根因总结

### 🔥 核心根因：Material Components 主题与原生 Widget 不兼容

**症状**：APK 编译通过但安装后点开即闪退，无任何报错弹窗。
**定位**：崩溃发生在 `setContentView()` 阶段，在 `onCreate()` 执行到 `super.onCreate()` 之后、自定义代码之前。

### 📋 5 层修复追溯

| 版本 | 修复内容 | 结果 |
|:--:|------|:--:|
| v7.0~7.1 | 初始版本 | ❌ 闪退 |
| v7.2 | ApplicationContext + 空安全 View | ❌ 闪退 |
| v7.3 | RECEIVER_NOT_EXPORTED API 兼容 + usb.host required=false | ❌ 闪退 |
| v7.4 | 全局崩溃捕获 | ❌ 闪退（捕获不到，因为崩溃在 Java 层之前） |
| v7.5 | 对齐 nls_cure 项目结构 (AGP/Kotlin/minSdk/Material) | ❌ 闪退 |
| **v7.6** | **Button→TextView + ProgressBar 改用 AppCompat style** | **✅ 成功！** |

### ⚡ 关键教训：Material 主题下的铁律

```
铁律 #66: Android APK 使用 Theme.MaterialComponents 主题时，
          禁止在 XML 中使用 <Button> + backgroundTint，
          必须用 <TextView> + background + clickable 替代。
          
          禁止使用 ?android:attr/progressBarStyleHorizontal，
          必须使用 @style/Widget.AppCompat.ProgressBar.Horizontal。
```

**原理**：Material Components 主题会自动将 `<Button>` 替换为 `MaterialButton`(Java 类)，
该类不支持直接设置 `backgroundTint`，在 XML 解析(inflation)阶段抛出 RuntimeException，
此时 Activity 尚未创建，全局崩溃捕获器无法拦截。

### 📐 正确做法

```xml
<!-- ❌ 错误：Material 主题下会崩溃 -->
<Button
    android:backgroundTint="#00aa55"
    android:text="启动" />

<!-- ✅ 正确：TextView 模拟按钮 -->
<TextView
    android:background="#00aa55"
    android:text="启动"
    android:clickable="true"
    android:focusable="true"
    android:gravity="center" />

<!-- ❌ 错误 -->
<ProgressBar
    style="?android:attr/progressBarStyleHorizontal" />

<!-- ✅ 正确 -->
<ProgressBar
    style="@style/Widget.AppCompat.ProgressBar.Horizontal" />
```

### 🏗️ 最终项目结构 (nls_balance v7.6, 正常工作)

```
apk/
├── build.gradle.kts          ← AGP 8.2.0 + Kotlin 1.9.22
├── settings.gradle.kts
├── gradle.properties          ← nonTransitiveRClass=true
├── gradle/wrapper/
└── app/
    ├── build.gradle.kts       ← minSdk 26, material 1.12.0
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml    ← theme=Theme.NLS, portrait
        ├── res/
        │   ├── layout/activity_main.xml  ← TextView替代Button
        │   ├── values/styles.xml         ← Theme.MaterialComponents.DayNight.NoActionBar
        │   └── xml/device_filter.xml
        └── java/com/nls/selfbalancing/
            ├── MainActivity.kt      ← lateinit var + onCreate 内初始化
            └── BalancerEngine.kt    ← 18频段 + 5算法 + USB OTG
```

### 🎯 与 nls_cure (正常工作) 的最终差异

| 项目 | nls_cure | nls_balance v7.6 |
|------|:--:|:--:|
| AGP | 8.2.0 | 8.2.0 |
| Kotlin | 1.9.22 | 1.9.22 |
| minSdk | 26 | 26 |
| 主题 | MaterialComponents | MaterialComponents |
| 按钮 | 程序中动态创建 | **TextView + clickable** |
| 进度条 | 未使用 | **AppCompat.Horizontal** |
