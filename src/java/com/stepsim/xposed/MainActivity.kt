package com.stepsim.xposed

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * MIUI/HyperOS 风格 UI。
 * - 浅色背景，白色圆角卡片
 * - 以速度（km/h）为主要调节项，不显示步频
 * - 波动幅度调节（波形起伏强度）
 */
class MainActivity : Activity() {

    private lateinit var prefs: android.content.SharedPreferences

    // 主题模式：0=浅色 1=深色 2=跟随系统（默认）
    private var dark = false
    private val bgColor get() = if (dark) "#000000" else "#F5F5F7"
    private val cardColor get() = if (dark) "#1C1C1E" else "#FFFFFF"
    private val accentBlue = "#0D84FF"
    private val titleColor get() = if (dark) "#FFFFFF" else "#1A1A1A"
    private val subtitleColor get() = if (dark) "#98989D" else "#8E8E93"
    private val valueColor = "#0D84FF"

    private fun themeMode(): Int = prefs.getInt("theme_mode", 2)

    private fun isDarkEffective(): Boolean = when (themeMode()) {
        0 -> false
        1 -> true
        else -> (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun themeLabel(): String = when (themeMode()) {
        0 -> "浅色"
        1 -> "深色"
        else -> "跟随系统"
    }

    private fun applyTheme() {
        dark = isDarkEffective()
        window.statusBarColor = Color.parseColor(bgColor)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            if (dark) 0 else android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }

    private fun rebuild() {
        applyTheme()
        setContentView(buildUi())
        refresh()
        runChecks()
    }

    private lateinit var speedValue: TextView
    private lateinit var speedBar: SeekBar
    private lateinit var fluctValue: TextView
    private lateinit var fluctBar: SeekBar
    private lateinit var toggleBtn: TextView
    private lateinit var statusDot: TextView
    private lateinit var statusText: TextView
    private lateinit var lspStatus: TextView
    private lateinit var rootStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("stepsim_config", Context.MODE_PRIVATE)
        rebuild()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundedBg(color: String, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = roundedBg(cardColor, 16)
        // 轻微阴影用 elevation（API 21+）
        elevation = dp(2).toFloat()
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor(subtitleColor))
        textSize = 13f
    }

    /** 状态栏高度（解决顶部与系统状态栏重合） */
    private fun statusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else dp(24)
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 顶部按状态栏高度留白
            setPadding(dp(16), statusBarHeight() + dp(12), dp(16), dp(16))
            setBackgroundColor(Color.parseColor(bgColor))
        }

