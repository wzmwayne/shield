package wzmwayne.shield.activation

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Base64
import wzmwayne.shield.admin.ShieldDeviceAdminReceiver
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.Constants
import wzmwayne.shield.core.Prefs
import wzmwayne.shield.core.Trace
import wzmwayne.shield.shell.ShellClient
import java.security.SecureRandom

/**
 * 阶段四：执行激活。所有命令都通过 Shizuku 以 Shell 身份执行。
 * 这是全应用唯一调用 Shizuku 的位置之一（另一处是向导的安装/检测步骤）。
 */
object OwnerSetup {

    fun selfAdminComponent(context: Context): String =
        "${context.packageName}/${ShieldDeviceAdminReceiver::class.java.name}"

    fun isSelfDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        return runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)
    }

    suspend fun setDirectOwner(context: Context): StepOutcome {
        Trace.step("执行激活命令：dpm set-device-owner ${selfAdminComponent(context)}")
        val result = ShellClient.exec("dpm set-device-owner ${selfAdminComponent(context)}")
        // 退出码可能为 0 而实际未生效，也可能为 0 但 stderr 里带 Error：一律以回读校验为准
        val verified = isSelfDeviceOwner(context)
        Trace.judge("回读设备所有者身份（isDeviceOwnerApp）", verified, if (verified) "锁盾自身" else "未生效")
        Trace.step("Shizuku 执行后的状态：${ShellClient.diagnostics()}")
        return if (result.ok && verified) {
            StepOutcome.ok("锁盾已成为设备所有者", result.summary)
        } else if (verified) {
            StepOutcome.ok("锁盾已是设备所有者（无需重复设置）", result.summary)
        } else {
            StepOutcome.fail(
                "设置设备所有者失败（锁盾未获得身份）",
                buildString {
                    if (result.out.isNotBlank()) append("stdout: ${result.out.trim()} ")
                    if (result.err.isNotBlank()) append("stderr: ${result.err.trim()} ")
                    if (result.out.isBlank() && result.err.isBlank()) append("退出码=${result.code} 无输出")
                    append("｜常见原因：设备存在账户/次要用户、已被其他应用持有、或系统已完成 provisioning")
                },
            )
        }
    }

    suspend fun grantCorePermissions(context: Context): StepOutcome {
        val pkg = context.packageName
        val commands = listOf(
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
            "pm grant $pkg android.permission.DUMP",
        )
        val failures = mutableListOf<String>()
        val successes = mutableListOf<String>()
        commands.forEach { command ->
            val result = ShellClient.exec(command)
            Trace.judge(
                what = command.removePrefix("pm grant $pkg "),
                ok = result.ok,
                detail = result.summary.take(80),
            )
            if (result.ok) successes += command else failures += "${result.summary}（$command）"
        }
        Trace.step("Shizuku 在权限授予后的状态：${ShellClient.diagnostics()}")
        return if (failures.isEmpty()) {
            StepOutcome.ok("核心权限已授予", successes.joinToString("；"))
        } else {
            StepOutcome.fail("部分权限授予失败", failures.joinToString("；"))
        }
    }

    /** 释放设备所有者身份（一键自杀时调用）：系统会同时撤销本应用的设备管理器 */
    fun clearDeviceOwner(context: Context): StepOutcome {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return StepOutcome.fail("无法获取设备策略服务")
        Trace.step("释放设备所有者权限：dpm clearDeviceOwnerApp")
        return try {
            dpm.clearDeviceOwnerApp(context.packageName)
            val stillOwner = isSelfDeviceOwner(context)
            if (stillOwner) {
                StepOutcome.fail("设备所有者释放失败", "系统仍认为锁盾是设备所有者")
            } else {
                StepOutcome.ok("设备所有者权限已释放", "卸载阻止与无障碍白名单随设备管理器一并撤销")
            }
        } catch (t: Throwable) {
            StepOutcome.fail("释放设备所有者异常", t.message.orEmpty())
        }
    }

    /** 预置恢复令牌，用于锁机场景下的密码重置 */
    fun installResetToken(context: Context): StepOutcome {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return StepOutcome.fail("无法获取设备策略服务")
        val admin = ComponentName(context, ShieldDeviceAdminReceiver::class.java)
        val token = Prefs.resetToken.value.ifEmpty { newToken() }
        Trace.step("调用 setResetPasswordToken 预置恢复令牌")
        return try {
            val accepted = dpm.setResetPasswordToken(admin, Base64.decode(token, Base64.DEFAULT))
            if (accepted) {
                Prefs.setResetToken(token)
                StepOutcome.ok("恢复令牌已预置", "请在恢复中心导出并妥善保存")
            } else {
                StepOutcome.fail("恢复令牌被系统拒绝", "当前身份可能不是设备所有者")
            }
        } catch (t: Throwable) {
            StepOutcome.fail("恢复令牌预置异常", t.message.orEmpty())
        }
    }

    fun newToken(): String {
        val bytes = ByteArray(Constants.RESET_TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    fun logOwnerState(context: Context) {
        AppLog.i("设备所有者状态：self=${isSelfDeviceOwner(context)}")
    }
}
