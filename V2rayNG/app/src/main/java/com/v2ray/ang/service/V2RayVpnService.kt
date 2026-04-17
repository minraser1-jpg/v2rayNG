package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.util.Log
import androidx.annotation.RequiresApi
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.Utils
import java.lang.ref.SoftReference

@SuppressLint("VpnServicePolicy")
class V2RayVpnService : VpnService(), ServiceControl {
    private lateinit var mInterface: ParcelFileDescriptor
    private var isRunning = false
    private var tun2SocksService: Tun2SocksControl? = null

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(AppConfig.TAG, "StartCore-VPN: Service created")
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        V2RayServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        Log.w(AppConfig.TAG, "StartCore-VPN: Permission revoked")
        stopAllService()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")
        NotificationManager.cancelNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(AppConfig.TAG, "StartCore-VPN: Service command received")
        setupVpnService()
        startService()
        return START_STICKY
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        if (!::mInterface.isInitialized) {
            Log.e(AppConfig.TAG, "StartCore-VPN: Interface not initialized")
            return
        }
        if (!V2RayServiceManager.startCoreLoop(mInterface)) {
            Log.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
            stopAllService()
            return
        }
    }

    override fun stopService() {
        stopAllService(true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }

    private fun setupVpnService() {
        val prepare = prepare(this)
        if (prepare != null) {
            Log.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            stopSelf()
            return
        }

        if (configureVpnService() != true) {
            Log.e(AppConfig.TAG, "StartCore-VPN: Configuration failed")
            stopSelf()
            return
        }

        runTun2socks()
    }

    private fun configureVpnService(): Boolean {
        val builder = Builder()

        configureNetworkSettings(builder)
        configurePerAppProxy(builder)

        try {
            if (::mInterface.isInitialized) {
                mInterface.close()
            }
        } catch (e: Exception) {
            Log.w(AppConfig.TAG, "Failed to close old interface", e)
        }

        configurePlatformFeatures(builder)

        try {
            mInterface = builder.establish()!!
            isRunning = true
            return true
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to establish VPN interface", e)
            stopAllService()
        }
        return false
    }

    private fun configureNetworkSettings(builder: Builder) {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = SettingsManager.routingRulesetsBypassLan()

        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)

        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6) == true) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3)
                builder.addRoute("fc00::", 18)
            } else {
                builder.addRoute("::", 0)
            }
        }

        SettingsManager.getVpnDnsServers().forEach {
            if (Utils.isPureIpAddress(it)) {
                builder.addDnsServer(it)
            }
        }

        builder.setSession(V2RayServiceManager.getRunningServerName())
    }

    private fun configurePlatformFeatures(builder: Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "StartCore-VPN: Failed to request network", e)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
    }

    /**
     * Конфигуратор правил раздельного туннелирования.
     * Модифицирован для поддержки аппаратного байпаса гейминга Pulsar.
     */
    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID
        val isPerAppProxyEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == true
        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET) ?: mutableSetOf()
        val isBypassMode = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)

        // Если ручные настройки отключены или список пуст
        if (!isPerAppProxyEnabled || apps.isEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            
            // Жестко исключаем игры из VPN, чтобы они шли напрямую
            TProxyService.GAMING_BYPASS_PACKAGES.forEach { pkg ->
                try {
                    builder.addDisallowedApplication(pkg)
                    Log.i("PULSAR", "Hardware bypass applied for gaming: $pkg")
                } catch (e: PackageManager.NameNotFoundException) {
                    // Приложение не установлено на телефоне, пропускаем
                }
            }
            return
        }

        // Если включены ручные настройки, синхронизируем сам v2rayNG
        if (isBypassMode) apps.add(selfPackageName) else apps.remove(selfPackageName)

        // Инъекция пакетов Supercell в ручные списки
        TProxyService.GAMING_BYPASS_PACKAGES.forEach { pkg ->
            if (isBypassMode) {
                apps.add(pkg)    // В режиме Bypass добавляем игры в список исключений
            } else {
                apps.remove(pkg) // В режиме Proxy удаляем игры из списка разрешенных
            }
        }

        // Применяем финальный список, избегая конфликта Allowed/Disallowed
        apps.forEach {
            try {
                if (isBypassMode) {
                    builder.addDisallowedApplication(it)
                } else {
                    builder.addAllowedApplication(it)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(AppConfig.TAG, "StartCore-VPN: Failed to configure app", e)
            }
        }
    }

    private fun runTun2socks() {
        if (SettingsManager.isUsingHevTun()) {
            tun2SocksService = TProxyService(
                context = applicationContext,
                vpnInterface = mInterface,
                isRunningProvider = { isRunning },
                restartCallback = { runTun2socks() }
            )
        } else {
            tun2SocksService = null
        }

        tun2SocksService?.startTun2Socks()
    }

    private fun stopAllService(isForced: Boolean = true) {
        isRunning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.unregisterNetworkCallback(defaultNetworkCallback)
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "StartCore-VPN: Failed to unregister callback", e)
            }
        }

        tun2SocksService?.stopTun2Socks()
        tun2SocksService = null

        V2RayServiceManager.stopCoreLoop()

        if (isForced) {
            stopSelf()
            try {
                if (::mInterface.isInitialized) {
                    mInterface.close()
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface", e)
            }
        }
    }
}
