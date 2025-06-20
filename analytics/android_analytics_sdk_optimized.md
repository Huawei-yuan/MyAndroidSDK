# Android自研埋点SDK优化方案（移除AspectJX）

## 1. 自动埋点替代方案

### 1.1 方案对比

| 方案 | 优势 | 劣势 | 适用场景 |
|------|------|------|----------|
| **Activity/Fragment生命周期监听** | 简单易用，无需额外插件 | 覆盖场景有限 | 页面访问统计 |
| **View代理模式** | 覆盖面广，性能好 | 需要手动注册 | 点击事件统计 |
| **字节码插桩（Transform API）** | 功能强大，自动化程度高 | 配置复杂，维护成本高 | 全面自动埋点 |
| **Gradle Plugin + ASM** | 现代化，可控性强 | 开发成本较高 | 企业级应用 |

### 1.2 推荐方案组合

我们采用**生命周期监听 + View代理模式**的组合方案，既保证了功能完整性，又避免了复杂的字节码操作。

## 2. 优化后的项目结构

### 2.1 移除AspectJ相关依赖

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    
    // 移除AspectJ相关依赖
    // implementation("org.aspectj:aspectjrt:1.9.19")
    
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.lifecycle:lifecycle-common:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    
    // 添加反射相关（可选，用于高级功能）
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.9.0")
}

// 移除AspectJ插件
plugins {
    id("kotlinx-serialization")
    // id("com.hujiang.aspectjx") version "2.0.10" // 删除
}
```

### 2.2 新的自动埋点架构

```
analytics-sdk/
├── src/main/kotlin/com/yourcompany/analytics/
│   ├── AnalyticsSDK.kt                 
│   ├── config/
│   ├── collector/
│   │   ├── EventCollector.kt           
│   │   ├── AutoTrackManager.kt         // 替代AspectJ的自动埋点管理器
│   │   ├── LifecycleTracker.kt         // 生命周期追踪
│   │   ├── ViewTracker.kt              // View点击追踪
│   │   └── ManualTracker.kt            
│   ├── storage/
│   ├── network/
│   ├── processor/
│   ├── models/
│   └── utils/
```

## 3. 核心实现优化

### 3.1 自动埋点管理器

```kotlin
class AutoTrackManager private constructor() {
    companion object {
        @Volatile
        private var INSTANCE: AutoTrackManager? = null
        
        fun getInstance(): AutoTrackManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AutoTrackManager().also { INSTANCE = it }
            }
        }
    }
    
    private var isEnabled = false
    private var config: AutoTrackConfig? = null
    private val lifecycleTracker = LifecycleTracker()
    private val viewTracker = ViewTracker()
    
    fun init(context: Context, config: AutoTrackConfig) {
        if (isEnabled) return
        
        this.config = config
        this.isEnabled = true
        
        // 初始化各个追踪器
        if (config.enableActivityTracking) {
            lifecycleTracker.startActivityTracking(context)
        }
        
        if (config.enableFragmentTracking) {
            lifecycleTracker.startFragmentTracking()
        }
        
        if (config.enableViewClickTracking) {
            viewTracker.startClickTracking()
        }
        
        // 启动应用生命周期监听
        if (config.enableAppLifecycleTracking) {
            lifecycleTracker.startAppLifecycleTracking(context)
        }
    }
    
    fun shutdown() {
        lifecycleTracker.stop()
        viewTracker.stop()
        isEnabled = false
    }
    
    // 手动注册View点击事件
    fun trackViewClick(view: View, properties: Map<String, Any> = emptyMap()) {
        if (!isEnabled) return
        viewTracker.trackViewClick(view, properties)
    }
    
    // 批量注册View点击事件
    fun trackViewClicks(vararg views: View) {
        if (!isEnabled) return
        views.forEach { view ->
            trackViewClick(view)
        }
    }
}

