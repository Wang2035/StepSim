# StepSim 步频模拟

StepSim 是一个 LSPosed（libxposed API 101/102）模块，通过修改目标应用的加速度计传感器数据来模拟跑步姿态。

## 功能

- 模拟真实的跑步加速度计波形（频率和振幅随配速自动缩放）
- 配速可调：3'00" – 10'00" /公里
- 波动幅度可调：0–100%
- 应用内开始/停止开关（配置通过 root 同步到 `/data/local/tmp/`）
- 权限检测：Root 可用性 + LSPosed 框架状态
- 深色模式：浅色 / 深色 / 跟随系统
- 隐蔽：仅在应用层原地修改传感器数值

## 环境要求

- Android 8.1 及以上（API 27+），最高支持 Android 16
- LSPosed 2.x（libxposed API 101 或 102）并已激活 —— 经典版 LSPosed 1.9.x（API 93）因接口不兼容无法使用
- Root（Magisk），用于配置同步
- 在 LSPosed 作用域中勾选目标应用

## 使用方法

1. 安装 `StepSim.apk`
2. 在 LSPosed 中启用模块，并把目标应用加入作用域
3. 打开 StepSim，设置配速和波动幅度
4. 点"开始模拟"（首次会请求 root 授权）
5. 打开目标应用，约 1 秒后模拟生效

## 构建

本项目使用手动构建链（kotlinc → aapt2 → d8 → zipalign → apksigner）：

```bash
bash build.sh
```

产物：`output/StepSim.apk`

## 原理

模块 hook `SystemSensorManager$SensorEventQueue.dispatchSensorEvent` 以及各监听器的 `onSensorChanged`，原地修改加速度计数值。步频由配速推导，波形振幅随速度缩放。

UI 进程与被 hook 的目标进程之间通过 `/data/local/tmp/` 下的文件共享配置（root 写入并 chmod 666），因为在某些设备上跨进程 SharedPreferences 和 ContentProvider 查询不可靠。

## License

MIT

---

# StepSim (English)

StepSim is an LSPosed (libxposed API 101/102) module that simulates running posture data by modifying accelerometer sensor values in target apps.

## Features

- Simulates realistic running accelerometer waveforms (frequency and amplitude scale with pace)
- Adjustable pace: 3'00" – 10'00" /km
- Adjustable fluctuation: 0–100%
- In-app start/stop switch (config synced via root to `/data/local/tmp/`)
- Permission check: Root availability and LSPosed framework status
- Dark mode: light / dark / follow system
- Stealth: modifies sensor values in place at the app layer only

## Requirements

- Android 8.1 or higher (API 27+), up to Android 16
- LSPosed 2.x (libxposed API 101 or 102) activated — classic LSPosed 1.9.x (API 93) is not supported because the modern API was rewritten in API 101
- Root (Magisk) for config synchronization
- Target app added to the module scope in LSPosed

## Usage

1. Install `StepSim.apk`
2. Enable the module in LSPosed and add the target app to the scope
3. Open StepSim, set pace and fluctuation
4. Tap "开始模拟" (grants root when prompted)
5. Open the target app — simulation takes effect within ~1 second

## Build

The project uses a manual build chain (kotlinc → aapt2 → d8 → zipalign → apksigner):

```bash
bash build.sh
```

Output: `output/StepSim.apk`

## How it works

The module hooks `SystemSensorManager$SensorEventQueue.dispatchSensorEvent` and individual listeners' `onSensorChanged` to modify accelerometer values in place. Step frequency is derived from the configured pace, and the waveform amplitude scales with speed.

Config is shared between the UI process and hooked target processes via files in `/data/local/tmp/` written with root and made world-readable, since cross-process preferences and ContentProvider queries are unreliable on some devices.

## License

MIT
