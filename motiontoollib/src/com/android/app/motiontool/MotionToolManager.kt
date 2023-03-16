/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.app.motiontool

import android.util.Log
import android.view.View
import android.view.WindowManagerGlobal
import androidx.annotation.VisibleForTesting
import com.android.app.motiontool.nano.WindowIdentifier
import com.android.app.viewcapture.ViewCapture
import com.android.app.viewcapture.data.nano.ExportedData

/**
 * Singleton to manage motion tracing sessions.
 *
 * A motion tracing session captures motion-relevant data on a frame-by-frame basis for a given
 * window, as long as the trace is running.
 *
 * To start a trace, use [beginTrace]. The returned handle must be used to terminate tracing and
 * receive the data by calling [endTrace]. While the trace is active, data is buffered, however
 * the buffer size is limited (@see [ViewCapture.mMemorySize]. Use [pollTrace] periodically to
 * ensure no data is dropped. Both, [pollTrace] and [endTrace] only return data captured since the
 * last call to either [beginTrace] or [endTrace].
 *
 * NOTE: a running trace will incur some performance penalty. Only keep traces running while a user
 * requested it.
 *
 * @see [DdmHandleMotionTool]
 */
class MotionToolManager private constructor(
    private val viewCapture: ViewCapture,
    private val windowManagerGlobal: WindowManagerGlobal
) {

    companion object {
        private const val TAG = "MotionToolManager"

        private var INSTANCE: MotionToolManager? = null

        @Synchronized
        fun getInstance(
            viewCapture: ViewCapture,
            windowManagerGlobal: WindowManagerGlobal
        ): MotionToolManager {
            return INSTANCE ?: MotionToolManager(viewCapture, windowManagerGlobal).also {
                INSTANCE = it
            }
        }
    }

    private var traceIdCounter = 0
    private val traces = mutableMapOf<Int, TraceMetadata>()

    @Synchronized
    fun hasWindow(windowId: WindowIdentifier): Boolean {
        val rootView = getRootView(windowId.rootWindow)
        return rootView != null
    }

    /** Starts [ViewCapture] and returns a traceId. */
    @Synchronized
    fun beginTrace(windowId: String): Int {
        val traceId = ++traceIdCounter
        Log.d(TAG, "Begin Trace for id: $traceId")
        val rootView = getRootView(windowId) ?: throw WindowNotFoundException(windowId)
        val autoCloseable = viewCapture.startCapture(rootView, windowId)
        traces[traceId] = TraceMetadata(windowId, 0, autoCloseable::close)
        return traceId
    }

    /**
     * Ends [ViewCapture] and returns the captured [ExportedData] since the [beginTrace] call or the
     * last [pollTrace] call.
     */
    @Synchronized
    fun endTrace(traceId: Int): ExportedData {
        Log.d(TAG, "End Trace for id: $traceId")
        val traceMetadata = traces.getOrElse(traceId) { throw UnknownTraceIdException(traceId) }
        val exportedData = pollTrace(traceId)
        traceMetadata.stopTrace()
        traces.remove(traceId)
        return exportedData
    }

    /**
     * Returns the [ExportedData] captured since the [beginTrace] call or the last [pollTrace] call.
     * This function can only be used after [beginTrace] is called and before [endTrace] is called.
     */
    @Synchronized
    fun pollTrace(traceId: Int): ExportedData {
        val traceMetadata = traces.getOrElse(traceId) { throw UnknownTraceIdException(traceId) }
        val exportedData = getExportedDataFromViewCapture(traceMetadata)
        traceMetadata.updateLastPolledTime(exportedData)
        return exportedData
    }

    /**
     * Stops and deletes all active [traces] and resets the [traceIdCounter].
     */
    @VisibleForTesting
    @Synchronized
    fun reset() {
        for (traceMetadata in traces.values) {
            traceMetadata.stopTrace()
        }
        traces.clear()
        traceIdCounter = 0
    }

    private fun getExportedDataFromViewCapture(traceMetadata: TraceMetadata): ExportedData {
        val rootView =
            getRootView(traceMetadata.windowId)
                ?: throw WindowNotFoundException(traceMetadata.windowId)
        return viewCapture
            .getDumpTask(rootView)
            ?.orElse(null)
            ?.get()
            ?.apply {
                frameData = frameData?.filter { it.timestamp > traceMetadata.lastPolledTime }
                    ?.toTypedArray()
            }
            ?: ExportedData()
    }

    private fun getRootView(windowId: String): View? {
        return windowManagerGlobal.getRootView(windowId)
    }
}

private data class TraceMetadata(
    val windowId: String,
    var lastPolledTime: Long,
    var stopTrace: () -> Unit
) {
    fun updateLastPolledTime(exportedData: ExportedData?) {
        exportedData?.frameData?.maxOfOrNull { it.timestamp }?.let { maxFrameTimestamp ->
            lastPolledTime = maxFrameTimestamp
        }
    }
}

class UnknownTraceIdException(val traceId: Int) : Exception()

class WindowNotFoundException(val windowId: String) : Exception()
