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
        if (logged.add(operation)) log("$operation unavailable: ${error.javaClass.simpleName}" +
            (error.cause?.let { " (${it.javaClass.simpleName})" } ?: ""))
    }
    inline fun <T> system(block: () -> T): T {
        val identity = Binder.clearCallingIdentity()
        return try { block() } finally { Binder.restoreCallingIdentity(identity) }
    }
    fun field(instance: Any, name: String): Any? = XposedHelpers.getObjectField(instance, name)
    fun call(instance: Any, name: String, vararg args: Any?): Any? =
        XposedHelpers.callMethod(instance, name, *args)

    fun hook(loader: ClassLoader, className: String, methodName: String,
             before: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
             after: ((XC_MethodHook.MethodHookParam) -> Unit)? = null): Int {
        val clazz = XposedHelpers.findClassIfExists(className, loader) ?: return 0
        return clazz.declaredMethods.filter { it.name == methodName }.count { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try { before?.invoke(param) } catch (e: Exception) { failure("$className.$methodName/before", e) }
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    try { after?.invoke(param) } catch (e: Exception) { failure("$className.$methodName/after", e) }
                }
            })
            true
        }
    }
    fun parameterIndex(param: XC_MethodHook.MethodHookParam, name: String) =
        (param.method as Method).parameterTypes.indexOfFirst { it.name == name }
}
