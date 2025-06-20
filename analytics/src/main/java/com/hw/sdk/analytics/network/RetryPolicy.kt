package com.hw.sdk.analytics.network

import com.hw.sdk.analytics.models.AnalyticsResult
import com.hw.sdk.analytics.utils.logDebug
import kotlinx.coroutines.delay

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