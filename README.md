# StepSim

StepSim is an LSPosed (libxposed API 102) module that simulates running posture data by modifying accelerometer sensor values in target apps.

## Features

- Simulates realistic running accelerometer waveforms (frequency and amplitude scale with pace)
- Adjustable pace: 3'00" – 10'00" /km
- Adjustable fluctuation: 0–100%
- In-app start/stop switch (config synced via root to `/data/local/tmp/`)
- Permission check: Root availability and LSPosed framework status
- MIUI / HyperOS style UI
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
