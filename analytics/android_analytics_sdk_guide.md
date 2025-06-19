# Android自研埋点SDK实现方案（Kotlin版）

## 1. 整体架构设计

### 1.1 架构分层
```
┌─────────────────────────────────────────┐
│                应用层                    │
├─────────────────────────────────────────┤
│            埋点SDK接口层                  │
├─────────────────────────────────────────┤
│          数据收集 & 预处理层               │
├─────────────────────────────────────────┤
│            本地存储缓存层                  │
├─────────────────────────────────────────┤
│             网络上报层                    │
├─────────────────────────────────────────┤
│              后端服务                     │
└─────────────────────────────────────────┘
```

### 1.2 Kotlin特性应用
- **协程**：替代线程池，提供更优雅的异步处理
- **扩展函数**：简化常用操作，提升代码可读性
- **数据类**：简化数据模型定义
- **密封类**：优雅处理状态和结果类型
- **委托属性**：实现懒加载和属性观察
- **DSL**：提供流畅的配置API

## 2. 项目结构搭建

### 2.1 Gradle依赖配置
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
    
    implementation("org.aspectj:aspectjrt:1.9.19")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
}

// 添加AspectJ和序列化插件
plugins {
    id("kotlinx-serialization")
    id("com.hujiang.aspectjx") version "2.0.10"
}
```

### 2.2 SDK模块结构
```
analytics-sdk/
├── src/main/kotlin/com/yourcompany/analytics/
│   ├── AnalyticsSDK.kt                 // SDK主入口
│   ├── config/
│   │   ├── AnalyticsConfig.kt          // 配置类（DSL支持）
│   │   ├── RemoteConfig.kt             // 远程配置
│   │   └── ConfigDsl.kt                // 配置DSL
│   ├── collector/
│   │   ├── EventCollector.kt           // 事件收集器
│   │   ├── AutoTrackAspect.kt          // AOP自动埋点
│   │   └── ManualTracker.kt            // 手动埋点扩展
│   ├── storage/
│   │   ├── EventDatabase.kt            // Room数据库
│   │   ├── EventDao.kt                 // 数据访问对象
│   │   └── EventEntity.kt              // 数据实体
│   ├── network/
│   │   ├── NetworkManager.kt           // 网络管理
│   │   ├── RetryPolicy.kt              // 重试策略
│   │   ├── DataUploader.kt             // 数据上报
│   │   └── ApiService.kt               // Retrofit接口
│   ├── processor/
│   │   ├── EventProcessor.kt           // 事件处理器
│   │   └── DataEncoder.kt              // 数据编码
│   ├── models/
│   │   ├── Event.kt                    // 事件模型
│   │   ├── UserInfo.kt                 // 用户信息
│   │   └── Result.kt                   // 结果封装
│   └── utils/
│       ├── Extensions.kt               // 扩展函数
│       ├── DeviceUtils.kt              // 设备信息工具
│       └── CoroutineUtils.kt           // 协程工具
```

## 3. 核心代码实现

### 3.1 SDK主入口类
```kotlin
class AnalyticsSDK private constructor() {
    companion object {
        @Volatile
        private var INSTANCE: AnalyticsSDK? = null
        
        fun getInstance(): AnalyticsSDK {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AnalyticsSDK().also { INSTANCE = it }
            }
        }
    }
    
    private var config: AnalyticsConfig? = null
    private var eventCollector: EventCollector? = null
    private var dataUploader: DataUploader? = null
    private var isInitialized = false
    
    // 协程作用域
    private val sdkScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("AnalyticsSDK")
    )
    
    fun init(context: Context, block: AnalyticsConfigBuilder.() -> Unit) {
        if (isInitialized) return
        
        // 使用DSL构建配置
        this.config = AnalyticsConfigBuilder().apply(block).build()
        
        // 初始化组件
        val database = EventDatabase.getInstance(context)
        this.eventCollector = EventCollector(context, config!!, database)
        this.dataUploader = DataUploader(context, config!!, database)
        
        // 启动定时上报
        startScheduledUpload()
        isInitialized = true
    }
    
    fun track(eventName: String, properties: Map<String, Any> = emptyMap()) {
        if (!isInitialized) {
            logWarning("SDK not initialized")
            return
        }
        
        sdkScope.launch {
            eventCollector?.collectEvent(eventName, properties)
        }
    }
    
    fun setUserProperties(properties: Map<String, Any>) {
        if (!isInitialized) return
        
        sdkScope.launch {
            eventCollector?.setUserProperties(properties)
        }
    }
    
    private fun startScheduledUpload() {
        sdkScope.launch {
            while (isActive) {
                try {
                    dataUploader?.uploadEvents()
                    delay(config?.uploadInterval ?: 30_000L)
                } catch (e: Exception) {
                    logError("Scheduled upload failed", e)
                    delay(60_000L) // 失败后等待更长时间
                }
            }
        }
    }
    
    fun flush() {
        sdkScope.launch {
            dataUploader?.uploadEvents()
        }
    }
    
    fun shutdown() {
        sdkScope.cancel()
        isInitialized = false
    }
}
```

### 3.2 配置类（支持DSL）
```kotlin
@Serializable
data class AnalyticsConfig(
    val serverUrl: String,
    val appKey: String,
    val uploadInterval: Long = 30_000L,
    val batchSize: Int = 50,
    val maxBatchSize: Int = 100,
    val enableAutoTrack: Boolean = true,
    val enableEncryption: Boolean = false,
    val encryptionKey: String? = null,
    val debugMode: Boolean = false,
    val enableLocationTracking: Boolean = false,
    val sessionTimeout: Long = 30 * 60 * 1000L // 30分钟
)

