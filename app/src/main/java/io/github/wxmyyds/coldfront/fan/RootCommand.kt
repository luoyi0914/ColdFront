package io.github.wxmyyds.coldfront.fan

import android.os.Build
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * `su -c` 的执行结果。旧实现只返回 stdout 字符串，无法区分「写成功」和
 * 「命令没执行/写被拒绝」——写失败会被 UI 当成写成功。这里把退出码和超时暴露出来，
 * 让调用方能够 truthful 地判定成功。
 */
data class RootResult(
    val output: String = "",
    val exitCode: Int = -1,
    val timedOut: Boolean = false,
    /** 熔断期内直接短路返回（没有真的执行命令）。 */
    val fused: Boolean = false,
) {
    val success: Boolean get() = !fused && !timedOut && exitCode == 0
}

/**
 * 执行外部命令，并在**独立线程**上并发抽取 stdout/stderr。
 *
 * 关键点一：超时必须覆盖「读输出」阶段。若先阻塞读到 EOF 再 `waitFor`，一旦 `su`
 * 在等授权弹窗（既不输出也不退出），进程会一直挂着，超时分支永远走不到，
 * 引导页和轮询就一起卡死。
 *
 * 关键点二：超时后必须**先关管道再等抽取线程**。`destroy()` 只给 `su` 发信号，
 * 而真正执行命令的子 shell 可能继承着管道写端继续存活 —— 此时 `read` 永不返回 EOF。
 * 所以超时路径上额外 `destroyForcibly()` 并主动关闭输入流，让抽取线程立刻收尾，
 * 而不是靠 `join(200)` 假装输出已经完整（那会静默截断输出）。
 *
 * 关键点三：`waitFor`/`join` 抛出的 `InterruptedException` 必须单独处理 ——
 * 否则会被外层 catch 变成「没有 su」这类错误结论。中断时保留中断标志并如实报告超时。
 *
 * 关键点四：`Process.waitFor(timeout, unit)` 与 `Process.destroyForcibly()` 都是
 * API 26 才加入的，而本模块 minSdk = 24（沿用上游）。这里用显式的版本分支提供兼容
 * 实现，而不是把 minSdk 抬到 26：API 24/25 上用 `exitValue()` 轮询表达同样的语义。
 */
internal fun executeProcess(command: List<String>, timeoutMs: Long): RootResult {
    var process: Process? = null
    return try {
        val running = ProcessBuilder(command).redirectErrorStream(true).start()
        process = running
        val output = StringBuilder()
        val drain = thread(isDaemon = true, name = "fan-root-output") {
            runCatching {
                running.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(1024)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val room = OUTPUT_LIMIT - output.length
                            if (room > 0) output.append(buffer, 0, minOf(count, room))
                        }
                    }
                }
            }
        }

        var completed = false
        try {
            completed = running.waitForCompat(timeoutMs)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            completed = false
        }

        if (!completed) {
            running.destroy()
            var dead = false
            try {
                dead = running.waitForCompat(300)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (!dead) runCatching { running.destroyForciblyCompat() }
            // 关掉读端：即使子 shell 仍持有写端，抽取线程也会立刻拿到 EOF 而退出。
            runCatching { running.inputStream.close() }
        }
        try {
            drain.join(200)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        RootResult(
            output = synchronized(output) { output.toString().trim() },
            exitCode = if (completed) runCatching { running.exitValue() }.getOrDefault(-1) else -1,
            timedOut = !completed,
        )
    } catch (error: Exception) {
        // 没有 su 二进制、权限被拒等：统一按失败处理，并把原因留在 output 里便于诊断。
        RootResult(output = error.message.orEmpty())
    } finally {
        process?.destroy()
        runCatching { process?.inputStream?.close() }
        runCatching { process?.errorStream?.close() }
        runCatching { process?.outputStream?.close() }
    }
}

private const val OUTPUT_LIMIT = 32 * 1024

/** API 24/25 上 `exitValue()` 轮询的间隔。进程通常几十毫秒内就退出，5 ms 足够且开销可忽略。 */
private const val WAIT_POLL_MS = 5L

/**
 * `Process.waitFor(timeout, unit)` 的跨版本等价实现。
 *
 * API 26 起直接用系统实现；API 24/25 没有带超时的 `waitFor`，且 `isAlive()` 同样是
 * API 26 才有的，因此只能靠 `exitValue()` 抛 `IllegalThreadStateException` 来判断
 * 进程是否仍在运行。语义与带超时的 `waitFor` 一致：退出返回 true，超时返回 false。
 *
 * 与调用处一样，`Thread.sleep` 抛出的 `InterruptedException` 不做捕获，交由调用方处理。
 */
private fun Process.waitForCompat(timeoutMs: Long): Boolean {
    if (Build.VERSION.SDK_INT >= 26) {
        return waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    }
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (true) {
        if (!isAliveCompat()) return true
        if (System.nanoTime() >= deadline) return false
        Thread.sleep(WAIT_POLL_MS)
    }
}

/** API 26 起 `isAlive()` 可用；更早的版本只能用 `exitValue()` 是否抛异常来判断。 */
private fun Process.isAliveCompat(): Boolean =
    try {
        exitValue()
        false
    } catch (ignored: IllegalThreadStateException) {
        true
    }

/**
 * `destroyForcibly()` 是 API 26 才有的。API 24/25 退化为 `destroy()`：
 * 发不出 SIGKILL，但调用处紧接着会关闭读端，抽取线程不会因此挂死。
 */
private fun Process.destroyForciblyCompat() {
    if (Build.VERSION.SDK_INT >= 26) destroyForcibly() else destroy()
}
