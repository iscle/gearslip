package app.seb3thehacker.gearslip.systemhook

import android.os.Bundle
import android.os.Parcel

/** AndroidX Bundleable wire adapter; never loads classes from a third-party application. */
internal object HandshakeParcel {
    private const val CLASS = "androidx.car.app.HandshakeInfo"
    fun rewrite(bundle: Bundle, expectedHost: String): Bundle {
        require(bundle.getString("tag_class_name") == CLASS) { "Unknown handshake class" }
        val packageKey = CLASS + "mHostPackageName"
        val packageField = requireNotNull(bundle.getBundle(packageKey)) { "Missing host package" }
        require(packageField.getString("tag_value") == expectedHost) { "Unexpected host identity" }
        val api = requireNotNull(bundle.getBundle(CLASS + "mHostCarAppApiLevel"))
        require(api.getInt("tag_value", -1) in 1..8) { "Unsupported Car App API" }
        // The advertised package must match the system UID that forwards the handshake.
        return Bundle(bundle).apply {
            putBundle(packageKey, Bundle(packageField).apply { putString("tag_value", "android") })
        }
    }

    fun copyHandshake(input: Parcel, output: Parcel, host: String) {
        input.enforceInterface(BridgePolicy.CAR_DESCRIPTOR)
        require(input.readInt() == 1) { "Missing handshake" }
        val bundle = requireNotNull(input.readBundle(HandshakeParcel::class.java.classLoader))
        val callback = requireNotNull(input.readStrongBinder())
        // Reject layout changes rather than silently dropping fields from a newer protocol.
        require(input.dataAvail() == 0) { "Unexpected handshake fields" }
        output.writeInterfaceToken(BridgePolicy.CAR_DESCRIPTOR)
        output.writeInt(1)
        output.writeBundle(rewrite(bundle, host))
        output.writeStrongBinder(callback)
    }
}