class AnalyticsConfigBuilder {
    var serverUrl: String = ""
    var appKey: String = ""
    var uploadInterval: Long = 30_000L
    var batchSize: Int = 50
    var maxBatchSize: Int = 100
    var enableAutoTrack: Boolean = true
    var enableEncryption: Boolean = false
    var encryptionKey: String? = null
    var debugMode: Boolean = false
    var enableLocationTracking: Boolean = false
    var sessionTimeout: Long = 30 * 60 * 1000L
    
    fun build(): AnalyticsConfig {
        require(serverUrl.isNotEmpty()) { "Server URL cannot be empty" }
        require(appKey.isNotEmpty()) { "App key cannot be empty" }
        
        return AnalyticsConfig(
            serverUrl = serverUrl,
            appKey = appKey,
            uploadInterval = uploadInterval,
            batchSize = batchSize,
            maxBatchSize = maxBatchSize,
            enableAutoTrack = enableAutoTrack,
            enableEncryption = enableEncryption,
            encryptionKey = encryptionKey,
            debugMode = debugMode,
            enableLocationTracking = enableLocationTracking,
            sessionTimeout = sessionTimeout
        )
    }
}

// DSL 扩展函数
fun analyticsConfig(block: AnalyticsConfigBuilder.() -> Unit): AnalyticsConfig {
    return AnalyticsConfigBuilder().apply(block).build()
}
```

### 3.3 数据模型（使用数据类和密封类）
```kotlin
@Serializable
data class Event(
    val eventName: String,
    val properties: Map<String, JsonElement>,
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String,
    val userId: String?,
    val deviceInfo: DeviceInfo,
    val userProperties: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val platform: String = "Android",
    val osVersion: String,
    val appVersion: String,
    val manufacturer: String,
    val model: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val networkType: String,
    val carrier: String?,
    val language: String,
    val timezone: String
)

// 使用密封类处理结果
sealed class AnalyticsResult<out T> {
    data class Success<T>(val data: T) : AnalyticsResult<T>()
    data class Error(val exception: Throwable) : AnalyticsResult<Nothing>()
    object Loading : AnalyticsResult<Nothing>()
}

// 扩展函数简化结果处理
inline fun <T> AnalyticsResult<T>.onSuccess(action: (T) -> Unit): AnalyticsResult<T> {
    if (this is AnalyticsResult.Success) action(data)
    return this
}

inline fun <T> AnalyticsResult<T>.onError(action: (Throwable) -> Unit): AnalyticsResult<T> {
    if (this is AnalyticsResult.Error) action(exception)
    return this
}
```

### 3.4 事件收集器
```kotlin
class EventCollector(
    private val context: Context,
    private val config: AnalyticsConfig,
    private val database: EventDatabase
) {
    private val deviceInfo: DeviceInfo by lazy { context.getDeviceInfo() }
    private val sessionManager = SessionManager(config.sessionTimeout)
    private val eventProcessor = EventProcessor(config)
    
    // 用户属性（线程安全）
    private val userProperties = ConcurrentHashMap<String, JsonElement>()
    
    suspend fun collectEvent(eventName: String, properties: Map<String, Any>) {
        try {
            val event = buildEvent(eventName, properties)
            val processedEvent = eventProcessor.processEvent(event)
            
            // 保存到数据库
            database.eventDao().insertEvent(processedEvent.toEntity())
            
            if (config.debugMode) {
                logDebug("Event collected: $eventName")
            }
            
        } catch (e: Exception) {
            logError("Failed to collect event: $eventName", e)
        }
    }
    
    suspend fun setUserProperties(properties: Map<String, Any>) {
        properties.forEach { (key, value) ->
            userProperties[key] = value.toJsonElement()
        }
    }
    
    private fun buildEvent(eventName: String, properties: Map<String, Any>): Event {
        return Event(
            eventName = eventName,
            properties = properties.mapValues { it.value.toJsonElement() },
            sessionId = sessionManager.getCurrentSessionId(),
            userId = getCurrentUserId(),
            deviceInfo = deviceInfo,
            userProperties = userProperties.toMap()
        )
    }
    
    private fun getCurrentUserId(): String? {
        // 从SharedPreferences或其他地方获取用户ID
        return context.getSharedPreferences("analytics", Context.MODE_PRIVATE)
            ?.getString("user_id", null)
    }
}

// 会话管理器
class SessionManager(private val sessionTimeout: Long) {
    private var currentSessionId: String = generateSessionId()
    private var lastActivityTime: Long = System.currentTimeMillis()
    
    @Synchronized
    fun getCurrentSessionId(): String {
        val now = System.currentTimeMillis()
        if (now - lastActivityTime > sessionTimeout) {
            currentSessionId = generateSessionId()
        }
        lastActivityTime = now
        return currentSessionId
    }
    
    private fun generateSessionId(): String {
        return "${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
}
```

### 3.5 AOP自动埋点
```kotlin
@Aspect
class AutoTrackAspect {
    
    // 点击事件自动埋点
    @Around("execution(* android.view.View.OnClickListener.onClick(..))")
    fun trackViewClick(joinPoint: ProceedingJoinPoint): Any? {
        val result = joinPoint.proceed()
        
        // 获取点击的View
        val args = joinPoint.args
        if (args.isNotEmpty() && args[0] is View) {
            val view = args[0] as View
            trackClick(view)
        }
        
        return result
    }
    
