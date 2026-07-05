# NLS 动态平衡仪 APK — 完整框架检查清单

## 项目结构对比

| 文件 | nls_cure (✅ 工作) | nls_balance (❌ 闪退) | 差异 |
|------|------|------|------|
| `build.gradle.kts` (root) | AGP 8.2.0, Kotlin 1.9.22 | AGP 8.2.0, Kotlin 1.9.22 | ✅ 一致 |
| `app/build.gradle.kts` | minSdk 26, material 1.12.0, coroutines | minSdk 26, material 1.12.0, coroutines | ✅ 一致 |
| `settings.gradle.kts` | rootProject=NLS-HandRing, jitpack | rootProject=NLS-Balancer, 无 jitpack | ⚠ jitpack 不需要 |
| `gradle.properties` | android.nonTransitiveRClass=true | 缺少此配置 | ⚠ 可能影响 R 类 |
| `AndroidManifest.xml` | theme=Theme.NLS, screenOrientation=portrait | theme=Theme.NLS, screenOrientation=portrait | ✅ 一致 |
| `styles.xml` | parent=Theme.MaterialComponents.DayNight.NoActionBar | parent=Theme.MaterialComponents.DayNight.NoActionBar | ✅ 一致 |
| `activity_main.xml` | ScrollView根, 复杂布局 | LinearLayout根, 自定义widget | ⚠ 需检查组件兼容性 |
| `device_filter.xml` | vendor-id=1027, product-id=24577 | vendor-id=1027, product-id=24577 | ✅ 一致 |

## 崩溃阶段排查

### 阶段1: 安装
- [x] APK 编译成功 (GitHub Actions 通过)
- [ ] 签名正确 (debug 签名，vivo 接受)
- [ ] minSdk 26 兼容 vivo10

### 阶段2: 启动 (pre-onCreate)
- [ ] 主题 Theme.NLS 正确解析
- [ ] 所有 layout 引用的 View 类型存在
- [ ] 无 ClassNotFoundException
- [ ] AndroidManifest 无错误

### 阶段3: onCreate
- [ ] setContentView 成功
- [ ] findViewById 全部成功
- [ ] BalancerEngine(this) 不崩溃
- [ ] engine.initialize() 不崩溃

### 阶段4: UI 交互
- [ ] 按钮点击不崩溃
- [ ] USB 连接流程正常
- [ ] 平衡流程正常

## layout 组件逐一检查

| 组件 | 说明 | Material 兼容？ |
|------|------|:--:|
| ProgressBar (style=horizontal) | 水平进度条 | ⚠ Material 3 中 API 可能不同 |
| Button (backgroundTint) | 着色按钮 | ⚠ Material Button 需要特殊处理 |
| View (纯色圆点) | 状态指示灯 | ✅ |
| ScrollView | 滚动容器 | ✅ |