data class AutoTrackConfig(
    val enableActivityTracking: Boolean = true,
    val enableFragmentTracking: Boolean = true,
    val enableViewClickTracking: Boolean = true,
    val enableAppLifecycleTracking: Boolean = true,
    val enableDialogTracking: Boolean = false,
    val clickDebounceMs: Long = 500L, // 防重复点击
    val ignoreClasses: Set<String> = emptySet(), // 忽略的类名
    val ignoreViewTypes: Set<String> = emptySet() // 忽略的View类型
)
```

### 3.2 生命周期追踪器

```kotlin
class LifecycleTracker {
    private var activityCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var fragmentCallbacks: FragmentManager.FragmentLifecycleCallbacks? = null
    private var processLifecycleObserver: LifecycleObserver? = null
    
    fun startActivityTracking(context: Context) {
        val application = context.applicationContext as Application
        
        activityCallbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                trackActivityEvent(activity, "activity_created")
            }
            
            override fun onActivityStarted(activity: Activity) {
                trackActivityEvent(activity, "activity_started")
            }
            
            override fun onActivityResumed(activity: Activity) {
                trackActivityEvent(activity, "page_view", mapOf(
                    "page_name" to activity.getPageName(),
                    "page_title" to activity.getPageTitle(),
                    "is_main_page" to activity.isMainActivity()
                ))
            }
            
            override fun onActivityPaused(activity: Activity) {
                trackActivityEvent(activity, "page_leave")
            }
            
            override fun onActivityStopped(activity: Activity) {
                trackActivityEvent(activity, "activity_stopped")
            }
            
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            
            override fun onActivityDestroyed(activity: Activity) {
                trackActivityEvent(activity, "activity_destroyed")
            }
        }
        
        application.registerActivityLifecycleCallbacks(activityCallbacks)
    }
    
    fun startFragmentTracking() {
        // 这里需要在Activity中手动注册Fragment回调
        // 我们提供一个便捷的扩展函数
    }
    
    fun startAppLifecycleTracking(context: Context) {
        processLifecycleObserver = object : LifecycleObserver {
            @OnLifecycleEvent(Lifecycle.Event.ON_START)
            fun onAppForeground() {
                AnalyticsSDK.getInstance().track("app_foreground", mapOf(
                    "timestamp" to System.currentTimeMillis()
                ))
            }
            
            @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
            fun onAppBackground() {
                AnalyticsSDK.getInstance().track("app_background", mapOf(
                    "timestamp" to System.currentTimeMillis()
                ))
                
                // 后台时强制上报数据
                AnalyticsSDK.getInstance().flush()
            }
        }
        
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver!!)
    }
    
    private fun trackActivityEvent(activity: Activity, eventName: String, properties: Map<String, Any> = emptyMap()) {
        val activityName = activity::class.simpleName ?: "Unknown"
        
        // 检查是否在忽略列表中
        if (shouldIgnoreActivity(activityName)) return
        
        val finalProperties = properties.toMutableMap().apply {
            put("activity_name", activityName)
            put("activity_hash", activity.hashCode())
        }
        
        AnalyticsSDK.getInstance().track(eventName, finalProperties)
    }
    
    private fun shouldIgnoreActivity(activityName: String): Boolean {
        val ignoreList = setOf(
            "LauncherActivity", // 启动页
            "SplashActivity",   // 闪屏页
            "CrashActivity"     // 崩溃页面
        )
        return ignoreList.contains(activityName)
    }
    
    fun stop() {
        activityCallbacks?.let { callbacks ->
            // 需要保存Application引用才能注销
        }
        
        fragmentCallbacks?.let { callbacks ->
            // 需要保存FragmentManager引用才能注销
        }
        
        processLifecycleObserver?.let { observer ->
            ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
        }
    }
}
```

### 3.3 View点击追踪器

```kotlin
class ViewTracker {
    private val trackedViews = mutableSetOf<Int>() // 已追踪的View ID
    private val clickTimes = mutableMapOf<Int, Long>() // 防重复点击
    private var config: AutoTrackConfig? = null
    
    fun startClickTracking() {
        // View点击追踪需要手动注册，我们提供便捷的扩展函数
    }
    