    // Activity生命周期埋点
    @After("execution(* android.app.Activity.onResume(..))")
    fun trackActivityResume(joinPoint: JoinPoint) {
        val activity = joinPoint.`this` as Activity
        trackPageView(activity)
    }
    
    @After("execution(* androidx.fragment.app.Fragment.onResume(..))")
    fun trackFragmentResume(joinPoint: JoinPoint) {
        val fragment = joinPoint.`this` as Fragment
        trackFragmentView(fragment)
    }
    
    private fun trackClick(view: View) {
        val properties = mapOf(
            "element_type" to view::class.simpleName,
            "element_id" to view.getResourceName(),
            "element_content" to view.getViewContent(),
            "element_position" to view.getViewPosition()
        ).filterNotNullValues()
        
        AnalyticsSDK.getInstance().track("view_click", properties)
    }
    
    private fun trackPageView(activity: Activity) {
        val properties = mapOf(
            "page_name" to activity::class.simpleName,
            "page_title" to activity.title?.toString(),
            "page_url" to activity.intent?.dataString
        ).filterNotNullValues()
        
        AnalyticsSDK.getInstance().track("page_view", properties)
    }
    
    private fun trackFragmentView(fragment: Fragment) {
        val properties = mapOf(
            "fragment_name" to fragment::class.simpleName,
            "parent_activity" to fragment.activity?.let { it::class.simpleName }
        ).filterNotNullValues()
        
        AnalyticsSDK.getInstance().track("fragment_view", properties)
    }
}
```

### 3.6 本地存储（Room + 协程）
```kotlin
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "event_name")
    val eventName: String,
    
    @ColumnInfo(name = "properties")
    val properties: String,
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    
    @ColumnInfo(name = "user_id")
    val userId: String?,
    
    @ColumnInfo(name = "device_info")
    val deviceInfo: String,
    
    @ColumnInfo(name = "user_properties")
    val userProperties: String = "{}",
    
    @ColumnInfo(name = "uploaded")
    val uploaded: Boolean = false,
    
    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0
)

@Dao
interface EventDao {
    @Query("SELECT * FROM events WHERE uploaded = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getUnuploadedEvents(limit: Int): List<EventEntity>
    
    @Insert
    suspend fun insertEvent(event: EventEntity): Long
    
    @Insert
    suspend fun insertEvents(events: List<EventEntity>): List<Long>
    
    @Query("UPDATE events SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markAsUploaded(ids: List<Long>)
    
    @Query("UPDATE events SET retry_count = retry_count + 1 WHERE id IN (:ids)")
    suspend fun incrementRetryCount(ids: List<Long>)
    
    @Query("DELETE FROM events WHERE uploaded = 1 AND timestamp < :timestamp")
    suspend fun deleteOldUploadedEvents(timestamp: Long)
    
    @Query("DELETE FROM events WHERE retry_count >= 3 AND timestamp < :timestamp")
    suspend fun deleteFailedEvents(timestamp: Long)
    
    @Query("SELECT COUNT(*) FROM events WHERE uploaded = 0")
    suspend fun getUnuploadedCount(): Int
}

@Database(
    entities = [EventEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class EventDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    
    companion object {
        @Volatile
        private var INSTANCE: EventDatabase? = null
        
        fun getInstance(context: Context): EventDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    EventDatabase::class.java,
                    "analytics_database"
                ).apply {
                    setJournalMode(RoomDatabase.JournalMode.WAL) // 启用WAL模式
                    setQueryExecutor(Dispatchers.IO.asExecutor())
                }.build().also { INSTANCE = it }
            }
        }
    }
}
```

### 3.7 网络上报模块
```kotlin
// Retrofit接口
interface AnalyticsApiService {
    @POST("events")
    suspend fun uploadEvents(
        @Header("Authorization") authorization: String,
        @Body request: UploadRequest
    ): Response<UploadResponse>
}

