# EV 声浪模拟器 (EV Engine Sound Simulator)

把电车（EV）通过**蓝牙 ELM327 OBD** 读到的车辆数据，实时合成出"带燃油车风味、
又带电机科技感"的引擎声浪；可在后台播放，并与音乐共存（不抢焦点、音乐轻微 duck）。

> 平台：**Android**（含小鹏 xPad —— xPad 本质是基于高通骁龙的定制 Android 车机，
> 本工程是标准 Android APK，可直接在 xPad 的 Android 环境运行/上架审核）。
> iOS 无法使用（读不了经典蓝牙 SPP，也装不上 xPad）。

---

## 架构

```
蓝牙 ELM327 ──► OBD 解析层 ──► 虚拟变速箱 ──► Oboe 实时合成引擎 ──► 前台服务/混音输出
 (BLE/SPP)      (PID 010D/0111)  (速度+油门推算       (燃油层+电机层混合      (与音乐并存)
                                     虚拟RPM+换挡)      + 换挡 flare)
```

- **OBD 解析层** `ObdParser.kt` / `ObdFrame.kt`：解析 `010D`(车速) / `0111`(油门) / `015A`+`015B`(加速踏板) / `010C`(转速, 电车多为 0) 的十六进制响应，并累计每个 PID 的可用性。
- **虚拟变速箱** `VirtualGearbox.kt`：EV 没有真实挡位/转速，用 `速度×油门负载 ÷ 传动比` 推算
  **虚拟 RPM**，并按阈值升/降挡；换挡瞬间置 `shifting=true`，触发音频侧的补油 flare（"踢一脚"）。
- **声浪合成引擎** `cpp/engine.cpp`（Oboe 低延迟 C++）：
  - 燃油层：锯齿振荡(基频∝RPM) + 点火脉冲 + 排气共振低通；
  - 电机层：随速上升的正弦 whine + 谐波 + 慢速 LFO 科技感；
  - 混合器：`blend` 滑块在燃油/电机间插值（默认 0.4 燃油 / 0.6 电机）；
  - 换挡 flare 包络；末级 `tanh` 软限幅。
- **前台服务** `AudioService.kt`：持有引擎、常驻通知（mediaPlayback 前台服务类型）、
  `MediaSession` 接管媒体键；Oboe 用 `Shared` 共享流，从而和音乐同时发声。

---

## 构建

需要：**Android Studio (Hedgehog+/AGP 8.2)** + **Android NDK (r25+, 含 CMake 3.22)** + **Oboe 1.9.0**(已通过 prefab 引入)。

```bash
# 用 Android Studio 打开 EVEngineSound/ 目录，Sync 后 Run 到 Android 8.0+(API 24) 设备
# 或命令行（需本地有 sdk+ndk）：
cd EVEngineSound && ./gradlew assembleDebug
```

权限（首次会弹窗请求）：蓝牙扫描/连接（Android 12+ 为 `BLUETOOTH_SCAN/CONNECT`，
旧版本为 `BLUETOOTH`+`ACCESS_FINE_LOCATION`）、前台服务媒体播放、`POST_NOTIFICATIONS`。

---

## 使用

1. 车机/手机蓝牙连上 ELM327 蓝牙款（首次配对）。
2. App 内点「扫描设备」→ 选 ELM327 → 「连接」。
3. 点「启动声浪」开始合成（可切到音乐 App，声浪仍在后台播放）。
4. 滑块：
   - **混合比**：右=电机味重，左=燃油味重；
   - **音量**；
   - **虚拟挡位数**（1–8，越多换挡越频繁）；
   - **升挡 RPM 阈值**（3000–8000）。
   - **优先用加速踏板作负载**（开关，默认开）：关掉则回退到油门/速度估算。
   - **用真实电机转速**（开关，默认开）：关掉则始终用虚拟转速。

---

## 电车 OBD 能读到什么 / 哪些能提升声浪

通过 ELM327 走标准 OBD-II（Mode 01）能拿到的是**通用 PID**，与车厂无关。下表按"对声浪效果的提升价值"排序：