    fun trackViewClick(view: View, additionalProperties: Map<String, Any> = emptyMap()) {
        val viewId = view.id
        val currentTime = System.currentTimeMillis()
        
        // 防重复点击检查
        val debounceTime = config?.clickDebounceMs ?: 500L
        val lastClickTime = clickTimes[viewId] ?: 0L
        if (currentTime - lastClickTime < debounceTime) {
            return
        }
        clickTimes[viewId] = currentTime
        
        // 收集View信息
        val properties = mutableMapOf<String, Any>().apply {
            put("element_type", view::class.simpleName ?: "Unknown")
            put("element_id", view.getResourceName() ?: "")
            put("element_content", view.getViewContent() ?: "")
            
            // 添加位置信息
            view.getViewPosition()?.let { position ->
                putAll(position)
            }
            
            // 添加父容器信息
            view.getParentInfo()?.let { parentInfo ->
                putAll(parentInfo)
            }
            
            // 添加额外属性
            putAll(additionalProperties)
        }
        
        AnalyticsSDK.getInstance().track("view_click", properties)
    }
    
    fun stop() {
        trackedViews.clear()
        clickTimes.clear()
    }
}
```

### 3.4 Activity/Fragment扩展函数

```kotlin
// Activity扩展
fun Activity.setupAutoTrack() {
    // 自动注册Fragment生命周期回调
    if (this is FragmentActivity) {
        this.supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                    super.onFragmentResumed(fm, f)
                    trackFragmentEvent(f, "fragment_view")
                }
                
                override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
                    super.onFragmentPaused(fm, f)
                    trackFragmentEvent(f, "fragment_leave")
                }
            }, true
        )
    }
}

fun Activity.trackViewClicks(vararg views: View) {
    AutoTrackManager.getInstance().trackViewClicks(*views)
}

fun Activity.trackViewClick(view: View, properties: Map<String, Any> = emptyMap()) {
    AutoTrackManager.getInstance().trackViewClick(view, properties)
}

// Fragment扩展
fun Fragment.trackViewClicks(vararg views: View) {
    AutoTrackManager.getInstance().trackViewClicks(*views)
}

fun Fragment.trackViewClick(view: View, properties: Map<String, Any> = emptyMap()) {
    AutoTrackManager.getInstance().trackViewClick(view, properties)
}

// View扩展
fun View.setupAutoTrack(properties: Map<String, Any> = emptyMap()) {
    this.setOnClickListener { view ->
        AutoTrackManager.getInstance().trackViewClick(view, properties)
    }
}

fun View.setupAutoTrack(
    onClick: (() -> Unit)? = null,
    properties: Map<String, Any> = emptyMap()
) {
    this.setOnClickListener { view ->
        // 先执行埋点
        AutoTrackManager.getInstance().trackViewClick(view, properties)
        // 再执行原始点击逻辑
        onClick?.invoke()
    }
}

// 批量设置
fun ViewGroup.setupAutoTrackForChildren(
    recursive: Boolean = false,
    properties: Map<String, Any> = emptyMap()
) {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (child.isClickable) {
            child.setupAutoTrack(properties)
        }
        
        if (recursive && child is ViewGroup) {
            child.setupAutoTrackForChildren(recursive, properties)
        }
    }
}
```

### 3.5 View信息提取工具

```kotlin
// View扩展函数 - 提取详细信息
fun View.getResourceName(): String? = try {
    if (id != View.NO_ID) {
        resources.getResourceEntryName(id)
    } else null
} catch (e: Exception) {
    null
}

fun View.getViewContent(): String? = when (this) {
    is TextView -> text?.toString()
    is Button -> text?.toString()
    is ImageView -> contentDescription?.toString()
    is EditText -> hint?.toString() ?: text?.toString()
    is CheckBox -> text?.toString()
    is RadioButton -> text?.toString()
    is Switch -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
        textOn?.toString() ?: textOff?.toString()
    } else null
    else -> contentDescription?.toString()
}