@Serializable
data class UploadRequest(
    val events: List<Event>,
    val appKey: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class UploadResponse(
    val success: Boolean,
    val message: String? = null,
    val receivedCount: Int = 0
)

class DataUploader(
    private val context: Context,
    private val config: AnalyticsConfig,
    private val database: EventDatabase
) {
    private val apiService: AnalyticsApiService by lazy {
        Retrofit.Builder()
            .baseUrl(config.serverUrl)
            .client(createOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AnalyticsApiService::class.java)
    }
    
    private val retryPolicy = RetryPolicy()
    private val json = Json { ignoreUnknownKeys = true }
    
    suspend fun uploadEvents(): AnalyticsResult<Int> = withContext(Dispatchers.IO) {
        try {
            val events = database.eventDao().getUnuploadedEvents(config.batchSize)
            if (events.isEmpty()) {
                return@withContext AnalyticsResult.Success(0)
            }
            
            // 检查网络连接
            if (!context.isNetworkAvailable()) {
                return@withContext AnalyticsResult.Error(
                    Exception("No network connection")
                )
            }
            
            // 转换为Event对象
            val eventList = events.map { it.toEvent() }
            val request = UploadRequest(eventList, config.appKey)
            
            // 执行网络请求
            val response = apiService.uploadEvents(
                authorization = "Bearer ${config.appKey}",
                request = request
            )
            
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true) {
                    // 标记为已上报
                    val eventIds = events.map { it.id }
                    database.eventDao().markAsUploaded(eventIds)
                    
                    // 清理旧数据
                    cleanOldData()
                    
                    if (config.debugMode) {
                        logDebug("Successfully uploaded ${events.size} events")
                    }
                    
                    AnalyticsResult.Success(events.size)
                } else {
                    AnalyticsResult.Error(Exception(body?.message ?: "Upload failed"))
                }
            } else {
                handleUploadFailure(events)
                AnalyticsResult.Error(Exception("HTTP ${response.code()}: ${response.message()}"))
            }
            
        } catch (e: Exception) {
            logError("Upload failed", e)
            AnalyticsResult.Error(e)
        }
    }
    
    private suspend fun handleUploadFailure(events: List<EventEntity>) {
        val eventIds = events.map { it.id }
        database.eventDao().incrementRetryCount(eventIds)
    }
    
    private suspend fun cleanOldData() {
        val oneWeekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        database.eventDao().deleteOldUploadedEvents(oneWeekAgo)
        database.eventDao().deleteFailedEvents(oneWeekAgo)
    }
    
    private fun createOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(createLoggingInterceptor())
            .addInterceptor(createCompressionInterceptor())
            .build()
    }
    
    private fun createLoggingInterceptor(): Interceptor {
        return if (config.debugMode) {
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
        } else {
            Interceptor { chain -> chain.proceed(chain.request()) }
        }
    }
    
    private fun createCompressionInterceptor(): Interceptor {
        return Interceptor { chain ->
            val originalRequest = chain.request()
            val compressedRequest = originalRequest.newBuilder()
                .header("Content-Encoding", "gzip")
                .method(originalRequest.method, originalRequest.body?.gzip())
                .build()
            chain.proceed(compressedRequest)
        }
    }
}
```

### 3.8 重试策略（协程实现）
```kotlin
class RetryPolicy {
    private val maxRetries = 3
    private val baseDelayMs = 1000L
    private val maxDelayMs = 30000L
    
    suspend fun <T> executeWithRetry(
        operation: suspend () -> AnalyticsResult<T>
    ): AnalyticsResult<T> {
        repeat(maxRetries) { attempt ->
            when (val result = operation()) {
                is AnalyticsResult.Success -> return result
                is AnalyticsResult.Error -> {
                    if (attempt == maxRetries - 1) {
                        return result
                    }
                    
                    val delay = calculateDelay(attempt)
                    logDebug("Retry attempt ${attempt + 1} after ${delay}ms")
                    delay(delay)
                }
                is AnalyticsResult.Loading -> {
                    // 继续重试
                }
            }
        }
        
        return AnalyticsResult.Error(Exception("Max retries exceeded"))
    }
    
    private fun calculateDelay(attempt: Int): Long {
        val delay = baseDelayMs * (1 shl attempt) // 指数退避
        return minOf(delay, maxDelayMs)
    }
}
```

### 3.9 扩展函数工具类
```kotlin
// Context扩展
fun Context.getDeviceInfo(): DeviceInfo {
    val displayMetrics = resources.displayMetrics
    val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    
    return DeviceInfo(
        deviceId = getDeviceId(),
        osVersion = Build.VERSION.RELEASE,
        appVersion = getAppVersion(),
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        screenWidth = displayMetrics.widthPixels,
        screenHeight = displayMetrics.heightPixels,
        networkType = getNetworkType(),
        carrier = telephonyManager?.networkOperatorName,
        language = Locale.getDefault().language,
        timezone = TimeZone.getDefault().id
    )
}

fun Context.isNetworkAvailable(): Boolean {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } else {
        @Suppress("DEPRECATION")
        connectivityManager.activeNetworkInfo?.isConnected == true
    }
}

fun Context.getNetworkType(): String {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile"
            else -> "Unknown"
        }
    } else {
        @Suppress("DEPRECATION")
        when (connectivityManager.activeNetworkInfo?.type) {
            ConnectivityManager.TYPE_WIFI -> "WiFi"
            ConnectivityManager.TYPE_MOBILE -> "Mobile"
            else -> "Unknown"
        }
    }
}

// View扩展
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
    else -> null
}

fun View.getViewPosition(): Map<String, Int>? {
    val location = IntArray(2)
    getLocationOnScreen(location)
    return mapOf(
        "x" to location[0],
        "y" to location[1]
    )
}

// Map扩展
fun <K, V> Map<K, V?>.filterNotNullValues(): Map<K, V> {
    return mapNotNull { (key, value) ->
        value?.let { key to it }
    }.toMap()
}

// Any扩展 - 类型转换
fun Any.toJsonElement(): JsonElement = when (this) {
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(this.mapKeys { it.key.toString() }
        .mapValues { it.value?.toJsonElement() ?: JsonNull })
    is List<*> -> JsonArray(this.map { it?.toJsonElement() ?: JsonNull })
    else -> JsonPrimitive(toString())
}

// EventEntity扩展
fun EventEntity.toEvent(): Event {
    val json = Json { ignoreUnknownKeys = true }
    return Event(
        eventName = eventName,
        properties = json.decodeFromString(properties),
        timestamp = timestamp,
        sessionId = sessionId,
        userId = userId,
        deviceInfo = json.decodeFromString(deviceInfo),
        userProperties = json.decodeFromString(userProperties)
    )
}

fun Event.toEntity(): EventEntity {
    val json = Json { ignoreUnknownKeys = true }
    return EventEntity(
        eventName = eventName,
        properties = json.encodeToString(properties),
        timestamp = timestamp,
        sessionId = sessionId,
        userId = userId,
        deviceInfo = json.encodeToString(deviceInfo),
        userProperties = json.encodeToString(userProperties)
    )
}

