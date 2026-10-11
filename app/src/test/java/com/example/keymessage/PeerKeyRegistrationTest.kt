package com.example.keymessage

import android.content.SharedPreferences
import com.km.crypto.Ed25519Impl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 2.1 / D2 (R2/S7a): registrar la clave de firma Ed25519 del contacto en el
 * momento del alta deja una entrada real en `km_peers`, la devuelve
 * `peersWithKnownKey()`/`known()` y el `peerPublicKeyResolver` la resuelve a
 * los 32 bytes para verificar los `ACK` entrantes.
 *
 * Se prueba la cadena EXACTA que `AppContainer` ejecuta en produccion:
 *
 *     1. `ContactIdentity.identityIdOf(publicKey)` — deriva el `IdentityId` de
 *        64 hex de la clave, igual que `PeerKeyDirectory.register` del
 *        escritorio exige que `identityId = SHA-256("KM-ID-IDENTITY" || clave)`.
 *     2. `PeerSigningKeys.put(id, publicKey)` — lo que hace
 *        `AppContainer.registerPeerKey` sobre `km_peers.xml`.
 *     3. `PeerSigningKeys.signingKeyOf(id)` — la lectura del directorio.
 *     4. `resolvePeerSigningKey(id, ...)` — el resolver que `AppContainer`
 *        entrega al core.
 *
 * `AppContainer` no se puede construir fuera del dispositivo (Application,
 * Room, KeyMessageCoreImpl), asi que el test sustituye solo el `SharedPreferences`
 * real por uno en memoria con la MISMA semantica (put/apply/get); `PeerSigningKeys`
 * y el resto de la cadena son el codigo de produccion sin cambios.
 */
class PeerKeyRegistrationTest {

    private val ed25519 = Ed25519Impl()

    @Test
    fun `registrar la clave del contacto deja una entrada en km_peers y la resuelve - S7a`() {
        val prefs = InMemorySharedPreferences()
        val directory = PeerSigningKeys(prefs)
        val publicKey = ed25519.generateKeyPair().publicKey
        val base64 = Base64.getEncoder().encodeToString(publicKey)

        // 1. El IdentityId de relevo se deriva de la clave publica (KM-ID-0001.
        //    idem PeerKeyDirectory del escritorio).
        val identityId = ContactIdentity.identityIdOf(base64)
        assertEquals("la clave deriva un id de 64 hex", 64, identityId!!.value.length)

        // 2. registerPeerKey(id, publicKey) tal cual AppContainer lo llama.
        directory.put(identityId, base64)

        // Aceptacion: km_peers.xml gana una entrada para ese peer.
        assertEquals("el fichero de peers es km_peers", "km_peers", PeerSigningKeys.PREFS_NAME)
        assertTrue("km_peers tiene la entrada del peer", prefs.contains(identityId.value))

        // Aceptacion: peersWithKnownKey() la devuelve.
        assertTrue("peer registrado en peersWithKnownKey", identityId in directory.known())

        // Aceptacion: el resolver devuelve la clave para verificar los ACK.
        val resolved = resolvePeerSigningKey(identityId, directory.signingKeyOf(identityId))
        assertArrayEquals("el resolver devuelve los 32 bytes Ed25519", publicKey, resolved)
    }

    @Test
    fun `un contacto sin clave publica se salta y no registra nada - R2`() {
        val prefs = InMemorySharedPreferences()
        val directory = PeerSigningKeys(prefs)

        // Contacto auto-anadido sin clave (publicKey = ""): la derivacion no
        // produce identidad, asi que el alta no puede registrar nada.
        val identityId = ContactIdentity.identityIdOf("")

        assertNull("sin clave no hay IdentityId que registrar", identityId)
        assertTrue("km_peers queda vacio", prefs.getAll().isEmpty())
        assertTrue("nada que devolver por known()", directory.known().isEmpty())
    }

    @Test
    fun `una clave que no es Ed25519 tampoco registra basura - R2`() {
        val prefs = InMemorySharedPreferences()
        val directory = PeerSigningKeys(prefs)

        // Basura que no es Base64, y un id de 40 hex del espacio DHT legacy:
        // ninguno de los dos puede derivar un IdentityId de relevo valido.
        assertNull(ContactIdentity.identityIdOf("no-es-base64!!!"))
        assertNull(ContactIdentity.identityIdOf("0123456789abcdef0123456789abcdef01234567"))

        assertTrue("km_peers queda vacio: nada se registra con basura", prefs.getAll().isEmpty())
        assertTrue("known() no devuelve identidades inventadas", directory.known().isEmpty())
    }

    @Test
    fun `volver a registrar el mismo peer con la misma clave es idempotente - S7a`() {
        val prefs = InMemorySharedPreferences()
        val directory = PeerSigningKeys(prefs)
        val publicKey = ed25519.generateKeyPair().publicKey
        val base64 = Base64.getEncoder().encodeToString(publicKey)
        val identityId = ContactIdentity.identityIdOf(base64)!!

        directory.put(identityId, base64)
        directory.put(identityId, base64)

        assertEquals("una sola entrada por peer", 1, prefs.getAll().size)
        assertEquals("la clave guardada es la misma", base64, directory.signingKeyOf(identityId))
    }

    /**
     * `SharedPreferences` en memoria con la semantica exacta que usa
     * `PeerSigningKeys` (put/apply/get/contains/getAll). Sustituye al XML de
     * `km_peers.xml` en la JVM de los tests unitarios, donde los stubs de
     * Android lanzan "not mocked".
     */
    private class InMemorySharedPreferences : SharedPreferences {

        private val store = mutableMapOf<String, Any?>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): Map<String, *> = HashMap(store)

        override fun getString(key: String, defValue: String?): String? =
            store[key] as? String ?: defValue

        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            (store[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String, defValue: Int): Int = store[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = store[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = store[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            store[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = store.containsKey(key)

        override fun edit(): SharedPreferences.Editor = EditorImpl()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) {
            listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) {
            listeners.remove(listener)
        }

        private inner class EditorImpl : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?
            ): SharedPreferences.Editor {
                pending[key] = values
                return this
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                pending[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearFlag = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearFlag) store.clear()
                pending.forEach { (key, value) ->
                    if (value == null) store.remove(key) else store[key] = value
                }
            }
        }
    }
}