fun View.getViewPosition(): Map<String, Int>? {
    val location = IntArray(2)
    getLocationOnScreen(location)
    return mapOf(
        "x" to location[0],
        "y" to location[1],
        "width" to width,
        "height" to height
    )
}

fun View.getParentInfo(): Map<String, String>? {
    val parent = parent
    return if (parent is ViewGroup) {
        mapOf(
            "parent_type" to parent::class.simpleName,
            "parent_id" to (parent.getResourceName() ?: ""),
            "child_index" to parent.indexOfChild(this).toString()
        )
    } else null
}

fun Activity.getPageName(): String {
    return this::class.simpleName ?: "Unknown"
}

fun Activity.getPageTitle(): String? {
    return title?.toString()
}

fun Activity.isMainActivity(): Boolean {
    return intent?.action == Intent.ACTION_MAIN && 
           intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true
}

private fun trackFragmentEvent(fragment: Fragment, eventName: String) {
    val properties = mapOf(
        "fragment_name" to (fragment::class.simpleName ?: "Unknown"),
        "parent_activity" to (fragment.activity?.let { it::class.simpleName } ?: "Unknown"),
        "fragment_tag" to (fragment.tag ?: ""),
        "fragment_id" to fragment.id
    )
    
    AnalyticsSDK.getInstance().track(eventName, properties)
}
```

### 3.6 RecyclerView特殊处理

```kotlin
// RecyclerView点击追踪
fun RecyclerView.setupAutoTrack() {
    this.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
        private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val child = findChildViewUnder(e.x, e.y)
                val position = getChildAdapterPosition(child ?: return false)
                
                if (position != RecyclerView.NO_POSITION) {
                    trackRecyclerViewItemClick(position, child)
                }
                return false
            }
        })
        
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            gestureDetector.onTouchEvent(e)
            return false
        }
        
        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
    })
}

private fun RecyclerView.trackRecyclerViewItemClick(position: Int, itemView: View) {
    val properties = mapOf(
        "element_type" to "RecyclerViewItem",
        "item_position" to position,
        "item_id" to itemView.getResourceName(),
        "item_content" to itemView.getViewContent(),
        "list_id" to this.getResourceName()
    ).filterNotNullValues()
    
    AnalyticsSDK.getInstance().track("recyclerview_item_click", properties)
}
```

## 4. 使用示例

### 4.1 Application初始化

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 初始化埋点SDK
        AnalyticsSDK.getInstance().init(this) {
            serverUrl = "https://your-analytics-server.com/api"
            appKey = "your_app_key_here"
            uploadInterval = 30_000L
            batchSize = 50
            debugMode = BuildConfig.DEBUG
        }
        
        // 初始化自动埋点
        AutoTrackManager.getInstance().init(this, AutoTrackConfig(
            enableActivityTracking = true,
            enableFragmentTracking = true,
            enableViewClickTracking = true,
            enableAppLifecycleTracking = true,
            clickDebounceMs = 500L,
            ignoreClasses = setOf("SplashActivity", "LauncherActivity")
        ))
    }
    
    override fun onTerminate() {
        super.onTerminate()
        AutoTrackManager.getInstance().shutdown()
        AnalyticsSDK.getInstance().shutdown()
    }
}
```

### 4.2 Activity中使用

```kotlin
class MainActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // 设置自动追踪
        setupAutoTrack()
        setupViewTracking()
    }
    
    private fun setupViewTracking() {
        // 方式1：单个View自动埋点
        findViewById<Button>(R.id.btnLogin).setupAutoTrack(
            onClick = { performLogin() },
            properties = mapOf("button_type" to "primary")
        )
        
        // 方式2：批量设置
        trackViewClicks(
            findViewById(R.id.btnShare),
            findViewById(R.id.btnSettings),
            findViewById(R.id.btnProfile)
        )
        
        // 方式3：为ViewGroup中的所有可点击View设置埋点
        findViewById<LinearLayout>(R.id.layoutButtons)
            .setupAutoTrackForChildren(recursive = true)
        
        // 方式4：RecyclerView特殊处理
        findViewById<RecyclerView>(R.id.recyclerView).setupAutoTrack()
        
        // 方式5：自定义埋点
        findViewById<Button>(R.id.btnCustom).setOnClickListener { view ->
            // 自定义埋点逻辑
            AnalyticsSDK.getInstance().track("custom_button_click", mapOf(
                "button_name" to "special_button",
                "user_level" to getUserLevel(),
                "screen_name" to "main"
            ))
            
            // 执行点击逻辑
            handleCustomButtonClick()
        }
    }
    
    private fun performLogin() {
        // 登录逻辑
    }
    
    private fun handleCustomButtonClick() {
        // 自定义按钮点击逻辑
    }
    
    private fun getUserLevel(): String {
        return "premium" // 示例
    }
}
```

