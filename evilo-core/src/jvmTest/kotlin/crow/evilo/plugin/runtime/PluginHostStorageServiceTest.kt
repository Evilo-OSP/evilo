package crow.evilo.plugin.runtime

import crow.evilo.plugin.api.HOST_STORAGE_CAPABILITY
import crow.evilo.plugin.api.HostDescriptor
import crow.evilo.storage.MemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PluginHostStorageServiceTest {
    private val host = HostDescriptor("1.0.0", 1, capabilities = setOf(HOST_STORAGE_CAPABILITY),
        storageQuotaBytes = 6, storageValueLimitBytes = 3, storageEntryLimit = 2)

    @Test
    fun pluginsCannotReadHostValuesOrAnotherPluginsNamespace() {
        val store = MemoryKeyValueStore()
        store.write("host.plugins", "setting", "host")
        val one = PluginHostStorageService("one", host, store)
        val two = PluginHostStorageService("two", host, store)
        one.writeValue("a", "12")
        assertNull(two.readValue("a"))
        assertNull(one.readValue("setting"))
        assertEquals("host", store.read("host.plugins", "setting"))
    }

    @Test
    fun rejectedQuotaChangesDoNotReplaceExistingValues() {
        val service = PluginHostStorageService("one", host, MemoryKeyValueStore())
        service.writeValue("a", "12")
        service.writeValue("b", "12")
        assertFailsWith<IllegalArgumentException> { service.writeValue("a", "123") }
        assertFailsWith<IllegalArgumentException> { service.writeValue("c", "1") }
        assertFailsWith<IllegalArgumentException> { service.writeValue("a", "1234") }
        assertEquals("12", service.readValue("a"))
        assertEquals("12", service.readValue("b"))
        assertNull(service.readValue("c"))
    }

    @Test
    fun controlCharactersAreRejectedInKeysAndPrefixes() {
        val service = PluginHostStorageService("one", host, MemoryKeyValueStore())
        assertFailsWith<IllegalArgumentException> { service.readValue("a\u007f") }
        assertFailsWith<IllegalArgumentException> { service.listKeys("a\u007f") }
    }
}