        // ---- 标题行：左侧标题，右上角主题切换 ----
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = "步频模拟"
            setTextColor(Color.parseColor(titleColor))
            textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        titleCol.addView(TextView(this).apply {
            text = "为运动软件注入跑步姿态传感器数据"
            setTextColor(Color.parseColor(subtitleColor))
            textSize = 14f
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })
        titleRow.addView(titleCol, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val themeBtn = TextView(this).apply {
            text = themeLabel()
            textSize = 13f
            setTextColor(Color.parseColor(titleColor))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundedBg(cardColor, 14)
            elevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { v ->
                val popup = android.widget.PopupMenu(this@MainActivity, v)
                popup.menu.add(0, 0, 0, "浅色模式")
                popup.menu.add(0, 1, 1, "深色模式")
                popup.menu.add(0, 2, 2, "跟随系统")
                popup.setOnMenuItemClickListener { item ->
                    prefs.edit().putInt("theme_mode", item.itemId).apply()
                    rebuild()
                    true
                }
                popup.show()
            }
        }
        titleRow.addView(themeBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })
        root.addView(titleRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(20) })

        // ---- 状态卡 ----
        val statusCard = card()
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statusDot = TextView(this).apply { text = "●"; textSize = 18f }
        statusRow.addView(statusDot)
        statusText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor(titleColor))
            setPadding(dp(8), 0, 0, 0)
        }
        statusRow.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusCard.addView(statusRow)
        statusCard.addView(TextView(this).apply {
            text = "需在 LSPosed 作用域中勾选目标应用，开启后重启目标应用生效"
            setTextColor(Color.parseColor(subtitleColor))
            textSize = 12f
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        root.addView(statusCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(16) })

        // ---- 权限检测卡（root + LSPosed） ----
        val checkCard = card()
        checkCard.addView(sectionTitle("权限检测"))
        lspStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor(titleColor))
        }
        checkCard.addView(lspStatus, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        rootStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor(titleColor))
        }
        checkCard.addView(rootStatus, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })
        val recheckBtn = TextView(this).apply {
            text = "重新检测"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(accentBlue))
            setPadding(0, dp(10), 0, dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener { runChecks() }
        }
        checkCard.addView(recheckBtn)
        root.addView(checkCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(16) })

        // ---- 速度卡（配速） ----
        val speedCard = card()
        speedCard.addView(sectionTitle("配速"))
        val speedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        speedValue = TextView(this).apply {
            textSize = 32f
            setTextColor(Color.parseColor(valueColor))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        speedRow.addView(speedValue)
        speedRow.addView(TextView(this).apply {
            text = "/公里"
            setTextColor(Color.parseColor(subtitleColor))
            textSize = 16f
            gravity = Gravity.BOTTOM
            setPadding(dp(4), 0, 0, dp(4))
        })
        speedCard.addView(speedRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4); bottomMargin = dp(8) })
        speedBar = SeekBar(this).apply {
            max = 420  // 180s(3'00") ~ 600s(10'00")，步进1秒
            setOnSeekBarChangeListener(simpleListener {
                val paceSec = 600 - it  // 反向：左慢右快
                prefs.edit().putInt("pace_sec", paceSec).apply()
                refresh()
            })
        }
        speedCard.addView(speedBar)
        val speedHint = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        speedHint.addView(TextView(this).apply {
            text = "10'00\""; setTextColor(Color.parseColor(subtitleColor)); textSize = 12f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        speedHint.addView(TextView(this).apply {
            text = "3'00\""; setTextColor(Color.parseColor(subtitleColor)); textSize = 12f; gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        speedCard.addView(speedHint)
        root.addView(speedCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(16) })

        // ---- 波动卡 ----
        val fluctCard = card()
        fluctCard.addView(sectionTitle("波动幅度"))
        fluctCard.addView(TextView(this).apply {
            text = "波形起伏强度，越高越像真实跑步，越低越平稳隐蔽"
            setTextColor(Color.parseColor(subtitleColor))
            textSize = 12f
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4); bottomMargin = dp(8) })
        val fluctRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fluctValue = TextView(this).apply {
            textSize = 24f
            setTextColor(Color.parseColor(titleColor))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        fluctRow.addView(fluctValue)
        fluctRow.addView(TextView(this).apply {
            text = " %"
            setTextColor(Color.parseColor(subtitleColor))
            textSize = 14f
            gravity = Gravity.BOTTOM
            setPadding(dp(2), 0, 0, dp(2))
        })
        fluctCard.addView(fluctRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) })
        fluctBar = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(simpleListener {
                prefs.edit().putInt("fluctuation", it).apply()
                refresh()
            })
        }
        fluctCard.addView(fluctBar)
        root.addView(fluctCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(24) })

        // ---- 开关按钮（MIUI 风格大圆角） ----
        toggleBtn = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(16), 0, dp(16))
            background = roundedBg(accentBlue, 28)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val next = !prefs.getBoolean("enabled", false)
                prefs.edit().putBoolean("enabled", next).commit()
                refresh()
            }
        }
        root.addView(toggleBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // 包一层 ScrollView 防止小屏溢出
        val scroll = android.widget.ScrollView(this).apply {
            addView(root)
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bgColor))
            addView(scroll)
        }
        return outer
    }

    private fun simpleListener(onChange: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }

    /** 权限检测：root + LSPosed 框架状态（通过 root 查，不需要勾选本体） */
    private fun runChecks() {
        val rooted = checkRoot()
        val okColor = "#34C759"
        val badColor = "#FF3B30"

        // root 状态
        rootStatus.text = if (rooted) "● Root 权限可用" else "● Root 不可用（配置同步需要 root，请先安装 Magisk 并授权）"
        rootStatus.setTextColor(Color.parseColor(if (rooted) okColor else badColor))

        // LSPosed 状态
        val lspState = checkLsposed()
        lspStatus.text = when (lspState) {
            0 -> "● LSPosed 框架运行中"
            1 -> "● LSPosed 框架未检测到（需 2.x / API 101+，请确认已安装并激活）"
            else -> "● 需要 Root 权限才能检测 LSPosed 状态"
        }
        lspStatus.setTextColor(Color.parseColor(when (lspState) {
            0 -> okColor
            1 -> badColor
            else -> "#FF9500"
        }))
    }

    private fun checkRoot(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            p.waitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }

    /** 0=运行中, 1=未检测到, 2=需要root */
    private fun checkLsposed(): Int {
        return try {
            // 检查 root 是否可用
            val rootCheck = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            if (rootCheck.waitFor() != 0) return 2

            // 检查 LSPosed 守护进程目录是否存在
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls -d /data/adb/lspd 2>/dev/null"))
            val result = p.waitFor()
            if (result == 0) 0 else 1
        } catch (_: Throwable) {
            1
        }
    }

    private fun writeConfigFiles() {
        try {
            val enabled = prefs.getBoolean("enabled", false)
            val pace = prefs.getInt("pace_sec", 360).toString()
            val fluct = prefs.getInt("fluctuation", 60).toString()
            val enabledStr = if (enabled) "1" else "0"
            // 用 root 写入，确保所有应用可读
            val cmd = "echo $enabledStr > /data/local/tmp/stepsim_enabled; " +
                    "echo $pace > /data/local/tmp/stepsim_pace; " +
                    "echo $fluct > /data/local/tmp/stepsim_fluct; " +
                    "chmod 666 /data/local/tmp/stepsim_enabled /data/local/tmp/stepsim_pace /data/local/tmp/stepsim_fluct"
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
            } catch (_: Throwable) {
                // 无 root 时降级为普通写入
                val dir = java.io.File("/data/local/tmp")
                if (!dir.exists()) dir.mkdirs()
                java.io.File(dir, "stepsim_enabled").writeText(enabledStr)
                java.io.File(dir, "stepsim_pace").writeText(pace)
                java.io.File(dir, "stepsim_fluct").writeText(fluct)
            }
        } catch (_: Throwable) {}
    }

    private fun refresh() {
        val enabled = prefs.getBoolean("enabled", false)
        val paceSec = prefs.getInt("pace_sec", 360)  // 默认 6'00"
        val fluct = prefs.getInt("fluctuation", 60)

        // 同步写入 /data/local/tmp 供 hook 进程读取
        writeConfigFiles()

        speedValue.text = formatPace(paceSec)
        speedBar.progress = (600 - paceSec).coerceIn(0, 420)

        fluctValue.text = "$fluct"
        fluctBar.progress = fluct.coerceIn(0, 100)

        if (enabled) {
            statusDot.setTextColor(Color.parseColor("#34C759"))
            statusText.text = "模拟中"
            toggleBtn.text = "停止模拟"
            toggleBtn.background = roundedBg("#FF3B30", 28)
        } else {
            statusDot.setTextColor(Color.parseColor(if (dark) "#48484A" else "#C7C7CC"))
            statusText.text = "已停止"
            toggleBtn.text = "开始模拟"
            toggleBtn.background = roundedBg(accentBlue, 28)
        }
    }

    /** 秒 -> 5'30" 格式 */
    private fun formatPace(totalSec: Int): String {
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format("%d'%02d\"", m, s)
    }

    override fun onResume() {
        super.onResume()
        // 跟随系统模式下，系统深浅色变化后重刷界面
        if (isDarkEffective() != dark) {
            rebuild()
        } else {
            refresh()
            runChecks()
        }
    }
}