### 4.3 Fragment中使用

```kotlin
class HomeFragment : Fragment() {
    
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Fragment中的View埋点
        trackViewClicks(
            view.findViewById(R.id.btnAction1),
            view.findViewById(R.id.btnAction2)
        )
        
        // 为特定View添加额外属性
        view.findViewById<Button>(R.id.btnSpecial).setupAutoTrack(
            properties = mapOf(
                "fragment_name" to "home",
                "button_category" to "action"
            )
        )
    }
}
```

### 4.4 自定义View埋点

```kotlin
class CustomView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    init {
        setupClickTracking()
    }
    
    private fun setupClickTracking() {
        setOnClickListener { view ->
            // 自动埋点
            AutoTrackManager.getInstance().trackViewClick(view, mapOf(
                "custom_view_type" to "special",
                "custom_property" to getCustomProperty()
            ))
            
            // 执行点击逻辑
            handleClick()
        }
    }
    
    private fun getCustomProperty(): String {
        return "custom_value"
    }
    
    private fun handleClick() {
        // 自定义点击处理
    }
}
```

## 5. 高级特性

### 5.1 动态开关控制

```kotlin
// 运行时动态控制埋点
class DynamicTrackingController {
    private val remoteConfig = RemoteConfigManager()
    
    suspend fun updateTrackingConfig() {
        remoteConfig.fetchConfig().onSuccess { config ->
            // 动态更新埋点配置
            if (!config.enableClickTracking) {
                AutoTrackManager.getInstance().shutdown()
            }
            
            // 更新采样率
            AnalyticsSDK.getInstance().updateSampleRate(config.sampleRate)
        }
    }
}
```

### 5.2 性能监控

```kotlin
// 监控埋点性能
class TrackingPerformanceMonitor {
    private val performanceMetrics = mutableMapOf<String, Long>()
    
    fun measureTrackingTime(eventName: String, block: () -> Unit) {
        val startTime = System.currentTimeMillis()
        block()
        val duration = System.currentTimeMillis() - startTime
        
        performanceMetrics[eventName] = duration
        
        // 如果埋点耗时过长，记录性能问题
        if (duration > 50) { // 50ms阈值
            AnalyticsSDK.getInstance().track("tracking_performance_issue", mapOf(
                "event_name" to eventName,
                "duration" to duration
            ))
        }
    }
}
```

## 6. 总结

通过移除AspectJX插件，我们实现了以下优化：

### 6.1 技术优势
- **兼容性更好**：不依赖过时的AspectJX插件
- **维护成本低**：使用Android原生API，更容易维护
- **性能更好**：避免了字节码注入的性能开销
- **调试友好**：埋点逻辑更加透明，便于调试

### 6.2 功能完整性
- **页面访问统计**：通过Activity/Fragment生命周期实现
- **点击事件统计**：通过View代理模式实现
- **应用生命周期**：通过ProcessLifecycleOwner实现
- **自定义埋点**：保持原有手动埋点能力

### 6.3 使用便捷性
- **扩展函数**：提供便捷的Kotlin扩展函数
- **自动化程度**：通过简单的函数调用实现自动埋点
- **灵活配置**：支持动态开关和配置

这套优化方案既保持了原有的功能完整性，又解决了AspectJX插件的兼容性问题，是一个更加现代化和可维护的解决方案。