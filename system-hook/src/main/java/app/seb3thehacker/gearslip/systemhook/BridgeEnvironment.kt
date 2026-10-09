package app.seb3thehacker.gearslip.systemhook

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

// These queries use system_server's Context, not the installed module's package visibility.
@SuppressLint("QueryPermissionsNeeded", "PrivateApi")
internal class BridgeEnvironment(private val loader: ClassLoader) {
    private val internal = ThreadLocal.withInitial { false }
    val isInternal: Boolean get() = internal.get() == true
    val bindScope = ThreadLocal<BridgeSession?>()
    val sessions = ConcurrentHashMap.newKeySet<BridgeSession>()
    private val hosts = ConcurrentHashMap<Int, Pair<Long, String?>>()
    @Volatile var ready = false
    var onOpen: (BridgeSession) -> Unit = {}
    var onClose: (BridgeSession) -> Unit = {}
    val afterClose = CopyOnWriteArrayList<(BridgeSession) -> Unit>()

    val context: Context by lazy {
        val thread = HookTools.callStatic(
            HookTools.findClass("android.app.ActivityThread", loader), "currentActivityThread")
        HookTools.call(requireNotNull(thread), "getSystemContext") as Context
    }

    fun <T> inspect(block: (PackageManager) -> T): T {
        val previous = internal.get()
        internal.set(true)
        return try { HookTools.system { block(context.packageManager) } }
        finally { internal.set(previous) }
    }

    /** Package name alone never authorizes a caller; shared UIDs are deliberately excluded. */
    fun host(uid: Int): String? {
        if (uid < 10_000 || uid / 100_000 != 0) return null
        val now = SystemClock.elapsedRealtime()
        hosts[uid]?.takeIf { now - it.first < 2_000 }?.let { return it.second }
        val result = inspect { pm ->
            val packages = pm.getPackagesForUid(uid)?.toList().orEmpty()
            packages.singleOrNull()?.takeIf {
                it in BridgePolicy.hostPackages &&
                    pm.checkSignatures(it, BridgePolicy.MODULE) == PackageManager.SIGNATURE_MATCH
            }
        }
        hosts[uid] = now to result
        return result
    }

    fun eligible(intent: Intent, uid: Int): ServiceInfo? {
        if (uid / 100_000 != 0 || intent.action !in BridgePolicy.actions) return null
        val component = intent.component ?: return null
        return inspect { pm ->
            // Query by action/package too: explicit intents alone ignore intent filters.
            pm.queryIntentServices(Intent(intent.action).setPackage(component.packageName),
                PackageManager.MATCH_DISABLED_COMPONENTS)
                .map { it.serviceInfo }
                .firstOrNull {
                    it.name == component.className && it.exported &&
                        it.flags and ServiceInfo.FLAG_ISOLATED_PROCESS == 0 &&
                        it.applicationInfo.enabled && it.applicationInfo.uid >= 10_000 &&
                        BridgePolicy.sameUser(uid, it.applicationInfo.uid) &&
                        pm.getComponentEnabledSetting(component) !=
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                }
        }
    }

    fun isCarUid(uid: Int): Boolean = inspect { pm ->
        uid >= 10_000 && uid / 100_000 == 0 && pm.getPackagesForUid(uid).orEmpty().any { pkg ->
            BridgePolicy.actions.any { action ->
                pm.queryIntentServices(Intent(action).setPackage(pkg), PackageManager.MATCH_DISABLED_COMPONENTS)
                    .any { it.serviceInfo.exported && it.serviceInfo.applicationInfo.enabled }
            }
        }
    }

    fun readerIsActive(uid: Int) = sessions.any { it.active.get() && it.appUid == uid }
    fun activeComponent(component: ComponentName, uid: Int) =
        sessions.any { it.active.get() && it.appUid == uid && it.component == component }

    fun open(session: BridgeSession): Unit = synchronized(session) {
        check(session.active.get())
        sessions.add(session)
        try {
            val death = IBinder.DeathRecipient { close(session) }
            session.connection.linkToDeath(death, 0)
            session.connectionDeath = death
            invalidateIdentityCaches()
            onOpen(session)
        } catch (e: Exception) {
            close(session)
            throw e
        }
    }

    fun close(session: BridgeSession): Unit = synchronized(session) {
        if (!session.active.compareAndSet(true, false)) return
        sessions.remove(session)
        session.connectionDeath?.let { runCatching { session.connection.unlinkToDeath(it, 0) } }
        session.serviceDeath?.let { runCatching { session.service?.unlinkToDeath(it, 0) } }
        invalidateIdentityCaches()
        onClose(session)
        afterClose.forEach { it(session) }
    }

    private fun invalidateIdentityCaches() {
        // Invalidate synchronously, including warm bindings. Auto-corked PM invalidation
        // would leave a window where a handshake sees a previous host's cached identity.
        try {
            HookTools.system {
                val pm = HookTools.findClass("android.content.pm.PackageManager", loader)
                HookTools.call(HookTools.staticField(pm, "sPackageInfoCache"), "invalidateCache")
                val appPm = HookTools.findClass("android.app.ApplicationPackageManager", loader)
                HookTools.callStatic(appPm, "invalidateGetPackagesForUidCache")
            }
        } catch (e: Exception) { HookTools.failure("invalidate package identity caches", e) }
    }
}

internal class BridgeSession(
    val hostUid: Int,
    val hostPackage: String,
    val appUid: Int,
    val component: ComponentName,
    val action: String,
    val connection: IBinder,
    val permission: String?,
) {
    val active = AtomicBoolean(true)
    @Volatile var uriOwner: IBinder? = null
    @Volatile var service: IBinder? = null
    @Volatile var connectionDeath: IBinder.DeathRecipient? = null
    @Volatile var serviceDeath: IBinder.DeathRecipient? = null
    fun enforceHost(env: BridgeEnvironment) {
        check(active.get()) { "Car connection has closed" }
        if (Binder.getCallingUid() != hostUid || env.host(hostUid) != hostPackage)
            throw SecurityException("Car bridge belongs to a different host")
    }
}