// RequestBody扩展 - GZIP压缩
fun RequestBody.gzip(): RequestBody {
    return object : RequestBody() {
        override fun contentType() = this@gzip.contentType()
        
        override fun writeTo(sink: BufferedSink) {
            val gzipSink = GzipSink(sink)
            val bufferedSink = gzipSink.buffer()
            this@gzip.writeTo(bufferedSink)
            bufferedSink.close()
        }
    }
}

// 日志扩展
fun Any.logDebug(message: String) {
    if (BuildConfig.DEBUG) {
        Log.d("AnalyticsSDK", "${this::class.simpleName}: $message")
    }
}

fun Any.logError(message: String, throwable: Throwable? = null) {
    Log.e("AnalyticsSDK", "${this::class.simpleName}: $message", throwable)
}

fun Any.logWarning(message: String) {
    Log.w("AnalyticsSDK", "${this::class.simpleName}: $message")
}
```

## 4. 高级特性实现

### 4.1 智能上报策略（协程 + Flow）
```kotlin
class SmartUploadStrategy(
    private val context: Context,
    private val config: AnalyticsConfig
) {
    private val networkStateFlow = context.networkStateFlow()
    private val batteryStateFlow = context.batteryStateFlow()
    
    fun shouldUploadNow(): Flow<Boolean> = combine(
        networkStateFlow,
        batteryStateFlow
    ) { networkState, batteryState ->
        when {
            // WiFi环境下优先上报
            networkState.isWiFi -> true
            
            // 移动网络下的条件判断
            networkState.isMobile -> {
                // 低电量模式下降低上报频率
                if (batteryState.isLowPower) {
                    batteryState.level > 20 // 电量大于20%才上报
                } else {
                    true
                }
            }
            
            else -> false
        }
    }.distinctUntilChanged()
    
    suspend fun getOptimalBatchSize(): Int {
        val networkState = networkStateFlow.first()
        val batteryState = batteryStateFlow.first()
        
        return when {
            networkState.isWiFi -> config.maxBatchSize
            networkState.isMobile && !batteryState.isLowPower -> config.batchSize
            else -> config.batchSize / 2
        }
    }
}

// 网络状态数据类
data class NetworkState(
    val isConnected: Boolean,
    val isWiFi: Boolean,
    val isMobile: Boolean,
    val networkType: String
)

// 电池状态数据类
data class BatteryState(
    val level: Int,
    val isCharging: Boolean,
    val isLowPower: Boolean
)

// Context扩展 - 网络状态Flow
fun Context.networkStateFlow(): Flow<NetworkState> = callbackFlow {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            trySend(getCurrentNetworkState())
        }
        
        override fun onLost(network: Network) {
            trySend(getCurrentNetworkState())
        }
        
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            trySend(getCurrentNetworkState())
        }
    }
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        connectivityManager.registerDefaultNetworkCallback(callback)
    } else {
        val request = NetworkRequest.Builder().build()
        connectivityManager.registerNetworkCallback(request, callback)
    }
    
    // 发送初始状态
    trySend(getCurrentNetworkState())
    
    awaitClose {
        connectivityManager.unregisterNetworkCallback(callback)
    }
}.distinctUntilChanged()

// Context扩展 - 电池状态Flow
fun Context.batteryStateFlow(): Flow<BatteryState> = callbackFlow {
    val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trySend(getCurrentBatteryState(intent))
        }
    }
    
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_BATTERY_CHANGED)
        addAction(Intent.ACTION_POWER_SAVE_MODE_CHANGED)
    }
    
    registerReceiver(batteryReceiver, filter)
    
    // 发送初始状态
    val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    batteryIntent?.let { trySend(getCurrentBatteryState(it)) }
    
    awaitClose {
        unregisterReceiver(batteryReceiver)
    }
}.distinctUntilChanged()

private fun Context.getCurrentNetworkState(): NetworkState {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        
        NetworkState(
            isConnected = capabilities != null,
            isWiFi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            isMobile = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            networkType = getNetworkType()
        )
    } else {
        @Suppress("DEPRECATION")
        val networkInfo = connectivityManager.activeNetworkInfo
        NetworkState(
            isConnected = networkInfo?.isConnected == true,
            isWiFi = networkInfo?.type == ConnectivityManager.TYPE_WIFI,
            isMobile = networkInfo?.type == ConnectivityManager.TYPE_MOBILE,
            networkType = getNetworkType()
        )
    }
}

private fun Context.getCurrentBatteryState(intent: Intent): BatteryState {
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val batteryPct = level * 100 / scale.toFloat()
    
    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    
    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    val isLowPower = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        powerManager.isPowerSaveMode
    } else {
        batteryPct < 15
    }
    
    return BatteryState(
        level = batteryPct.toInt(),
        isCharging = isCharging,
        isLowPower = isLowPower
    )
}
```

### 4.2 数据加密（协程友好）
```kotlin
class DataCryptor(private val secretKey: String) {
    companion object {
        private const val AES_ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_LENGTH = 256
    }
    
    private val cipher by lazy { Cipher.getInstance(AES_ALGORITHM) }
    private val keySpec by lazy { 
        val key = secretKey.toByteArray().copyOf(32) // 确保32字节长度
        SecretKeySpec(key, "AES")
    }
    
