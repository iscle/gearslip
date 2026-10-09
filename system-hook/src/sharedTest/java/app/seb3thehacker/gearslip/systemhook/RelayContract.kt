package app.seb3thehacker.gearslip.systemhook

import android.content.ComponentName
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Parcel
import android.os.Process
import androidx.car.app.HandshakeInfo
import androidx.car.app.serialization.Bundleable
import org.junit.Assert.*
import org.junit.Test

abstract class RelayContract {
    private val host = "app.seb3thehacker.gearslip.dev"
    private fun session() = BridgeSession(
        Process.myUid(),
        host,
        Process.myUid(),
        ComponentName("test.car.app", "test.car.app.Service"),
        BridgePolicy.TEMPLATE_ACTION,
        Binder(),
        null
    )

    @Test
    fun realAndroidXHandshakePreservesApiAndCallback() {
        val callback = Binder()
        var received = false
        val target = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(11, code)
                data.enforceInterface(BridgePolicy.CAR_DESCRIPTOR)
                val handshake = data.readTypedObject(Bundleable.CREATOR)!!.get() as HandshakeInfo
                assertEquals("android", handshake.hostPackageName)
                assertEquals(8, handshake.hostCarAppApiLevel)
                assertSame(callback, data.readStrongBinder())
                assertEquals(0, data.dataAvail())
                received = true
                return true
            }
        }
        val relay = CarServiceRelay(target, BridgePolicy.CAR_DESCRIPTOR, session()) {}
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(BridgePolicy.CAR_DESCRIPTOR)
            data.writeTypedObject(Bundleable.create(HandshakeInfo(host, 8)), 0)
            data.writeStrongBinder(callback)
            assertTrue(relay.transact(11, data, null, IBinder.FLAG_ONEWAY))
            assertTrue(received)
        } finally {
            data.recycle()
        }
    }

    @Test
    fun browserDisconnectUsesTheSameWrappedCallback() {
        var wrapped: IBinder? = null
        val original = Binder()
        val target = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(BridgePolicy.BROWSER_DESCRIPTOR)
                when (code) {
                    1 -> {
                        assertEquals(BridgePolicy.GOOGLE_HOST, data.readString())
                        assertEquals(
                            "kept",
                            data.readTypedObject(Bundle.CREATOR)!!.getString("hint")
                        )
                        wrapped = data.readStrongBinder()
                        assertNotSame(original, wrapped)
                    }

                    2 -> assertSame(wrapped, data.readStrongBinder())
                }
                assertEquals(0, data.dataAvail())
                return true
            }
        }
        val relay = CarServiceRelay(target, BridgePolicy.BROWSER_DESCRIPTOR, session()) {}
        for (code in 1..2) {
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(BridgePolicy.BROWSER_DESCRIPTOR)
                if (code == 1) {
                    data.writeString(host)
                    data.writeTypedObject(Bundle().apply { putString("hint", "kept") }, 0)
                }
                data.writeStrongBinder(original)
                assertTrue(relay.transact(code, data, null, IBinder.FLAG_ONEWAY))
            } finally {
                data.recycle()
            }
        }
    }

    @Test
    fun unauthorizedCallerNeverReachesTarget() {
        var forwarded = false
        val target = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                forwarded = true
                return true
            }
        }
        val relay = CarServiceRelay(target, BridgePolicy.CAR_DESCRIPTOR, session()) {
            throw SecurityException("wrong UID")
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            relay.transact(10, data, reply, 0)
            assertThrows(SecurityException::class.java) { reply.readException() }
            assertFalse(forwarded)
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    @Test
    fun unknownHandshakeAndWrongClaimedHostAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            HandshakeParcel.rewrite(
                Bundle(),
                host
            )
        }
        val data = Parcel.obtain()
        try {
            Bundleable.create(HandshakeInfo("untrusted.package", 8)).writeToParcel(data, 0)
            data.setDataPosition(0)
            val bundle = data.readBundle(javaClass.classLoader)!!
            assertThrows(IllegalArgumentException::class.java) {
                HandshakeParcel.rewrite(
                    bundle,
                    host
                )
            }
        } finally {
            data.recycle()
        }
    }

    @Test
    fun subscriptionsAndItemsPreserveArgumentsAndCallbackIdentity() {
        val original = Binder()
        val subscription = Binder()
        var wrapped: IBinder? = null
        var calls = 0
        val target = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(BridgePolicy.BROWSER_DESCRIPTOR)
                when (code) {
                    1 -> {
                        data.readString(); data.readTypedObject(Bundle.CREATOR)
                        wrapped = data.readStrongBinder()
                    }

                    else -> {
                        assertEquals("media-id", data.readString())
                        if (code == 5) assertNull(data.readTypedObject(android.os.ResultReceiver.CREATOR))
                        if (code == 6 || code == 7) assertSame(
                            subscription,
                            data.readStrongBinder()
                        )
                        if (code == 6) assertEquals(
                            7,
                            data.readTypedObject(Bundle.CREATOR)!!.getInt("page")
                        )
                        assertSame(wrapped, data.readStrongBinder())
                        calls++
                    }
                }
                assertEquals(0, data.dataAvail())
                return true
            }
        }
        val relay = CarServiceRelay(target, BridgePolicy.BROWSER_DESCRIPTOR, session()) {}
        for (code in listOf(1, 3, 4, 5, 6, 7)) {
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(BridgePolicy.BROWSER_DESCRIPTOR)
                if (code == 1) {
                    data.writeString(host); data.writeTypedObject<Bundle>(null, 0)
                } else {
                    data.writeString("media-id")
                    if (code == 5) data.writeTypedObject<android.os.ResultReceiver>(null, 0)
                    if (code == 6 || code == 7) data.writeStrongBinder(subscription)
                    if (code == 6) data.writeTypedObject(Bundle().apply { putInt("page", 7) }, 0)
                }
                data.writeStrongBinder(original)
                relay.transact(code, data, null, IBinder.FLAG_ONEWAY)
            } finally {
                data.recycle()
            }
        }
        assertEquals(5, calls)
    }

    @Test
    fun returnedCompatMessengerIsMediatedForRegistrationAndSearch() {
        var messengerFromHost: IBinder? = null
        var calls = 0
        val messengerFromApp = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(BridgePolicy.MESSENGER_DESCRIPTOR)
                val message = data.readTypedObject(Message.CREATOR)!!
                if (message.what == 6) assertEquals(
                    BridgePolicy.GOOGLE_HOST,
                    message.data.getString("data_package_name")
                )
                if (message.what == 8) assertEquals(
                    "song",
                    message.data.getString("data_search_query")
                )
                message.recycle()
                calls++
                return true
            }
        }
        val hostCallback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(BridgePolicy.CALLBACK_DESCRIPTOR)
                assertEquals("root", data.readString())
                data.readTypedObject(android.media.session.MediaSession.Token.CREATOR)
                messengerFromHost =
                    data.readTypedObject(Bundle.CREATOR)!!.getBinder("extra_messenger")
                return true
            }
        }
        val app = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(BridgePolicy.BROWSER_DESCRIPTOR)
                data.readString(); data.readTypedObject(Bundle.CREATOR)
                val callback = data.readStrongBinder()!!
                val response = Parcel.obtain()
                try {
                    response.writeInterfaceToken(BridgePolicy.CALLBACK_DESCRIPTOR)
                    response.writeString("root")
                    response.writeTypedObject<android.media.session.MediaSession.Token>(null, 0)
                    response.writeTypedObject(Bundle().apply {
                        putBinder(
                            "extra_messenger",
                            messengerFromApp
                        )
                    }, 0)
                    callback.transact(1, response, null, IBinder.FLAG_ONEWAY)
                } finally {
                    response.recycle()
                }
                return true
            }
        }
        val relay = CarServiceRelay(app, BridgePolicy.BROWSER_DESCRIPTOR, session()) {}
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(BridgePolicy.BROWSER_DESCRIPTOR)
            data.writeString(host); data.writeTypedObject<Bundle>(null, 0); data.writeStrongBinder(
                hostCallback
            )
            relay.transact(1, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }
        assertNotNull(messengerFromHost)
        assertNotSame(messengerFromApp, messengerFromHost)
        for (what in listOf(6, 8)) {
            val request = Parcel.obtain()
            val message = Message.obtain().apply {
                this.what = what
                this.data = Bundle().apply {
                    putString("data_package_name", host)
                    putString("data_search_query", "song")
                }
            }
            try {
                request.writeInterfaceToken(BridgePolicy.MESSENGER_DESCRIPTOR)
                request.writeTypedObject(message, 0)
                messengerFromHost!!.transact(1, request, null, IBinder.FLAG_ONEWAY)
            } finally {
                request.recycle(); message.recycle()
            }
        }
        assertEquals(2, calls)
    }
}
