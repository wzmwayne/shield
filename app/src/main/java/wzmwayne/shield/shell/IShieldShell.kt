package wzmwayne.shield.shell

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel

/**
 * 手写的 Binder 协议，等价于 AIDL 生成的代码。
 *
 * 之所以不写 .aidl：Shizuku 用户服务只需要「类实现 IBinder」，事务号与序列化格式由本文件两端
 * 自行约定即可；服务端只会用固定事务号 16777115 调用 destroy()（见 Shizuku 的
 * ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy）。
 */
interface IShieldShell : IInterface {

    /** 在 shell 身份下执行命令，返回 {"code":int,"out":String,"err":String,"timeout":boolean} */
    fun exec(command: String): String

    fun destroy()

    open class Stub : Binder(), IShieldShell {

        override fun asBinder(): IBinder = this

        override fun getInterfaceDescriptor(): String = DESCRIPTOR

        override fun exec(command: String): String =
            throw UnsupportedOperationException("由具体实现覆盖")

        override fun destroy() = Unit

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                TRANSACTION_EXEC -> {
                    data.enforceInterface(DESCRIPTOR)
                    val command = data.readString().orEmpty()
                    val result = exec(command)
                    if (reply != null) {
                        reply.writeNoException()
                        reply.writeString(result)
                    }
                    return true
                }

                TRANSACTION_DESTROY -> {
                    // 服务端以 ONEWAY 调用，且不保证写入接口名，这里不做强制校验
                    destroy()
                    if (reply != null) reply.writeNoException()
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }

        companion object {
            const val DESCRIPTOR = "wzmwayne.shield.shell.IShieldShell"

            /** 由我们两侧自行约定的事务号 */
            const val TRANSACTION_EXEC = IBinder.FIRST_CALL_TRANSACTION

            /** Shizuku 约定的用户服务销毁事务号，不可改动 */
            const val TRANSACTION_DESTROY = 16777115
        }
    }

    class Proxy(private val remote: IBinder) : IShieldShell {

        override fun asBinder(): IBinder = remote

        override fun exec(command: String): String {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(Stub.DESCRIPTOR)
                data.writeString(command)
                remote.transact(Stub.TRANSACTION_EXEC, data, reply, 0)
                reply.readException()
                return reply.readString().orEmpty()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        override fun destroy() {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(Stub.DESCRIPTOR)
                remote.transact(Stub.TRANSACTION_DESTROY, data, reply, 0)
                reply.readException()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    companion object {
        fun asInterface(binder: IBinder): IShieldShell {
            val local = runCatching { binder.queryLocalInterface(Stub.DESCRIPTOR) }.getOrNull()
            return local as? IShieldShell ?: Proxy(binder)
        }
    }
}
