package com.stepsim.xposed

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Minimal step frequency simulator, based on the proven approach
 * from LocationSpoofer's SensorStepHooker.
 *
 * - Hooks registerListener to capture listeners and build handle->type map
 * - Hooks dispatchSensorEvent to modify accelerometer values in-place
 * - Generates synthetic step counter/detector events via Handler
 */
class StepSimModule : XposedModule() {

    companion object {
        private const val TAG = "StepSim"
        private const val PREFS = "stepsim_config"
        private const val CONFIG_URI = "content://com.stepsim.xposed.config/config"

        // 配置缓存：通过 ContentProvider 跨进程读取
        @Volatile
        var cachedEnabled = false
        @Volatile
        var cachedPaceSec = 360
        @Volatile
        var cachedFluctuation = 60

        private val capturedListeners = CopyOnWriteArrayList<CapturedListener>()
        private val handleToTypeMap = ConcurrentHashMap<Int, Int>()
        private val hookedClasses = ConcurrentHashMap<Class<*>, Boolean>()

        private var mockAccelerometer: Sensor? = null
        private var mockStepCounter: Sensor? = null
        private var mockStepDetector: Sensor? = null

        data class CapturedListener(
            val listener: Any,
            val sensor: Sensor?,
            val handler: Handler?,
        )
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        try {
            if (!param.isFirstPackage) return
            val pkg = param.packageName
            if (pkg == "com.stepsim.xposed") return

            // 通过 /data/local/tmp 文件读取配置（跨进程）
            try {
                readConfigViaFile()
            } catch (t: Throwable) {
                Log.e(TAG, "config read failed, using defaults", t)
            }

            // 始终安装 hook，开关在 hook 里判断
            hookSensorSimulation(param.classLoader)
            Log.i(TAG, "hooks installed for $pkg")
        } catch (t: Throwable) {
            Log.e(TAG, "init failed", t)
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
    }

    // ============================================================ core logic

    /**
     * Generate synthetic accelerometer vibration for running.
     * Direct port from LocationSpoofer's applySyntheticVibration,
     * with added fluctuation (amplitude) control.
     *
     * @param paceSec 配速：秒/公里
     * @param fluctuation 0-100, amplitude percentage
     */
    private fun applySyntheticVibration(values: FloatArray, paceSec: Int, fluctuation: Int) {
        // 防御：参数必须在合理范围内，否则用默认值
        val safePace = paceSec.coerceIn(180, 600)
        val safeFluct = fluctuation.coerceIn(0, 100)
        val speedMs = 1000.0 / safePace
        // 步频由配速推导：配速越快步频越高
        val cadence = (150 + speedMs * 12).toInt().coerceIn(95, 200)
        val elapsedRealtime = SystemClock.elapsedRealtime()
        val omega = (cadence / 60.0) * 2 * Math.PI
        val t = (elapsedRealtime % 60000) / 1000.0
        var amp = speedMs * 0.4
        if (amp > 3.0) amp = 3.0
        // 波动系数：0% -> 0.3x, 100% -> 1.5x
        val fluctFactor = 0.3f + (safeFluct / 100f) * 1.2f
        val f = ((amp + 1.5) * fluctFactor).toFloat()
        val phase = omega * t
        values[2] = (Math.sin(phase) * f).toFloat() + 9.8f
        values[1] = 0.35f * f * Math.cos(phase).toFloat()
        values[0] = f * 0.2f * Math.sin(omega * 0.5 * t).toFloat()
    }

    /**
     * Create a SensorEvent via reflection, with Unsafe fallback.
     * Direct port from LocationSpoofer's createSensorEvent.
     */
    private fun createSensorEvent(sensor: Sensor, values: FloatArray): SensorEvent? {
        return try {
            val ctor: Constructor<SensorEvent> =
                SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            ctor.isAccessible = true
            val event = ctor.newInstance(values.size)
            System.arraycopy(values, 0, event.values, 0, values.size)
            setField(event, "sensor", sensor)
            setField(event, "timestamp", SystemClock.elapsedRealtimeNanos())
            setField(event, "accuracy", 3)
            event
        } catch (_: Throwable) {
            try {
                // Fallback: Unsafe.allocateInstance
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
                theUnsafeField.isAccessible = true
                val unsafe = theUnsafeField.get(null)
                val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
                val event = allocateInstance.invoke(unsafe, SensorEvent::class.java) as SensorEvent
                setField(event, "values", values.copyOf())
                setField(event, "sensor", sensor)
                setField(event, "timestamp", SystemClock.elapsedRealtimeNanos())
                setField(event, "accuracy", 3)
                event
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun setField(obj: Any, name: String, value: Any) {
        try {
            val f: Field = obj.javaClass.getDeclaredField(name)
            f.isAccessible = true
            when (value) {
                is Int -> f.setInt(obj, value)
                is Long -> f.setLong(obj, value)
                is Float -> f.setFloat(obj, value)
                else -> f.set(obj, value)
            }
        } catch (_: Throwable) {}
    }

    /**
     * Create a mock Sensor via reflection.
     * Direct port from LocationSpoofer's getOrCreateMockSensor.
     */
    private fun getOrCreateMockSensor(type: Int, classLoader: ClassLoader): Sensor? {
        when (type) {
            19 -> mockStepCounter?.let { return it }
            18 -> mockStepDetector?.let { return it }
            1 -> mockAccelerometer?.let { return it }
        }
        return try {
            val sensorClass = Class.forName("android.hardware.Sensor", false, classLoader)
            val ctor = sensorClass.getDeclaredConstructor()
            ctor.isAccessible = true
            val sensor = ctor.newInstance() as Sensor
            setField(sensor, "mType", type)
            setField(sensor, "mName", when (type) {
                18 -> "Step Detector Sensor"
                19 -> "Step Counter Sensor"
                else -> "Accelerometer Sensor"
            })
            setField(sensor, "mVendor", "Android")
            setField(sensor, "mVersion", 1)
            setField(sensor, "mResolution", 1.0f)
            setField(sensor, "mPower", 0.05f)
            when (type) {
                19 -> mockStepCounter = sensor
                18 -> mockStepDetector = sensor
                1 -> mockAccelerometer = sensor
            }
            sensor
        } catch (_: Throwable) {
            null
        }
    }

    private fun getSensorHandle(sensor: Sensor): Int? {
        return try {
            val f = Sensor::class.java.getDeclaredField("mHandle")
            f.isAccessible = true
            f.getInt(sensor)
        } catch (_: Throwable) {
            null
        }
    }

    // ============================================================ hooks

    /** 通过 /data/local/tmp 文件跨进程读取配置（最直接可靠） */
    private fun readConfigViaFile() {
        try {
            val dir = java.io.File("/data/local/tmp")
            if (!dir.exists()) return
            val enabledFile = java.io.File(dir, "stepsim_enabled")
            if (enabledFile.exists()) {
                cachedEnabled = enabledFile.readText().trim() == "1"
            }
            val paceFile = java.io.File(dir, "stepsim_pace")
            if (paceFile.exists()) {
                cachedPaceSec = paceFile.readText().trim().toIntOrNull()?.coerceIn(180, 600) ?: 360
            }
            val fluctFile = java.io.File(dir, "stepsim_fluct")
            if (fluctFile.exists()) {
                cachedFluctuation = fluctFile.readText().trim().toIntOrNull()?.coerceIn(0, 100) ?: 60
            }
        } catch (_: Throwable) {}
    }

    @Volatile
    private var lastConfigRead = 0L

    /** 带节流的配置刷新（最多1秒一次），供 sensor 线程调用 */
    private fun refreshConfigThrottled() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastConfigRead < 1000) return
        lastConfigRead = now
        readConfigViaFile()
    }

    /** 通过 ActivityThread 获取 Application */
    private fun getApplication(): android.app.Application? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentApp = atClass.getMethod("currentApplication").invoke(null)
            currentApp as? android.app.Application
        } catch (_: Throwable) {
            null
        }
    }

