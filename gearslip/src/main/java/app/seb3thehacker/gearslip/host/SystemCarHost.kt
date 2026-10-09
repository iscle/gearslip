package app.seb3thehacker.gearslip.host

import android.content.Context

/** The system module advertises this feature only when its hooks are ready and this host is trusted. */
internal object SystemCarHost {
    private const val FEATURE = "app.seb3thehacker.gearslip.SYSTEM_CAR_HOST"

    fun isAvailable(context: Context): Boolean = try {
        // An installed module APK alone says nothing about its framework scope or enabled state.
        context.packageManager.hasSystemFeature(FEATURE)
    } catch (_: RuntimeException) {
        // Keep the normal app filtering if the system cannot answer the capability query.
        false
    }
}