| PID | 含义 | 电车可用性 | 对声浪的价值 |
|---|---|---|---|
| `0x0D` | 车速 km/h | 几乎所有车都有 | **基座**：没有它就无法推算虚拟转速，**必用** |
| `0x5A`/`0x5B` | 加速踏板位置 D / E | 多数 EV 有（比油门更准） | **高**：直接反映"踩多深"，比猜测油门自然得多，作为负载/强度源最佳 |
| `0x11` | 油门位置 | 部分 EV 有，部分恒为 0 | 中：作踏板缺失时的回退负载源 |
| `0x0C` | 发动机/电机转速 | 多数 EV 为 0 / NO DATA；少数暴露真实电机转速 | 中（看车）：若暴露则用真实转速，比虚拟更准；无则忽略 |
| `0x1F` | 点火后运行时间 | 多数有 | 低：可用来判断"车已通电"，做自动启停，不影响音质 |
| `0x42` | 控制模块电压 | 多数有 | 低：与声浪无关 |
| `0x05`/`0x0F` | 水温/进气温度 | 部分有 | 无：EV 无发动机，无意义 |
| 厂商私有 PID | 电机转速/电池 SOC/挡位 P·R·N·D/动能回收 | 车型专属，需逆向 | 高但**非通用**：如能拿到挡位/回收，可让声浪在 P/N 静音、回收时加"电刹音"，但每辆不一样 |

**结论**：对通用方案收益最大的就是 **加速踏板（0x5A/5B）** 与 **真实转速（0x0C）** 两项——它们已做成开关（见下）。厂商私有 PID 收益更高但不通用，留作后续"接 XPeng 官方车辆数据 SDK"时再吃。

---

## 数据来源开关（取不到自动回退）

为应对"部分车油门/转速读不到"的情况，所有数据都是**可降级**的，并在 UI 实时显示当前实际用的源：

- **负载源回退链**：`加速踏板(0x5A/5B)` → `油门(0x11)` → `由车速变化估算`。
  即便完全读不到踏板/油门，声浪仍会随车速起伏（加速度估算负载），不会"哑火"。
- **转速源回退链**：`真实转速(0x0C，若本车暴露且开关开)` → `虚拟转速`。
  读不到转速时无缝切回虚拟 RPM，换挡逻辑照常工作。
- 两个 `Switch` 让用户能**手动关掉某条异常数据源**（例如某车踏板值乱跳时关掉"优先用踏板"）。
- 主界面新增**数据可用状态行**：实时显示 速度/油门/踏板/转速 各自"有/无"，以及当前"负载源/转速源"，连上车一眼就知道本车暴露了哪些数据。

---

## 如何拿到 APK（本工程编译环境说明）

> 当前 AI 运行环境**没有 JDK / Android SDK / Gradle**，无法在此直接产出 `.apk` 二进制。
> 下面给两条不用你自己配环境（或最少配置）就能拿到 APK 的路径。

### 方式一（推荐，零安装）：GitHub Actions 云端自动出包

工程已内置 `.github/workflows/build-apk.yml`，推到 GitHub 后会自动编译并产出 APK 制品：

1. 在 GitHub 新建一个仓库（如 `ev-engine-sound`）。
2. 把 `EVEngineSound/` 整个目录的内容推上去（含 `.github/` 文件夹）。
3. 打开仓库 **Actions** 标签 → 等 `Build Debug APK` 跑完（约 5–10 分钟）。
4. 在任务页面的 **Artifacts** 里下载 `ev-engine-sound-apk`，里面就是 `app-debug.apk`。
5. 把 APK 拷到 Android 手机或 xPad 安装即可（首次装需允许"未知来源"）。

> 也可在仓库页面手动 **Actions → Build Debug APK → Run workflow** 触发。

### 方式二：Android Studio 本机编译

在你自己的电脑装好 **Android Studio（含 SDK + NDK r25）** 后：

```bash
# 用 Android Studio 打开 EVEngineSound/ 目录，Sync 后 Run
# 或命令行（需本地配好 ANDROID_SDK_ROOT 与 ndk）：
cd EVEngineSound && ./gradlew assembleDebug
```

APK 产物在 `app/build/outputs/apk/debug/app-debug.apk`。

---

## 重要说明 / 边界