    private fun hookSensorSimulation(classLoader: ClassLoader) {
        // 1. Hook getDefaultSensor to return mock sensors for step counter/detector
        for (clsName in listOf("android.hardware.SensorManager", "android.hardware.SystemSensorManager")) {
            val cls = try {
                Class.forName(clsName, false, classLoader)
            } catch (_: Throwable) { continue }
            for (m in cls.declaredMethods) {
                if (m.name == "getDefaultSensor") {
                    hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val args = chain.args
                            val type = args.getOrNull(0) as? Int
                            val result = chain.proceed(args.toTypedArray())
                            if (result == null && type != null && (type == 18 || type == 19)) {
                                return getOrCreateMockSensor(type, classLoader)
                            }
                            return result
                        }
                    })
                }
            }
        }

        // 2. Hook registerListener to capture listeners and build handle->type map
        val smClass = Class.forName("android.hardware.SensorManager", false, classLoader)
        for (m in smClass.declaredMethods) {
            if (m.name == "registerListener" || m.name == "registerListenerImpl") {
                val pt = m.parameterTypes
                if (pt.isNotEmpty()) {
                    hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            try {
                                val args = chain.args
                                var listenerObj: Any? = null
                                var sensor: Sensor? = null
                                var handler: Handler? = null
                                for (a in args) {
                                    when {
                                        a is SensorEventListener -> listenerObj = a
                                        a is Sensor -> sensor = a
                                        a is Handler -> handler = a
                                        // Also check by interface name for obfuscated listeners
                                        a != null && hasInterface(a.javaClass, "android.hardware.SensorEventListener") -> listenerObj = a
                                    }
                                }
                                if (listenerObj != null && sensor != null) {
                                    val type = sensor.type
                                    if (type == 1 || type == 18 || type == 19) {
                                        // Add to captured list (avoid duplicates)
                                        val exists = capturedListeners.any {
                                            it.listener === listenerObj && it.sensor?.type == type
                                        }
                                        if (!exists) {
                                            capturedListeners.add(CapturedListener(listenerObj, sensor, handler))
                                        }
                                        // Build handle->type map
                                        getSensorHandle(sensor)?.let { h ->
                                            handleToTypeMap[h] = type
                                        }
                                        // Backup: hook onSensorChanged in the listener class
                                        // (in case dispatchSensorEvent hook doesn't work)
                                        hookOnSensorChanged(listenerObj.javaClass)
                                    }
                                }
                            } catch (_: Throwable) {}
                            val args = chain.args
                            return chain.proceed(args.toTypedArray())
                        }
                    })
                }
            }
        }

        // 3. Hook dispatchSensorEvent to modify accelerometer values in-place
        for (clsName in listOf(
            "android.hardware.SystemSensorManager\$SensorEventQueue",
            "android.hardware.SystemSensorManager\$BaseEventQueue"
        )) {
            val cls = try {
                Class.forName(clsName, false, classLoader)
            } catch (_: Throwable) { continue }
            for (m in cls.declaredMethods) {
                if (m.name == "dispatchSensorEvent") {
                    hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            try {
                                refreshConfigThrottled()
                                if (cachedEnabled) {
                                    val args = chain.args
                                    val handle = args.getOrNull(0) as? Int
                                    val values = args.getOrNull(1) as? FloatArray
                                    if (handle != null && values != null) {
                                        val type = handleToTypeMap[handle]
                                        if (type == 1 && values.size >= 3) {
                                            applySyntheticVibration(values, cachedPaceSec, cachedFluctuation)
                                        }
                                    }
                                }
                            } catch (_: Throwable) {}
                            val args = chain.args
                            return chain.proceed(args.toTypedArray())
                        }
                    })
                }
            }
        }

        // 4. Hook onSensorChanged in listener classes (backup for accelerometer)
        // (Done per-listener in registerListener hook via hookOnSensorChanged)
    }

    /**
     * Hook onSensorChanged method in a listener class.
     * Backup in case dispatchSensorEvent hook doesn't work on this Android version.
     */
    private fun hookOnSensorChanged(clazz: Class<*>) {
        // Avoid hooking the same class twice
        if (hookedClasses.putIfAbsent(clazz, true) != null) return
        try {
            for (m in clazz.declaredMethods) {
                if (m.name == "onSensorChanged") {
                    val pt = m.parameterTypes
                    if (pt.size == 1 && pt[0] == SensorEvent::class.java) {
                        try {
                            hook(m).intercept(object : XposedInterface.Hooker {
                                override fun intercept(chain: XposedInterface.Chain): Any? {
                                    try {
                                        refreshConfigThrottled()
                                        if (cachedEnabled) {
                                            val args = chain.args
                                            val event = args.getOrNull(0) as? SensorEvent
                                            if (event != null && event.sensor?.type == 1) {
                                                val values = event.values
                                                if (values.size >= 3) {
                                                    applySyntheticVibration(values, cachedPaceSec, cachedFluctuation)
                                                    try {
                                                        val tsField = SensorEvent::class.java.getDeclaredField("timestamp")
                                                        tsField.isAccessible = true
                                                        tsField.setLong(event, SystemClock.elapsedRealtimeNanos())
                                                    } catch (_: Throwable) {}
                                                }
                                            }
                                        }
                                    } catch (_: Throwable) {}
                                    val args = chain.args
                                    return chain.proceed(args.toTypedArray())
                                }
                            })
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hasInterface(cls: Class<*>, name: String): Boolean {
        var c: Class<*>? = cls
        while (c != null) {
            if (c.interfaces.any { it.name == name }) return true
            c = c.superclass
        }
        return false
    }

    // ============================================================ synthetic event dispatch



}