    suspend fun encryptAsync(data: String): String = withContext(Dispatchers.Default) {
        try {
            val iv = ByteArray(12) // GCM推荐12字节IV
            SecureRandom().nextBytes(iv)
            val ivSpec = GCMParameterSpec(128, iv)
            
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            
            // 将IV和加密数据合并
            val result = iv + encrypted
            Base64.encodeToString(result, Base64.NO_WRAP)
        } catch (e: Exception) {
            logError("Encryption failed", e)
            data // 降级处理，返回原始数据
        }
    }
    
    suspend fun decryptAsync(encryptedData: String): String = withContext(Dispatchers.Default) {
        try {
            val data = Base64.decode(encryptedData, Base64.NO_WRAP)
            val iv = data.sliceArray(0..11) // 前12字节是IV
            val encrypted = data.sliceArray(12 until data.size)
            
            val ivSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decrypted = cipher.doFinal(encrypted)
            
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            logError("Decryption failed", e)
            encryptedData // 降级处理
        }
    }
}

// 在EventProcessor中使用加密
class EventProcessor(private val config: AnalyticsConfig) {
    private val cryptor = if (config.enableEncryption && config.encryptionKey != null) {
        DataCryptor(config.encryptionKey)
    } else null
    
    suspend fun processEvent(event: Event): Event {
        return if (cryptor != null) {
            // 加密敏感属性
            val encryptedProperties = event.properties.mapValues { (key, value) ->
                if (isSensitiveProperty(key)) {
                    JsonPrimitive(cryptor.encryptAsync(value.toString()))
                } else {
                    value
                }
            }
            
            event.copy(properties = encryptedProperties)
        } else {
            event
        }
    }
    
    private fun isSensitiveProperty(key: String): Boolean {
        val sensitiveKeys = setOf("user_id", "email", "phone", "address")
        return sensitiveKeys.contains(key.lowercase())
    }
}
```

### 4.3 远程配置（协程 + StateFlow）
```kotlin
class RemoteConfigManager(
    private val context: Context,
    private val configUrl: String
) {
    private val _configState = MutableStateFlow<AnalyticsResult<RemoteConfiguration>>(
        AnalyticsResult.Loading
    )
    val configState: StateFlow<AnalyticsResult<RemoteConfiguration>> = _configState.asStateFlow()
    
    private val preferences by lazy {
        context.getSharedPreferences("analytics_remote_config", Context.MODE_PRIVATE)
    }
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    suspend fun fetchConfig() {
        try {
            val request = Request.Builder()
                .url(configUrl)
                .addHeader("Accept", "application/json")
                .build()
            
            val response = httpClient.newCall(request).await()
            
            if (response.isSuccessful) {
                val json = response.body?.string()
                if (json != null) {
                    val config = Json.decodeFromString<RemoteConfiguration>(json)
                    saveConfigToLocal(config)
                    _configState.value = AnalyticsResult.Success(config)
                } else {
                    _configState.value = AnalyticsResult.Error(Exception("Empty response"))
                }
            } else {
                _configState.value = AnalyticsResult.Error(
                    Exception("HTTP ${response.code}: ${response.message}")
                )
            }
        } catch (e: Exception) {
            logError("Failed to fetch remote config", e)
            _configState.value = AnalyticsResult.Error(e)
            
            // 尝试加载本地缓存
            loadConfigFromLocal()?.let {
                _configState.value = AnalyticsResult.Success(it)
            }
        }
    }
    
    private fun saveConfigToLocal(config: RemoteConfiguration) {
        val json = Json.encodeToString(config)
        preferences.edit()
            .putString("config_json", json)
            .putLong("config_timestamp", System.currentTimeMillis())
            .apply()
    }
    
    private fun loadConfigFromLocal(): RemoteConfiguration? {
        return try {
            val json = preferences.getString("config_json", null)
            if (json != null) {
                Json.decodeFromString<RemoteConfiguration>(json)
            } else null
        } catch (e: Exception) {
            logError("Failed to load local config", e)
            null
        }
    }
    
    fun isConfigExpired(): Boolean {
        val timestamp = preferences.getLong("config_timestamp", 0)
        val expireTime = 24 * 60 * 60 * 1000L // 24小时过期
        return System.currentTimeMillis() - timestamp > expireTime
    }
}

@Serializable
data class RemoteConfiguration(
    val enableAutoTrack: Boolean = true,
    val uploadInterval: Long = 30_000L,
    val batchSize: Int = 50,
    val enableEncryption: Boolean = false,
    val sampleRate: Float = 1.0f, // 采样率
    val eventFilters: List<String> = emptyList(), // 过滤的事件
    val debugMode: Boolean = false,
    val features: Map<String, Boolean> = emptyMap() // 功能开关
)

// OkHttp Call扩展 - 协程支持
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }
        
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
    })
    
    continuation.invokeOnCancellation {
        cancel()
    }
}
```

### 4.4 性能监控（协程监控）
```kotlin
class PerformanceMonitor(private val config: AnalyticsConfig) {
    private val metrics = ConcurrentHashMap<String, PerformanceMetric>()
    
    // 监控协程执行时间
    suspend fun <T> measureCoroutine(
        name: String,
        block: suspend () -> T
    ): T {
        val startTime = System.currentTimeMillis()
        return try {
            block().also {
                recordMetric(name, System.currentTimeMillis() - startTime, true)
            }
        } catch (e: Exception) {
            recordMetric(name, System.currentTimeMillis() - startTime, false)
            throw e
        }
    }
    
