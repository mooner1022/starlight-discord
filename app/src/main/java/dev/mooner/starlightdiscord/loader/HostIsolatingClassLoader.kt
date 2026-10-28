package dev.mooner.starlightdiscord.loader

import java.net.URL
import java.util.Collections
import java.util.Enumeration

/**
 * Parent of this plugin's class loader, registered as `custom_classloader` in starlight.json.
 *
 * StarLight loads plugin classes parent-first, so a package the host app also ships always resolves to the
 * host's copy. The host bundles OkHttp 4 (through Coil) while JDA is built against OkHttp 5, and mixing the two
 * fails with NoSuchMethodError as soon as JDA sends a request. Hiding these packages from the host makes the
 * plugin's class loader define them from the plugin's own dex instead.
 *
 * StarLight loads this class from a separate class loader, so it must not reference other classes of the plugin.
 */
class HostIsolatingClassLoader(parent: ClassLoader) : ClassLoader(parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (ISOLATED_PACKAGES.any(name::startsWith))
            throw ClassNotFoundException(name)
        return super.loadClass(name, resolve)
    }

    override fun getResource(name: String): URL? =
        if (isIsolatedResource(name)) null else super.getResource(name)

    override fun getResources(name: String): Enumeration<URL> =
        if (isIsolatedResource(name)) Collections.emptyEnumeration() else super.getResources(name)

    private fun isIsolatedResource(name: String): Boolean {
        val path = name.removePrefix("/").replace('/', '.')
        return ISOLATED_PACKAGES.any(path::startsWith)
    }

    private companion object {
        val ISOLATED_PACKAGES = arrayOf("okhttp3.", "okio.")
    }
}
