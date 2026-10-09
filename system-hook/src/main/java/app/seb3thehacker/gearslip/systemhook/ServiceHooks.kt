package app.seb3thehacker.gearslip.systemhook

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import de.robv.android.xposed.XC_MethodHook
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

internal class ServiceHooks(private val env: BridgeEnvironment) {
    fun install(loader: ClassLoader): Boolean {
        val ams = "com.android.server.am.ActivityManagerService"
        var count = 0
        for (method in listOf("bindService", "bindServiceInstance")) {
            count += HookTools.hook(
                loader,
                ams,
                method,
                before = { beforeBind(it) },
                after = { param ->
                    val created = param.getObjectExtra("gearslip.session") as? BridgeSession
                    if (created != null) {
                        env.bindScope.remove()
                        if (param.hasThrowable() || (param.result as? Int ?: 0) <= 0) env.close(
                            created
                        )
                    }
                })
        }
        val unbinds = HookTools.hook(loader, ams, "unbindService", after = { param ->
            if (param.result == true) {
                val binder = (param.args.firstOrNull() as? IInterface)?.asBinder()
                env.sessions.filter { it.connection == binder && it.hostUid == Binder.getCallingUid() }
                    .forEach(env::close)
            }
        })
        val permissions =
            HookTools.hook(loader, ams, "checkComponentPermission", before = { param ->
                val session = env.bindScope.get() ?: return@hook
                // Only the selected exported service's declared binding permission. This does
                // not grant runtime permissions or change permission answers in other operations.
                val args = param.args
                val owningUid = args.getOrNull(args.size - 2) as? Int
                if (session.permission != null && args.firstOrNull() == session.permission &&
                    args.getOrNull(2) == session.hostUid && owningUid == session.appUid &&
                    args.lastOrNull() == true
                ) param.result = PackageManager.PERMISSION_GRANTED
            })
        return count > 0 && unbinds > 0 && permissions > 0
    }

    // This runs inside system_server and intentionally implements its hidden AIDL callback.
    @SuppressLint("PrivateApi")
    private fun beforeBind(param: XC_MethodHook.MethodHookParam) {
        if (!env.ready || env.bindScope.get() != null) return
        val uid = Binder.getCallingUid()
        val host = env.host(uid) ?: return
        // The AOSP entry points end in userId. Reject cross-user and isolated instances.
        if (param.args.lastOrNull() != uid / 100_000) return
        val intent = param.args.filterIsInstance<Intent>().firstOrNull() ?: return
        val info = env.eligible(intent, uid) ?: return
        val index = HookTools.parameterIndex(param, "android.app.IServiceConnection")
        if (index < 0) return
        val original = param.args[index] as IInterface
        val connection = original.asBinder()
        val component = ComponentName(info.packageName, info.name)
        fun newSession() = BridgeSession(
            uid, host, info.applicationInfo.uid, component,
            requireNotNull(intent.action), connection, info.permission
        )

        var session = newSession()
        val proxy = Proxy.newProxyInstance(
            original.javaClass.classLoader,
            arrayOf(
                Class.forName(
                    "android.app.IServiceConnection",
                    false,
                    original.javaClass.classLoader
                )
            )
        ) { _, method, args ->
            if (method.name == "asBinder") return@newProxyInstance connection
            val forwarded = args?.copyOf()
            if (method.name == "connected" && forwarded != null) {
                val target = forwarded.getOrNull(1) as? IBinder
                try {
                    if (target == null) {
                        env.close(session)
                    } else if (forwarded[0] == component && env.host(uid) == host) {
                        if (!session.active.get()) {
                            session = newSession(); env.open(session)
                        }
                        val expected = if (session.action == BridgePolicy.TEMPLATE_ACTION)
                            BridgePolicy.CAR_DESCRIPTOR else BridgePolicy.BROWSER_DESCRIPTOR
                        if (target.interfaceDescriptor == expected) {
                            session.serviceDeath?.let { old ->
                                runCatching {
                                    session.service?.unlinkToDeath(
                                        old,
                                        0
                                    )
                                }
                            }
                            session.service = target
                            val current = session
                            val death = IBinder.DeathRecipient { env.close(current) }
                            current.serviceDeath = death
                            target.linkToDeath(death, 0)
                            forwarded[1] = CarServiceRelay(
                                target,
                                expected,
                                current
                            ) { current.enforceHost(env) }
                        } else {
                            env.close(session)
                            HookTools.log("Unsupported service interface for ${component.flattenToShortString()}")
                        }
                    } else env.close(session)
                } catch (e: Exception) {
                    env.close(session)
                    forwarded[1] = target
                    HookTools.failure("deliver car service", e)
                }
            }
            try {
                method.invoke(original, *(forwarded ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
        env.open(session)
        param.setObjectExtra("gearslip.session", session)
        env.bindScope.set(session)
        param.args[index] = proxy
    }
}
