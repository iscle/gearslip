package app.seb3thehacker.gearslip.systemhook

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Binder
import android.os.HandlerThread
import android.os.IBinder
import java.util.LinkedHashMap

/** Mirrors only grants a car app actually issues to a Google host, with a session owner. */
internal class UriGrantHooks(private val env: BridgeEnvironment, private val loader: ClassLoader) {
    private data class Grant(
        val uid: Int,
        val sourcePackage: String,
        val uri: Uri,
        val sourceUser: Int,
        val flags: Int,
        val owner: Any?
    )

    private val grants = LinkedHashMap<String, Grant>()
    private val worker by lazy { Handler(HandlerThread("GearslipUriGrants").apply { start() }.looper) }
    @Volatile
    private var service: Any? = null

    private fun enqueue(block: () -> Unit) {
        worker.post {
            try {
                block()
            } catch (e: Exception) {
                HookTools.failure("URI grant worker", e)
            }
        }
    }

    fun install(): Boolean {
        env.onOpen = { session -> replay(session) }
        // Avoid grant-manager cleanup while running inside a bind or Binder-death callback.
        env.onClose = { session -> enqueue { revoke(session) } }
        val clazz = "com.android.server.uri.UriGrantsManagerService"
        val ams = "com.android.server.am.ActivityManagerService"
        val contextGrants = HookTools.hook(loader, ams, "grantUriPermission", before = { p ->
            if (env.isInternal || !env.ready || p.args.size != 5) return@hook
            // Context.grantUriPermission uses the Intent grant path, not the owner path.
            // Intercept before AMS filters out a Google host which isn't installed.
            observe(
                Binder.getCallingUid(), p.args[1] as? String, p.args[2] as? Uri,
                p.args[3] as Int, p.args[4] as Int, null
            )
        })
        HookTools.hook(loader, ams, "revokeUriPermission", after = { p ->
            if (env.isInternal || p.hasThrowable() || p.args.size != 5) return@hook
            if ((p.args[3] as Int) and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return@hook
            val uri = p.args[2] as? Uri ?: return@hook
            remove(Binder.getCallingUid(), uri)
        })
        val count = HookTools.hook(loader, clazz, "grantUriPermissionUnlocked", before = { p ->
            if (env.isInternal || !env.ready || p.args.size != 6) return@hook
            if (p.args[1] !in BridgePolicy.googleHosts) return@hook
            service = p.thisObject
            val uid = p.args[0] as Int
            val flags = p.args[3] as Int
            if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0 || p.args[5] != 0) return@hook
            val grantUri = p.args[2] ?: return@hook
            val uri = HookTools.field(grantUri, "uri") as Uri
            val user = HookTools.field(grantUri, "sourceUserId") as Int
            observe(uid, p.args[1] as? String, uri, flags, user, p.args[4])
        })
        HookTools.hook(loader, clazz, "revokeUriPermission", after = { p ->
            if (env.isInternal || p.hasThrowable() || p.args.size != 4) return@hook
            if ((p.args[3] as Int) and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return@hook
            val uid = p.args[1] as Int
            val uri = HookTools.field(p.args[2], "uri") as Uri
            remove(uid, uri)
        })
        HookTools.hook(loader, clazz, "removeUriPermissionsForPackageLocked", after = { p ->
            if (env.isInternal || p.hasThrowable()) return@hook
            val pkg = p.args.firstOrNull() as? String ?: return@hook
            synchronized(grants) { grants.entries.removeAll { it.value.sourcePackage == pkg } }
        })
        // All owner cleanup delegates to the most specific removeUriPermission overload.
        val ownerClass = "com.android.server.uri.UriPermissionOwner"
        val ownerArity = HookTools.findClass(ownerClass, loader).declaredMethods
            .filter { it.name == "removeUriPermission" }.maxOfOrNull { it.parameterCount } ?: 0
        val ownerHooks = HookTools.hook(loader, ownerClass, "removeUriPermission", after = { p ->
            if (env.isInternal || p.hasThrowable()) return@hook
            if (p.args.size != ownerArity || p.args.size < 2) return@hook
            if ((p.args[1] as Int) and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return@hook
            val target = p.args.getOrNull(2) as? String
            if (target != null && target !in BridgePolicy.googleHosts) return@hook
            val targetUser = p.args.getOrNull(3) as? Int
            if (targetUser != null && targetUser != 0 && targetUser != -1) return@hook
            val uri = p.args[0]?.let { HookTools.field(it, "uri") as Uri }
            val owner = p.thisObject
            enqueue {
                val affected = synchronized(grants) {
                    fun matches(grant: Grant) =
                        grant.owner === owner && (uri == null || grant.uri == uri)

                    val uids = grants.values.filter(::matches).map { it.uid }.toSet()
                    grants.entries.removeAll { matches(it.value) }
                    uids
                }
                affected.forEach(::refresh)
            }
        })
        return count > 0 && contextGrants > 0 && ownerHooks > 0
    }

    private fun observe(
        uid: Int,
        target: String?,
        uri: Uri?,
        flags: Int,
        user: Int,
        sourceOwner: Any?
    ) {
        if (target !in BridgePolicy.googleHosts || user != 0 || uri?.scheme != "content" ||
            flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0 || !env.isCarUid(uid)
        ) return
        val provider = env.inspect { it.resolveContentProvider(uri.authority ?: "", 0) }
        // A car app may mirror only its own provider's grantable content.
        if (provider?.applicationInfo?.uid != uid || !provider.grantUriPermissions) return
        val grant = Grant(
            uid,
            provider.packageName,
            uri,
            user,
            // Artwork needs read access; never carry write or persistable privileges across.
            flags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION),
            sourceOwner
        )
        synchronized(grants) {
            val key = "$uid:$uri:${System.identityHashCode(sourceOwner)}"
            grants[key] = grant
            // Retain pre-bind artwork grants without allowing an unbounded system-server cache.
            while (grants.size > 1024) grants.remove(grants.keys.first())
        }
        // Complete these before the app publishes a template using its image URIs.
        env.sessions.filter { it.appUid == uid }.forEach { mirror(it, grant) }
    }

    private fun remove(uid: Int, uri: Uri) {
        // Prune history before replay so a reconnect cannot restore explicitly revoked access.
        synchronized(grants) {
            grants.entries.removeAll { (_, grant) ->
                grant.uid == uid && overlaps(
                    uri,
                    grant.uri
                )
            }
        }
        enqueue { refresh(uid) }
    }

    private fun manager(): Any = service ?: env.inspect {
        val serviceManager = HookTools.findClass("android.os.ServiceManager", loader)
        val binder = HookTools.callStatic(serviceManager, "getService", "uri_grants")
        val stub = HookTools.findClass("android.app.IUriGrantsManager\$Stub", loader)
        requireNotNull(HookTools.callStatic(stub, "asInterface", binder)).also { service = it }
    }

    private fun local(): Any {
        val services = HookTools.findClass("com.android.server.LocalServices", loader)
        val type = HookTools.findClass("com.android.server.uri.UriGrantsManagerInternal", loader)
        return requireNotNull(HookTools.callStatic(services, "getService", type))
    }

    private fun replay(session: BridgeSession) {
        synchronized(grants) { grants.values.filter { it.uid == session.appUid } }
            .forEach { mirror(session, it) }
    }

    // Serialize owner creation with close/revoke so cleanup cannot miss a newly created grant.
    private fun mirror(session: BridgeSession, grant: Grant): Unit = synchronized(session) {
        if (!session.active.get() || env.host(session.hostUid) != session.hostPackage) return
        try {
            env.inspect {
                // A separate owner lets disconnect revoke only this connection's mirrored grants.
                val owner = session.uriOwner ?: (HookTools.call(
                    local(), "newUriPermissionOwner",
                    "Gearslip:${session.hostUid}:${session.component.flattenToShortString()}"
                ) as IBinder)
                    .also { session.uriOwner = it }
                // Pass the source UID through Android's grant validation instead of exporting the provider.
                HookTools.call(
                    manager(),
                    "grantUriPermissionFromOwner",
                    owner,
                    grant.uid,
                    session.hostPackage,
                    grant.uri,
                    grant.flags,
                    grant.sourceUser,
                    session.hostUid / 100_000
                )
            }
        } catch (e: Exception) {
            HookTools.failure("mirror artwork grant", e)
        }
    }

    private fun revoke(session: BridgeSession): Unit = synchronized(session) {
        val owner = session.uriOwner ?: return
        session.uriOwner = null
        try {
            env.inspect {
                HookTools.call(
                    local(), "revokeUriPermissionFromOwner", owner, null,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION, 0
                )
            }
        } catch (e: Exception) {
            HookTools.failure("revoke artwork grant", e)
        }
    }

    private fun refresh(uid: Int) {
        env.sessions.filter { it.appUid == uid }.forEach { revoke(it); replay(it) }
    }

    private fun overlaps(first: Uri, second: Uri): Boolean = first.authority == second.authority &&
            (first.pathSegments.take(second.pathSegments.size) == second.pathSegments ||
                    second.pathSegments.take(first.pathSegments.size) == first.pathSegments)
}
