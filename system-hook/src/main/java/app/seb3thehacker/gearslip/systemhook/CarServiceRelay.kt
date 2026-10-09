package app.seb3thehacker.gearslip.systemhook

import android.media.session.MediaSession
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Parcel
import java.util.concurrent.ConcurrentHashMap

/** Only fixed car IPC interfaces are forwarded. Rendering and playback stay in Gearslip. */
internal class CarServiceRelay(
    private val target: IBinder,
    private val descriptor: String,
    private val session: BridgeSession,
    private val authorize: () -> Unit,
) : Binder() {
    private val callbacks = ConcurrentHashMap<IBinder, IBinder>()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        authorize()
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(descriptor)
            return true
        }
        require(BridgePolicy.allowedTransaction(descriptor, code)) { "Unsupported car transaction $code" }
        val output = Parcel.obtain()
        try {
            when {
                descriptor == BridgePolicy.CAR_DESCRIPTOR && code == 11 ->
                    HandshakeParcel.copyHandshake(data, output, session.hostPackage)
                descriptor == BridgePolicy.BROWSER_DESCRIPTOR -> copyBrowser(code, data, output)
                descriptor == BridgePolicy.MESSENGER_DESCRIPTOR -> copyMessage(data, output)
                else -> output.appendFrom(data, 0, data.dataSize())
            }
            output.setDataPosition(0)
            return HookTools.system { target.transact(code, output, reply, flags) }
        } finally { output.recycle() }
    }

    private fun callback(original: IBinder): IBinder = callbacks.getOrPut(original) {
        object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (!session.active.get() || Binder.getCallingUid() != session.appUid)
                    throw SecurityException("Unexpected media callback sender")
                if (code == INTERFACE_TRANSACTION) {
                    reply?.writeString(BridgePolicy.CALLBACK_DESCRIPTOR)
                    return true
                }
                require(code in 1..4) { "Unsupported browser callback" }
                if (code != 1) return original.transact(code, data, reply, flags)
                data.enforceInterface(BridgePolicy.CALLBACK_DESCRIPTOR)
                val root = data.readString()
                val token = data.readTypedObject(MediaSession.Token.CREATOR)
                val extras = data.readTypedObject(Bundle.CREATOR)
                require(data.dataAvail() == 0)
                extras?.getBinder("extra_messenger")?.let {
                    extras.putBinder("extra_messenger", CarServiceRelay(it,
                        BridgePolicy.MESSENGER_DESCRIPTOR, session, authorize))
                }
                val output = Parcel.obtain()
                try {
                    output.writeInterfaceToken(BridgePolicy.CALLBACK_DESCRIPTOR)
                    output.writeString(root)
                    output.writeTypedObject(token, 0)
                    output.writeTypedObject(extras, 0)
                    output.setDataPosition(0)
                    return original.transact(code, output, reply, flags)
                } finally { output.recycle() }
            }
        }
    }

    private fun copyBrowser(code: Int, input: Parcel, output: Parcel) {
        input.enforceInterface(BridgePolicy.BROWSER_DESCRIPTOR)
        output.writeInterfaceToken(BridgePolicy.BROWSER_DESCRIPTOR)
        if (code == 1) {
            require(input.readString() == session.hostPackage) { "Unexpected browser package" }
            val hints = input.readTypedObject(Bundle.CREATOR)
            val original = requireNotNull(input.readStrongBinder())
            require(input.dataAvail() == 0)
            output.writeString(BridgePolicy.GOOGLE_HOST)
            output.writeTypedObject(hints, 0)
            output.writeStrongBinder(callback(original))
        } else {
            // The callback is always the last argument. Copy preceding fields with their
            // Binder object offsets intact (never marshall/unmarshall a Binder parcel).
            when (code) {
                2 -> Unit
                3, 4 -> input.readString()
                5 -> { input.readString(); input.readTypedObject(android.os.ResultReceiver.CREATOR) }
                6 -> { input.readString(); input.readStrongBinder(); input.readTypedObject(Bundle.CREATOR) }
                7 -> { input.readString(); input.readStrongBinder() }
            }
            val callbackOffset = input.dataPosition()
            val original = requireNotNull(input.readStrongBinder())
            require(input.dataAvail() == 0)
            output.setDataSize(0)
            output.setDataPosition(0)
            output.appendFrom(input, 0, callbackOffset)
            output.writeStrongBinder(callbacks[original] ?: throw SecurityException("Browser not connected"))
            if (code == 2) callbacks.remove(original)
        }
    }

    private fun copyMessage(input: Parcel, output: Parcel) {
        input.enforceInterface(BridgePolicy.MESSENGER_DESCRIPTOR)
        val message = requireNotNull(input.readTypedObject(Message.CREATOR))
        try {
            require(input.dataAvail() == 0)
            // MediaBrowserCompat CONNECT / REGISTER_CALLBACK_MESSENGER carry the package.
            if (message.what == 1 || message.what == 6) {
                val pkg = message.data.getString("data_package_name")
                require(pkg == null || pkg == session.hostPackage)
                message.data.putString("data_package_name", BridgePolicy.GOOGLE_HOST)
            }
            require(message.what in 1..9) { "Unknown media-browser message" }
            output.writeInterfaceToken(BridgePolicy.MESSENGER_DESCRIPTOR)
            output.writeTypedObject(message, 0)
        } finally { message.recycle() }
    }
}
