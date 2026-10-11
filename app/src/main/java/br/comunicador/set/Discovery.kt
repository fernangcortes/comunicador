package br.comunicador.set

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

/** Anuncia a central na rede e encontra centrais anunciadas por outros celulares. */
class Discovery(context: Context) {
    data class Found(val name: String, val host: String, val port: Int)

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())

    // ---- anunciar (central) ----
    private var registration: NsdManager.RegistrationListener? = null

    fun announce(channelName: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = ("Comunicador_" + channelName.replace(' ', '_')).take(60)
            serviceType = Protocol.SERVICE_TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        registration = l
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) {}
    }

    fun stopAnnouncing() {
        registration?.let { try { nsd.unregisterService(it) } catch (_: Exception) {} }
        registration = null
    }

    // ---- procurar (participante) ----
    private var discovery: NsdManager.DiscoveryListener? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var onFound: ((Found) -> Unit)? = null
    private var onLost: ((String) -> Unit)? = null

    fun search(found: (Found) -> Unit, lost: (String) -> Unit) {
        stopSearching()
        onFound = found
        onLost = lost
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    pending.addLast(info)
                    resolveNext()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { onLost?.invoke(displayName(info.serviceName)) }
            }
        }
        discovery = l
        try { nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) {}
    }

    // O Android só resolve um serviço por vez, então é uma fila.
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val next = pending.removeFirstOrNull() ?: return
        resolving = true
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    main.post { resolving = false; resolveNext() }
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress
                    main.post {
                        resolving = false
                        if (host != null) {
                            onFound?.invoke(Found(displayName(info.serviceName), host, info.port))
                        }
                        resolveNext()
                    }
                }
            })
        } catch (_: Exception) {
            resolving = false
        }
    }

    private fun displayName(serviceName: String): String =
        serviceName.replace("\\032", " ").removePrefix("Comunicador_").replace('_', ' ').trim()

    fun stopSearching() {
        discovery?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        discovery = null
        pending.clear()
        resolving = false
    }
}
