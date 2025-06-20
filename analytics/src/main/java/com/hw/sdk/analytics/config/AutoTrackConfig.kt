package com.hw.sdk.analytics.config

data class AutoTrackConfig(
    val enableActivityTracking: Boolean = true,
    val enableFragmentTracking: Boolean = true,
    val enableViewClickTracking: Boolean = true,
    val enableAppLifecycleTracking: Boolean = true,
    val enableDialogTracking: Boolean = false,
    val clickDebounceMs: Long = 500L, // 防重复点击时间间隔
    val ignoreClasses: Set<String> = emptySet(), // 忽略的类名
    val ignoreViewTypes: Set<String> = emptySet(), // 忽略的View类型
    val sampleRate: Float = 1.0f // 埋点采样率，默认100%
)
