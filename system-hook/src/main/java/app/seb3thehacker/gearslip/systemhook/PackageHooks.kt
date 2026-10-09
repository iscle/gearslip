package app.seb3thehacker.gearslip.systemhook

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Parcel
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentHashMap

/** Caller-scoped views only; installed packages, signatures and saved settings are untouched. */
internal class PackageHooks(private val env: BridgeEnvironment) {
    private val deferred = ConcurrentHashMap<ComponentName, () -> Unit>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    fun install(loader: ClassLoader): Boolean {
        env.afterClose += { session ->
            handler.post {
                // Keep a shared service available until its last bridge connection closes.
                if (!env.activeComponent(session.component, session.appUid)) {
                    deferred.remove(session.component)?.let { action ->
                        try {
                            env.inspect { pm ->
                                // A later user disable must take precedence over the app's deferred request.
                                if (pm.getApplicationInfo(
                                        session.component.packageName,
                                        0
                                    ).enabled &&
                                    pm.getComponentEnabledSetting(session.component) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                                )
                                    action()
                            }
                        } catch (e: Exception) {
                            HookTools.failure("restore component request", e)
                        }
                    }
                }
            }
        }
        var identityHooks = 0
        var packageInfoHooks = 0
        var discoveryHooks = 0
        var serviceInfoHooks = 0
        var featureHooks = 0
        var componentSettingHooks = 0
        var packageUidHooks = 0
        // Newer Android versions move package queries from PMS into ComputerEngine snapshots.
        for (className in listOf(
            "com.android.server.pm.PackageManagerService",
            "com.android.server.pm.ComputerEngine"
        )) {
            // Keep package lists, package metadata and UID lookups consistent for active car apps.
            identityHooks += HookTools.hook(loader, className, "getPackagesForUid", after = { p ->
                if (activeReader() && p.args.firstOrNull() == 1000 && !p.hasThrowable()) {
                    @Suppress("UNCHECKED_CAST")
                    val packages = (p.result as? Array<String>).orEmpty()
                    p.result =
                        (packages.toList() + BridgePolicy.GOOGLE_HOST).distinct().toTypedArray()
                }
            })
            packageInfoHooks += HookTools.hook(loader, className, "getPackageInfo", after = { p ->
                if (activeReader() && p.args.firstOrNull() == BridgePolicy.GOOGLE_HOST &&
                    p.args.lastOrNull() == 0 && !p.hasThrowable()
                ) {
                    val flags = (p.args[1] as Number).toLong()
                    p.result = systemAlias(flags)
                }
            })
            packageUidHooks += HookTools.hook(loader, className, "getPackageUid", after = { p ->
                if (activeReader() && p.args.firstOrNull() == BridgePolicy.GOOGLE_HOST &&
                    p.args.lastOrNull() == 0 && !p.hasThrowable()
                ) p.result = 1000
            })
            for (query in listOf("queryIntentServices", "queryIntentServicesInternal")) {
                discoveryHooks += HookTools.hook(
                    loader,
                    className,
                    query,
                    before = { p -> beforeQuery(p) },
                    after = { p -> afterQuery(p) })
            }
            serviceInfoHooks += HookTools.hook(loader, className, "getServiceInfo", before = { p ->
                val session = env.bindScope.get() ?: return@hook
                if (env.isInternal || p.args.firstOrNull() != session.component) return@hook
                addDisabledFlag(p, 1)
                p.setObjectExtra("gearslip.service", true)
            }, after = { p ->
                if (p.getObjectExtra("gearslip.service") == true && !p.hasThrowable()) {
                    (p.result as? ServiceInfo)?.let {
                        p.result = ServiceInfo(it).apply { enabled = true }
                    }
                }
            })
        }
        for (className in listOf(
            "com.android.server.pm.PackageManagerService",
            "com.android.server.pm.PackageManagerService\$IPackageManagerImpl",
            "com.android.server.pm.IPackageManagerBase"
        )) {
            featureHooks += HookTools.hook(loader, className, "hasSystemFeature", before = { p ->
                if (!env.isInternal && env.ready && p.args.firstOrNull() == BridgePolicy.FEATURE &&
                    env.host(Binder.getCallingUid()) != null
                ) p.result = true
            })
            componentSettingHooks += HookTools.hook(
                loader,
                className,
                "setComponentEnabledSetting",
                before = { p ->
                    if (env.isInternal || !env.ready) return@hook
                    val component = p.args.firstOrNull() as? ComponentName ?: return@hook
                    if (p.args.getOrNull(3) != 0) return@hook
                    deferred.remove(component)
                    if (!BridgePolicy.canDeferComponentSetting(
                            p.args[3] as Int,
                            p.args[1] as Int,
                            p.args[2] as Int
                        )
                    )
                        return@hook
                    // An app may disable its car service while in use; postpone that self-request.
                    if (env.activeComponent(component, Binder.getCallingUid())) {
                        defer(component, p, p.args.clone())
                        p.result = null
                    }
                })
            HookTools.hook(loader, className, "setComponentEnabledSettings", before = { p ->
                if (env.isInternal || !env.ready) return@hook
                val settings = p.args.firstOrNull() as? List<*> ?: return@hook
                if (p.args.getOrNull(1) != 0) return@hook
                val caller = Binder.getCallingUid()
                // Defer only active components; unrelated entries retain normal framework handling.
                val remaining = settings.filter { entry ->
                    val component =
                        entry?.let { HookTools.call(it, "getComponentName") } as? ComponentName
                    if (component != null) deferred.remove(component)
                    val ordinary = entry != null && BridgePolicy.canDeferComponentSetting(
                        0,
                        HookTools.call(entry, "getEnabledState") as Int,
                        HookTools.call(entry, "getEnabledFlags") as Int
                    )
                    val keep =
                        component == null || !ordinary || !env.activeComponent(component, caller)
                    if (!keep) defer(
                        requireNotNull(component),
                        p,
                        p.args.clone().apply { this[0] = listOf(entry) })
                    keep
                }
                if (remaining.size != settings.size) {
                    if (remaining.isEmpty()) p.result = null else p.args[0] = remaining
                }
            })
        }
        return identityHooks > 0 && packageInfoHooks > 0 && discoveryHooks > 0 &&
                serviceInfoHooks > 0 && featureHooks > 0 && componentSettingHooks > 0 && packageUidHooks > 0
    }

