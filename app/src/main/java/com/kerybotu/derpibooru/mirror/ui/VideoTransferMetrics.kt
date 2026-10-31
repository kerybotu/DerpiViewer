package com.kerybotu.derpibooru.mirror.ui

import android.os.SystemClock
import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import java.util.Collections
import java.util.WeakHashMap

/** Actual received bytes, never buffer percentages or a bandwidth estimator's default. */
internal class VideoTransferMetrics(private val purpose: String) : TransferListener {
    private data class Sample(val start: Long, val version: Long, var first: Long = -1, var bytes: Long = 0)
    private val transfers = Collections.synchronizedMap(WeakHashMap<DataSource, Sample>())
    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (isNetwork) transfers[source] = Sample(SystemClock.elapsedRealtime(), NetworkManager.configurationVersion.value)
    }
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (isNetwork) Log.d("VideoTransfer", "start purpose=$purpose sourceKey=${dataSpec.uri.toString().hashCode()} config=${transfers[source]?.version} active=${transfers.size} rangeStart=${dataSpec.position}")
    }
    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (!isNetwork) return
        transfers[source]?.let {
            if (it.first < 0) it.first = SystemClock.elapsedRealtime() - it.start
            it.bytes += bytesTransferred
        }
    }
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isNetwork) return
        val sample = transfers.remove(source) ?: return
        val elapsed = (SystemClock.elapsedRealtime() - sample.start).coerceAtLeast(1)
        Log.d("VideoTransfer", "end purpose=$purpose sourceKey=${dataSpec.uri.toString().hashCode()} config=${sample.version} bytes=${sample.bytes} elapsedMs=$elapsed firstByteMs=${sample.first} bytesPerSec=${sample.bytes * 1000 / elapsed} active=${transfers.size}")
    }
}
