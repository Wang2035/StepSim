package com.stepsim.xposed

/**
 * 模块激活状态标记。模块加载自身包时 hook isActivated 返回 true。
 * UI 通过它判断模块是否在 LSPosed 中生效。
 */
object ModuleStatus {
    @JvmStatic
    fun isActivated(): Boolean = false
}