    private fun activeReader() =
        !env.isInternal && env.ready && env.readerIsActive(Binder.getCallingUid())

    private fun defer(
        component: ComponentName,
        p: XC_MethodHook.MethodHookParam,
        args: Array<Any?>
    ) {
        val method = p.method
        val receiver = p.thisObject
        // Replay the saved request without passing through our deferral hook again.
        deferred[component] = { XposedBridge.invokeOriginalMethod(method, receiver, args); Unit }
    }

    private fun systemAlias(flags: Long): PackageInfo = env.inspect { pm ->
        @Suppress("DEPRECATION")
        val platform = pm.getPackageInfo("android", flags.toInt())
        // Deep-copy nested signing and application data to avoid mutating cached framework objects.
        val parcel = Parcel.obtain()
        val result = try {
            platform.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            PackageInfo.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
        result.apply {
            packageName = BridgePolicy.GOOGLE_HOST
            applicationInfo = applicationInfo?.let {
                ApplicationInfo(it).apply {
                    packageName = BridgePolicy.GOOGLE_HOST
                }
            }
            // Identity is the actual relay's UID and signing information, not a Google
            // certificate. The inspected validators explicitly trust the system UID.
            setLongVersionCode(144_000_000L)
            versionName = "Gearslip system bridge"
        }
    }

    private fun beforeQuery(p: XC_MethodHook.MethodHookParam) {
        if (env.isInternal || !env.ready) return
        val intent = p.args.firstOrNull() as? Intent ?: return
        if (intent.action !in BridgePolicy.actions) return
        val uid = Binder.getCallingUid()
        val session = env.bindScope.get()
        // Preserve the normal media-browser preference when an app also declares an
        // experimental, disabled Media3 service. Only template discovery is expanded.
        if (session == null && intent.action != BridgePolicy.TEMPLATE_ACTION) return
        if (session == null && env.host(uid) == null) return
        if (session != null && intent.component != session.component) return
        if (p.args.getOrNull(3) != 0) return
        addDisabledFlag(p, 2)
        p.setObjectExtra("gearslip.query", true)
    }

    private fun afterQuery(p: XC_MethodHook.MethodHookParam) {
        if (p.getObjectExtra("gearslip.query") != true || p.hasThrowable()) return
        val original = p.result ?: return
        val list =
            (if (original is List<*>) original else HookTools.call(original, "getList")) as? List<*>
                ?: return
        val result = list.filterIsInstance<ResolveInfo>().filter { ri ->
            val info = ri.serviceInfo ?: return@filter false
            info.exported && info.applicationInfo.enabled && env.inspect { pm ->
                pm.getComponentEnabledSetting(ComponentName(info.packageName, info.name)) !=
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            }
        }.map { ri ->
            ResolveInfo(ri).apply {
                serviceInfo = ServiceInfo(ri.serviceInfo).apply { enabled = true }
            }
        }
        p.result =
            if (original is List<*>) result else original.javaClass.getConstructor(List::class.java)
                .newInstance(result)
    }

    private fun addDisabledFlag(p: XC_MethodHook.MethodHookParam, index: Int) {
        val flags = p.args[index] as Number
        val value = flags.toLong() or PackageManager.MATCH_DISABLED_COMPONENTS.toLong()
        // Framework flag parameters changed from Int to Long across supported Android versions.
        p.args[index] = if (flags is Long) value else value.toInt()
    }
}
