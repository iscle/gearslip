package app.seb3thehacker.gearslip.systemhook

import android.os.Process
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

/** Legacy API 82 is supported by LSPosed and Vector; only system_server is instrumented. */
class SystemHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != "android" || Process.myUid() != 1000 ||
            !installed.compareAndSet(false, true)) return
        try {
            val env = BridgeEnvironment(param.classLoader)
            val packages = PackageHooks(env).install(param.classLoader)
            val grants = UriGrantHooks(env, param.classLoader).install()
            val services = ServiceHooks(env).install(param.classLoader)
            env.ready = packages && grants && services
            HookTools.log("installed: packages=$packages grants=$grants services=$services ready=${env.ready}")
        } catch (e: Exception) { HookTools.failure("install", e) }
    }

    private companion object { val installed = AtomicBoolean(false) }
}
