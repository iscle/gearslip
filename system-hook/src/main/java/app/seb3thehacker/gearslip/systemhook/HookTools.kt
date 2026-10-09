package app.seb3thehacker.gearslip.systemhook

import android.os.Binder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal object HookTools {
    private val logged = ConcurrentHashMap.newKeySet<String>()
    fun log(message: String) = XposedBridge.log("GearslipBridge: $message")
    fun failure(operation: String, error: Throwable) {
        // Framework exception messages can contain private content URIs or request data.
        if (logged.add(operation)) log(
            "$operation unavailable: ${error.javaClass.simpleName}" +
                (error.cause?.let { " (${it.javaClass.simpleName})" } ?: ""))
    }

    inline fun <T> system(block: () -> T): T {
        // Binder threads retain the incoming caller's identity until it is explicitly cleared.
        val identity = Binder.clearCallingIdentity()
        return try {
            block()
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    /** Xposed reflection reports missing OEM APIs as Error, not Exception. Convert those
     * to ordinary failures so a Binder/worker thread cannot take down system_server. */
    inline fun <T> reflect(block: () -> T): T = try {
        block()
    } catch (e: XposedHelpers.InvocationTargetError) {
        val cause = e.cause
        if (cause is VirtualMachineError) throw cause
        if (cause is ThreadDeath) throw cause
        throw IllegalStateException("Framework invocation failed", cause)
    } catch (e: XposedHelpers.ClassNotFoundError) {
        throw IllegalStateException("Framework class unavailable", e)
    } catch (e: LinkageError) {
        throw IllegalStateException("Framework API unavailable", e)
    }

    fun findClass(name: String, loader: ClassLoader): Class<*> =
        reflect { XposedHelpers.findClass(name, loader) }

    fun staticField(type: Class<*>, name: String): Any =
        reflect { XposedHelpers.getStaticObjectField(type, name) }

    fun callStatic(type: Class<*>, name: String, vararg args: Any?): Any? =
        reflect { XposedHelpers.callStaticMethod(type, name, *args) }

    fun field(instance: Any, name: String): Any? =
        reflect { XposedHelpers.getObjectField(instance, name) }

    fun call(instance: Any, name: String, vararg args: Any?): Any? =
        reflect { XposedHelpers.callMethod(instance, name, *args) }

    fun hook(
        loader: ClassLoader, className: String, methodName: String,
        before: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
        after: ((XC_MethodHook.MethodHookParam) -> Unit)? = null
    ): Int {
        val clazz = reflect { XposedHelpers.findClassIfExists(className, loader) } ?: return 0
        return clazz.declaredMethods.filter { it.name == methodName }.count { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        before?.invoke(param)
                    } catch (e: Exception) {
                        failure("$className.$methodName/before", e)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        after?.invoke(param)
                    } catch (e: Exception) {
                        failure("$className.$methodName/after", e)
                    }
                }
            })
            true
        }
    }

    fun parameterIndex(param: XC_MethodHook.MethodHookParam, name: String) =
        (param.method as Method).parameterTypes.indexOfFirst { it.name == name }
}
