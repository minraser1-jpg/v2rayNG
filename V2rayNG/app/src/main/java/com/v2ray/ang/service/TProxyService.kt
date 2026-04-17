package com.v2ray.ang.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.pulsar.ProfileAutoSelector
import com.v2ray.ang.pulsar.NetworkType
import java.io.File

class TProxyService(
    private val context: Context,
    private val vpnInterface: ParcelFileDescriptor,
    private val isRunningProvider: () -> Boolean,
    private val restartCallback: () -> Unit
) : Tun2SocksControl {

    private val pulsarSelector = ProfileAutoSelector()

    companion object {
        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int)
        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStopService()
        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyGetStats(): LongArray?

        val GAMING_BYPASS_PACKAGES = listOf(
            "com.supercell.moco",
            "com.supercell.squad"
        )

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }

    override fun startTun2Socks() {
        val initialProfile = pulsarSelector.selectProfile("www.wildberries.ru", 0L, NetworkType.MOBILE)
        Log.i("PULSAR", "Pulsar Engine hooked. Initial profile: ${initialProfile.profileName}")

        val configContent = buildConfig()
        val configFile = File(context.filesDir, "hev-socks5-tunnel.yaml").apply {
            writeText(configContent)
        }

        try {
            TProxyStartService(configFile.absolutePath, vpnInterface.fd)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "HevSocks5Tunnel exception: ${e.message}")
        }
    }

    private fun buildConfig(): String {
        val socksPort = SettingsManager.getSocksPort()
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        return buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${SettingsManager.getVpnMtu()}")
            appendLine("  ipv4: ${vpnConfig.ipv4Client}")

            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6)) {
                appendLine("  ipv6: '${vpnConfig.ipv6Client}'")
            }

            appendLine("socks5:")
            appendLine("  port: ${socksPort}")
            appendLine("  address: ${AppConfig.LOOPBACK}")
            appendLine("  udp: 'udp'")
            
            val socks5User = MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS5_USERNAME) ?: "vpnuser"
            val socks5Pass = MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS5_PASSWORD) ?: "changeme"
            appendLine("  username: '${socks5User}'")
            appendLine("  password: '${socks5Pass}'")

            val timeoutSetting = MmkvManager.decodeSettingsString(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT) ?: AppConfig.HEVTUN_RW_TIMEOUT
            val parts = timeoutSetting.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val tcpTimeout = parts.getOrNull(0)?.toIntOrNull() ?: 300
            val udpTimeout = parts.getOrNull(1)?.toIntOrNull() ?: 60

            appendLine("misc:")
            appendLine("  tcp-read-write-timeout: ${tcpTimeout * 1000}")
            appendLine("  udp-read-write-timeout: ${udpTimeout * 1000}")
            appendLine("  log-level: ${MmkvManager.decodeSettingsString(AppConfig.PREF_HEV_TUNNEL_LOGLEVEL) ?: "warn"}")
        }
    }

    override fun stopTun2Socks() {
        try {
            Log.i("PULSAR", "Pulsar Engine stopped.")
            TProxyStopService()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to stop hev-socks5-tunnel", e)
        }
    }
}