    // 监控普通方法执行时间
    inline fun <T> measure(name: String, block: () -> T): T {
        val startTime = System.currentTimeMillis()
        return try {
            block().also {
                recordMetric(name, System.currentTimeMillis() - startTime, true)
            }
        } catch (e: Exception) {
            recordMetric(name, System.currentTimeMillis() - startTime, false)
            throw e
        }
    }
    
    private fun recordMetric(name: String, duration: Long, success: Boolean) {
        metrics.compute(name) { _, existing ->
            val current = existing ?: PerformanceMetric(name)
            current.copy(
                totalCalls = current.totalCalls + 1,
                successCalls = if (success) current.successCalls + 1 else current.successCalls,
                totalDuration = current.totalDuration + duration,
                maxDuration = maxOf(current.maxDuration, duration),
                minDuration = if (current.minDuration == 0L) duration else minOf(current.minDuration, duration)
            )
        }
    }
    
    fun getMetrics(): Map<String, PerformanceMetric> = metrics.toMap()
    
    fun reportMetrics() {
        if (!config.debugMode) return
        
        metrics.forEach { (name, metric) ->
            val avgDuration = metric.totalDuration / metric.totalCalls
            val successRate = (metric.successCalls.toFloat() / metric.totalCalls * 100).toInt()
            
            logDebug(
                "Performance [$name]: " +
                        "calls=${metric.totalCalls}, " +
                        "success=${successRate}%, " +
                        "avg=${avgDuration}ms, " +
                        "min=${metric.minDuration}ms, " +
                        "max=${metric.maxDuration}ms"
            )
        }
    }
    
    fun reset() {
        metrics.clear()
    }
}

data class PerformanceMetric(
    val name: String,
    val totalCalls: Long = 0,
    val successCalls: Long = 0,
    val totalDuration: Long = 0,
    val maxDuration: Long = 0,
    val minDuration: Long = 0
)
```

## 5. 使用示例

### 5.1 SDK初始化（DSL风格）
```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 使用DSL配置SDK
        AnalyticsSDK.getInstance().init(this) {
            serverUrl = "https://your-analytics-server.com/api"
            appKey = "your_app_key_here"
            uploadInterval = 30_000L // 30秒
            batchSize = 50
            enableAutoTrack = true
            enableEncryption = true
            encryptionKey = "your_32_char_encryption_key_here"
            debugMode = BuildConfig.DEBUG
            enableLocationTracking = false
            sessionTimeout = 30 * 60 * 1000L // 30分钟
        }
        
        // 或者使用传统方式
        val config = analyticsConfig {
            serverUrl = "https://your-analytics-server.com/api"
            appKey = "your_app_key_here"
            enableAutoTrack = true
            debugMode = BuildConfig.DEBUG
        }
    }
}
```

### 5.2 手动埋点（Kotlin风格）
```kotlin
class MainActivity : AppCompatActivity() {
    private val analytics = AnalyticsSDK.getInstance()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        setupViews()
        setupUserProperties()
    }
    
    private fun setupViews() {
        // 使用扩展函数简化埋点
        findViewById<Button>(R.id.btnPurchase).setOnClickListener {
            // 埋点数据构建
            analytics.track("purchase_click") {
                "product_id" to "12345"
                "product_name" to "Premium Plan"
                "price" to 99.99
                "currency" to "USD"
                "category" to "subscription"
            }
            
            performPurchase()
        }
        
        findViewById<Button>(R.id.btnShare).setOnClickListener {
            analytics.track("share_click", mapOf(
                "content_type" to "article",
                "content_id" to "article_123",
                "share_method" to "native"
            ))
            
            shareContent()
        }
    }
    
    private fun setupUserProperties() {
        // 设置用户属性
        analytics.setUserProperties(mapOf(
            "user_level" to "VIP",
            "registration_date" to "2024-01-01",
            "preferred_language" to Locale.getDefault().language,
            "app_version" to getAppVersion()
        ))
    }
    
    private fun performPurchase() {
        // 购买逻辑
        lifecycleScope.launch {
            try {
                val result = purchaseRepository.purchase("premium_plan")
                
                // 购买成功埋点
                analytics.track("purchase_success", mapOf(
                    "product_id" to "premium_plan",
                    "transaction_id" to result.transactionId,
                    "amount" to result.amount
                ))
                
            } catch (e: Exception) {
                // 购买失败埋点
                analytics.track("purchase_failed", mapOf(
                    "product_id" to "premium_plan",
                    "error_message" to e.message,
                    "error_code" to (e as? PurchaseException)?.code
                ))
            }
        }
    }
}

// 扩展函数 - 简化埋点调用
inline fun AnalyticsSDK.track(
    eventName: String,
    propertiesBuilder: MutableMap<String, Any>.() -> Unit
) {
    val properties = mutableMapOf<String, Any>().apply(propertiesBuilder)
    track(eventName, properties)
}
```

### 5.3 自定义事件收集器
```kotlin
// 电商专用事件收集器
class ECommerceTracker(private val analytics: AnalyticsSDK) {
    
    fun trackProductView(product: Product) {
        analytics.track("product_view", mapOf(
            "product_id" to product.id,
            "product_name" to product.name,
            "category" to product.category,
            "price" to product.price,
            "brand" to product.brand,
            "in_stock" to product.inStock
        ))
    }
    
    fun trackAddToCart(product: Product, quantity: Int) {
        analytics.track("add_to_cart", mapOf(
            "product_id" to product.id,
            "quantity" to quantity,
            "total_value" to product.price * quantity
        ))
    }
    
