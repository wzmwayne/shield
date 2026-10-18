package wzmwayne.shield.core

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 处置运行状态（跨线程）。
 *  · running：当前是否有处置正在执行；
 *  · abortRequested：处置过程中无障碍被关闭时置位，处置执行器会在步骤间隙中止。
 */
object DisposalState {

    private val runningFlag = AtomicBoolean(false)
    private val abortFlag = AtomicBoolean(false)

    val running: Boolean get() = runningFlag.get()

    fun begin() {
        abortFlag.set(false)
        runningFlag.set(true)
    }

    fun end() {
        runningFlag.set(false)
        abortFlag.set(false)
    }

    fun requestAbort() {
        abortFlag.set(true)
    }

    fun shouldAbort(): Boolean = abortFlag.get()

    fun resetAbort() {
        abortFlag.set(false)
    }
}
