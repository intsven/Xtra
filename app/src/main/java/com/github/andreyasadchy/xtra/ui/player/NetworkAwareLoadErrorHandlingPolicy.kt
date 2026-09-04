package com.github.andreyasadchy.xtra.ui.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.HttpDataSource.CleartextNotPermittedException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import java.io.FileNotFoundException
import java.io.IOException

class NetworkAwareLoadErrorHandlingPolicy(
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
) : DefaultLoadErrorHandlingPolicy(maxRetries) {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
        val exception = findFirstIOException(loadErrorInfo.exception)
        return if (exception != null && isNetworkError(exception)) {
            if (loadErrorInfo.errorCount <= maxRetries) {
                RETRY_DELAYS.getOrElse(loadErrorInfo.errorCount - 1) { MAX_DELAY_MS }
            } else {
                C.TIME_UNSET
            }
        } else {
            super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    private fun isNetworkError(throwable: Throwable): Boolean {
        if (throwable is CleartextNotPermittedException || throwable is FileNotFoundException) {
            return false
        }
        if (throwable is InvalidResponseCodeException) {
            return false
        }
        if (throwable is DataSourceException) {
            return throwable.reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    throwable.reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        }
        if (throwable is IOException) {
            return true
        }
        return false
    }

    private fun findFirstIOException(throwable: Throwable?): IOException? {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is IOException) return current
            current = current.cause
        }
        return null
    }

    companion object {
        private const val DEFAULT_MAX_RETRIES = 20
        private const val MAX_DELAY_MS = 5000L
        private val RETRY_DELAYS = longArrayOf(
            0L,    // 1st: immediate
            1000L, // 2nd: 1s
            1000L, // 3rd: 1s
            2000L, // 4th: 2s
            2000L, // 5th: 2s
            3000L, // 6th: 3s
            3000L, // 7th: 3s
            4000L, // 8th: 4s
            4000L, // 9th: 4s
            5000L, // 10th-20th: 5s
        )
    }
}