    fun trackPurchase(order: Order) {
        analytics.track("purchase", mapOf(
            "order_id" to order.id,
            "total_amount" to order.totalAmount,
            "currency" to order.currency,
            "items_count" to order.items.size,
            "payment_method" to order.paymentMethod,
            "items" to order.items.map { item ->
                mapOf(
                    "product_id" to item.productId,
                    "quantity" to item.quantity,
                    "price" to item.price
                )
            }
        ))
    }
}

// 使用示例
class ProductActivity : AppCompatActivity() {
    private val ecommerceTracker = ECommerceTracker(AnalyticsSDK.getInstance())
    
    private fun showProduct(product: Product) {
        ecommerceTracker.trackProductView(product)
        // 显示产品界面...
    }
    
    private fun addToCart(product: Product, quantity: Int) {
        ecommerceTracker.trackAddToCart(product, quantity)
        // 添加到购物车逻辑...
    }
}
```

### 5.4 生命周期集成
```kotlin
// 使用Application.ActivityLifecycleCallbacks自动追踪页面
class AnalyticsLifecycleTracker : Application.ActivityLifecycleCallbacks {
    private val analytics = AnalyticsSDK.getInstance()
    private val sessionManager = mutableMapOf<String, Long>()
    
    override fun onActivityResumed(activity: Activity) {
        val activityName = activity::class.simpleName ?: "Unknown"
        val startTime = System.currentTimeMillis()
        sessionManager[activityName] = startTime
        
        analytics.track("page_enter", mapOf(
            "page_name" to activityName,
            "page_title" to activity.title?.toString(),
            "timestamp" to startTime
        ))
    }
    
    override fun onActivityPaused(activity: Activity) {
        val activityName = activity::class.simpleName ?: "Unknown"
        val startTime = sessionManager[activityName]
        val duration = if (startTime != null) {
            System.currentTimeMillis() - startTime
        } else 0L
        
        analytics.track("page_exit", mapOf(
            "page_name" to activityName,
            "duration" to duration
        ))
        
        sessionManager.remove(activityName)
    }
    
    // 其他生命周期方法的空实现...
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

// 在Application中注册
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 初始化SDK...
        
        // 注册生命周期回调
        registerActivityLifecycleCallbacks(AnalyticsLifecycleTracker())
    }
}
```

## 6. 测试方案

### 6.1 单元测试（使用MockK）
```kotlin
@RunWith(MockitoJUnitRunner::class)
class EventCollectorTest {
    
    @Mock
    lateinit var mockContext: Context
    
    @Mock
    lateinit var mockDatabase: EventDatabase
    
    @Mock
    lateinit var mockEventDao: EventDao
    
    private lateinit var eventCollector: EventCollector
    private lateinit var config: AnalyticsConfig
    
    @Before
    fun setup() {
        config = AnalyticsConfig(
            serverUrl = "https://test.com",
            appKey = "test_key"
        )
        
        whenever(mockDatabase.eventDao()).thenReturn(mockEventDao)
        eventCollector = EventCollector(mockContext, config, mockDatabase)
    }
    
    @Test
    fun `collectEvent should save event to database`() = runTest {
        // Given
        val eventName = "test_event"
        val properties = mapOf("key" to "value")
        
        // When
        eventCollector.collectEvent(eventName, properties)
        
        // Then
        verify(mockEventDao).insertEvent(any())
    }
    
    @Test
    fun `event should have correct timestamp`() = runTest {
        // Given
        val beforeTime = System.currentTimeMillis()
        
        // When
        eventCollector.collectEvent("test", emptyMap())
        
        val afterTime = System.currentTimeMillis()
        
        // Then
        argumentCaptor<EventEntity>().apply {
            verify(mockEventDao).insertEvent(capture())
            assertTrue(firstValue.timestamp in beforeTime..afterTime)
        }
    }
}
```

### 6.2 集成测试
```kotlin
@RunWith(AndroidJUnit4::class)
class AnalyticsSDKIntegrationTest {
    
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()
    
    private lateinit var context: Context
    private lateinit var analytics: AnalyticsSDK
    
    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        analytics = AnalyticsSDK.getInstance()
    }
    
    @Test
    fun `full workflow test - init, track, upload`() = runTest {
        // Given
        val mockWebServer = MockWebServer()
        mockWebServer.start()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success": true}"""))
        
        // When - 初始化SDK
        analytics.init(context) {
            serverUrl = mockWebServer.url("/").toString()
            appKey = "test_key"
            uploadInterval = 100L // 快速上传用于测试
            debugMode = true
        }
        
        // When - 发送事件
        analytics.track("test_event", mapOf("test_key" to "test_value"))
        
        // 等待上传
        delay(200L)
        
        // Then
        val request = mockWebServer.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("POST", request.method)
        
        mockWebServer.shutdown()
    }
}
```

这个Kotlin版本的实现充分利用了Kotlin的语言特性，包括协程、扩展函数、数据类、密封类等，提供了更加简洁、类型安全和易用的API。主要优势包括：

1. **协程替代线程池**：提供更好的异步处理体验
2. **DSL配置**：更直观的配置方式
3. **扩展函数**：简化常用操作
4. **类型安全**：编译时发现更多错误
5. **函数式编程**：更简洁的代码风格
6. **null安全**：避免NPE问题

这套方案可以直接用于生产环境，并且易于维护和扩展。