- **ELM327 UUID 差异（已自动处理）**：不同 ELM327 蓝牙版的 GATT 特征 UUID 不统一。
  代码**不再硬编码**，而是遍历所有服务/特征，按"可写/可通知"能力 + 已知 UUID 加权自动挑选，
  并把发现的 UUID 全部打进日志。若连上却收不到数据，多半是该 dongle 需要经典蓝牙 SPP
  （见下方"日志排查"）。
- **电车 PID 限制**：多数电车只有 `010D`(车速) 稳定可用，`0111`(油门) 部分车型返回 0，
  `010C`(转速) 常为 0/NO DATA。因此声浪核心是**虚拟 RPM**，真实转速仅作可选融合。
- **经典蓝牙 SPP 兜底**：本 MVP 以 BLE 为主。若要支持经典 SPP 款 ELM327，需在
  `BleObdManager` 增加 `BluetoothSocket`(UUID `00001101-...`) 通道（Manifest 已含 `BLUETOOTH_ADMIN`）。
- **未实机验证**：本工程在编写环境下**没有 ELM327 硬件与 xPad/真机**，无法真机调试。
  代码逻辑与编译结构已按可运行标准编写，但声浪手感、PID 可用性、车机音频共存表现
  需你在真机/车机上实测微调（尤其 Oboe 参数与目标车型的 ELM327 UUID）。
- **xPad 上架**：APK 本身可在 xPad 的 Android 环境安装运行；正式上架小鹏应用商店需走
  XPeng 开发者审核流程（商业/合规门槛，非代码问题）。如需更全的车辆信号，建议成为 XPeng
  开发者后接入官方车辆数据 SDK，ELM327 作通用兜底。

---

## 数据没进来？用日志三步排查（v1.2 新增）

主界面新增：
- **实时数值面板**：`速度 / 踏板 / 油门 / 转速` 的实际读数，**读不到显示 `NA`**；
  下方一行显示各 PID 是否曾成功解析（✓/✗）以及当前用的是哪个**负载源 / 转速源**。
- **原始串口日志**：打开"显示原始串口日志(调试)"开关即可看到全部收发内容。

看日志下结论：

| 日志现象 | 含义 | 怎么办 |
|---|---|---|
| 完全没有 `>>` 行 | 没连上/没开始轮询 | 检查 GATT 是否 CONNECTED、`选用写特征` 是否打印 |
| 有 `>>` 但没有任何 `<<` 行 | **写成功但收不到回复** | 十有八九是 dongle 只支持**经典蓝牙 SPP**，不是 BLE → 需补 SPP 通道 |
| `<< NO DATA` | 车**不提供**该 PID | 正常，靠其他 PID + 虚拟转速兜底 |
| `<< 410D..` 但面板仍 `NA` | 收到了但**解析/映射**问题 | 把该行原始响应发我，我改解析 |
| 面板有数值但声浪不变 | 数据 OK，音频侧问题 | 确认已点"启动声浪"，并把混合比滑块往"燃油"侧拉 |

> 交叉验证：可用手机装个免费的 `Car Scanner ELM OBD2` 连同一个 dongle，
> 看它能不能读到 `车速 / 油门 / 转速`——能读到的 PID，本 App 也应能读到；读不到的，就是车不提供。

---

## 目录结构

```
EVEngineSound/
├── build.gradle / settings.gradle      # Gradle / AGP / Kotlin
└── app/
    ├── build.gradle                    # Oboe prefab + NDK/CMake + 前台服务权限
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/xfan/evenginesound/
        │   ├── MainActivity.kt         # UI / 权限 / 扫描连接 / 滑块
        │   ├── BleObdManager.kt       # 蓝牙 ELM327 扫描/连接/收发
        │   ├── ObdParser.kt           # PID 解析
        │   ├── VirtualGearbox.kt      # 虚拟变速箱 + 换挡
        │   ├── AudioEngine.kt         # JNI 桥
        │   ├── AudioService.kt        # 前台服务 + MediaSession
        │   └── EngineState.kt         # 数据类
        ├── cpp/
        │   ├── engine.cpp             # Oboe 实时声浪合成
        │   └── CMakeLists.txt
        └── res/                       # 布局 / 字符串 / 主题 / 通知图标
```
