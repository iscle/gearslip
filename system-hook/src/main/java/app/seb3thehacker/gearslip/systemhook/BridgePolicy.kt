package app.seb3thehacker.gearslip.systemhook

/** Protocol selection and policy contain no app-specific class names. */
internal object BridgePolicy {
    const val MODULE = "app.seb3thehacker.gearslip.systemhook"
    const val FEATURE = "app.seb3thehacker.gearslip.SYSTEM_CAR_HOST"
    const val GOOGLE_HOST = "com.google.android.projection.gearhead"
    const val TEMPLATE_ACTION = "androidx.car.app.CarAppService"
    const val BROWSER_ACTION = "android.media.browse.MediaBrowserService"
    const val CAR_DESCRIPTOR = "androidx.car.app.ICarApp"
    const val BROWSER_DESCRIPTOR = "android.service.media.IMediaBrowserService"
    const val CALLBACK_DESCRIPTOR = "android.service.media.IMediaBrowserServiceCallbacks"
    const val MESSENGER_DESCRIPTOR = "android.os.IMessenger"
    val hostPackages = setOf("app.seb3thehacker.gearslip", "app.seb3thehacker.gearslip.dev")
    val googleHosts = setOf(GOOGLE_HOST, "com.google.android.apps.automotive.templates.host")
    val actions = setOf(TEMPLATE_ACTION, BROWSER_ACTION)

    fun sameUser(first: Int, second: Int) = first / 100_000 == second / 100_000
    // Only ordinary self-component operations can be replayed with system identity.
    fun canDeferComponentSetting(user: Int, state: Int, flags: Int) =
        user == 0 && state in 0..2 && flags in 0..1
    fun allowedTransaction(descriptor: String, code: Int): Boolean = when (descriptor) {
        CAR_DESCRIPTOR -> code in 2..12 || code == 16_777_215
        BROWSER_DESCRIPTOR -> code in 1..7
        MESSENGER_DESCRIPTOR -> code == 1
        else -> false
    }
}
