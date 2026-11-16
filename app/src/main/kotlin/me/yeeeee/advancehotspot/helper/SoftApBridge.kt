package me.yeeeee.advancehotspot.helper

import android.annotation.SuppressLint
import android.content.Context
import android.net.LinkAddress
import android.net.MacAddress
import android.net.wifi.WifiManager
import android.util.SparseIntArray
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * 透過反射操作 SoftAP 與 tethering 的隱藏 API。
 *
 * JSON 欄位名稱與 [me.yeeeee.advancehotspot.hotspot.HotspotConfig] 的序列化格式對應。
 */
// 整個 helper 的目的就是呼叫隱藏 API，且只針對單一 Android 版本
@SuppressLint("PrivateApi")
class SoftApBridge(context: Context) {
    private val wifiManager: WifiManager = (
        context.getSystemService(WifiManager::class.java)
            ?: throw IllegalStateException("WifiManager is unavailable")
        ).also { it.replaceContext(context) }
    private val tetheringManager: Any = (
        context.getSystemService("tethering")
            ?: throw IllegalStateException("TetheringManager is unavailable")
        ).also { it.replaceContext(context) }
    private val directExecutor = Executor { it.run() }

    fun status(): JSONObject {
        val snapshot = collectCallbackSnapshot()
        return JSONObject()
            .put("apState", wifiManager.call("getWifiApState"))
            .put("config", configToJson(currentConfig()))
            .put("capability", snapshot.capability?.let(::capabilityToJson) ?: JSONObject.NULL)
            .put("activeFrequencies", JSONArray(snapshot.frequencies))
            .put("clientCount", snapshot.clientMacs.size)
    }

    fun applyConfig(json: JSONObject): JSONObject {
        val builderClass = Class.forName("$CONFIG_CLASS\$Builder")
        val configClass = Class.forName(CONFIG_CLASS)
        // 以現有設定為基底，只覆寫本 app 管理的欄位，其餘（如 MAC 隨機化）維持不變
        val builder = builderClass.getConstructor(configClass).newInstance(currentConfig())

        val ssidBytes = json.getString("ssid").toByteArray(Charsets.UTF_8)
        val wifiSsid = Class.forName("android.net.wifi.WifiSsid")
            .getMethod("fromBytes", ByteArray::class.java).invoke(null, ssidBytes)
        builder.call("setWifiSsid", wifiSsid)

        val securityType = json.getInt("securityType")
        val passphrase = json.optString("passphrase").takeIf { securityType in PASSPHRASE_SECURITY_TYPES }
        builderClass.getMethod("setPassphrase", String::class.java, Int::class.javaPrimitiveType)
            .invoke(builder, passphrase, securityType)

        val band = json.getInt("band")
        val channel = json.optInt("channel", 0)
        if (channel == 0) {
            builder.call("setBand", band)
        } else {
            builder.call("setChannel", channel, band)
        }

        builder.call("setHiddenSsid", json.getBoolean("hidden"))
        builder.call("setMaxNumberOfClients", json.getInt("maxClients"))
        builder.call("setAutoShutdownEnabled", json.getBoolean("autoShutdown"))

        val accepted = wifiManager.call("setSoftApConfiguration", builder.call("build")) as Boolean
        if (!accepted) throw IllegalStateException("System rejected the SoftAP configuration")
        return JSONObject().put("config", configToJson(currentConfig()))
    }

    /** 列出目前連線的裝置與封鎖清單。 */
    fun clients(): JSONObject {
        val byMac = LinkedHashMap<String, JSONObject>()
        // 先放 Wi-Fi 層已連上的裝置，尚未取得 DHCP 租約的裝置也會出現在清單中
        for (mac in collectCallbackSnapshot().clientMacs) {
            byMac[mac] = JSONObject().put("mac", mac).put("ip", "").put("hostname", "")
        }
        for (client in collectTetheredClients()) {
            val mac = client?.call("getMacAddress").toString()
            val entry = byMac.getOrPut(mac) { JSONObject().put("mac", mac).put("ip", "").put("hostname", "") }
            for (addressInfo in client?.call("getAddresses") as List<*>) {
                val address = (addressInfo?.call("getAddress") as LinkAddress).address
                if (address is Inet4Address) entry.put("ip", address.hostAddress)
                (addressInfo.call("getHostname") as String?)?.let { entry.put("hostname", it) }
            }
        }
        return JSONObject()
            .put("clients", JSONArray(byMac.values))
            .put("blocked", JSONArray(blockedMacs(currentConfig())))
    }

    /** 以新的清單取代封鎖名單；熱點開啟中會立即踢除被封鎖的裝置，不需要重新啟動。 */
    fun setBlocked(json: JSONObject): JSONObject {
        val macs = json.getJSONArray("blocked")
        val blocked = List(macs.length()) { MacAddress.fromString(macs.getString(it)) }
        val configClass = Class.forName(CONFIG_CLASS)
        val builder = Class.forName("$CONFIG_CLASS\$Builder").getConstructor(configClass).newInstance(currentConfig())
        builder.call("setBlockedClientList", blocked)
        val accepted = wifiManager.call("setSoftApConfiguration", builder.call("build")) as Boolean
        if (!accepted) throw IllegalStateException("System rejected the blocked client list")
        return JSONObject().put("blocked", JSONArray(blockedMacs(currentConfig())))
    }

    fun start(): JSONObject {
        val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val latch = CountDownLatch(1)
        var errorCode: Int? = null
        val callback = proxyOf(callbackClass) { name, args ->
            when (name) {
                "onTetheringStarted" -> latch.countDown()
                "onTetheringFailed" -> {
                    errorCode = args[0] as Int
                    latch.countDown()
                }
            }
        }
        tetheringManager.javaClass
            .getMethod("startTethering", Int::class.javaPrimitiveType, Executor::class.java, callbackClass)
            .invoke(tetheringManager, TETHERING_WIFI, directExecutor, callback)

        if (!latch.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IllegalStateException("Timed out waiting for tethering to start")
        }
        errorCode?.let { throw IllegalStateException("Tethering failed with error code $it") }
        return JSONObject()
    }

    fun stop(): JSONObject {
        tetheringManager.javaClass
            .getMethod("stopTethering", Int::class.javaPrimitiveType)
            .invoke(tetheringManager, TETHERING_WIFI)
        return JSONObject()
    }

    private fun currentConfig(): Any = wifiManager.call("getSoftApConfiguration")
        ?: throw IllegalStateException("SoftAP configuration is unavailable")

    private fun configToJson(config: Any): JSONObject {
        val ssidBytes = config.call("getWifiSsid")?.call("getBytes") as ByteArray?
        val channels = config.call("getChannels") as SparseIntArray
        return JSONObject()
            .put("ssid", ssidBytes?.toString(Charsets.UTF_8) ?: "")
            .put("passphrase", config.call("getPassphrase") ?: "")
            .put("securityType", config.call("getSecurityType"))
            // 目前只管理單一 AP；橋接雙頻設定時取第一組
            .put("band", if (channels.size() > 0) channels.keyAt(0) else config.call("getBand"))
            .put("channel", if (channels.size() > 0) channels.valueAt(0) else 0)
            .put("bridged", channels.size() > 1)
            .put("hidden", config.call("isHiddenSsid"))
            .put("maxClients", config.call("getMaxNumberOfClients"))
            .put("autoShutdown", config.call("isAutoShutdownEnabled"))
    }

    private fun blockedMacs(config: Any): List<String> =
        (config.call("getBlockedClientList") as List<*>).map { it.toString() }

    /** 向 tethering 服務取得已連線裝置（含 DHCP 位址與主機名稱）；註冊後系統會立即回報一次。 */
    private fun collectTetheredClients(): Collection<*> {
        val callbackClass = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
        val latch = CountDownLatch(1)
        var clients: Collection<*> = emptyList<Any>()
        val callback = proxyOf(callbackClass) { name, args ->
            if (name == "onClientsChanged") {
                clients = args[0] as Collection<*>
                latch.countDown()
            }
        }
        tetheringManager.javaClass
            .getMethod("registerTetheringEventCallback", Executor::class.java, callbackClass)
            .invoke(tetheringManager, directExecutor, callback)
        try {
            latch.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            tetheringManager.javaClass.getMethod("unregisterTetheringEventCallback", callbackClass)
                .invoke(tetheringManager, callback)
        }
        return clients
    }

    private fun capabilityToJson(capability: Any): JSONObject {
        val channelsByBand = JSONObject()
        for (band in SINGLE_BANDS) {
            val channels = runCatching { capability.call("getSupportedChannelList", band) as IntArray }
                .getOrDefault(IntArray(0))
            channelsByBand.put(band.toString(), JSONArray(channels.toList()))
        }
        return JSONObject()
            .put("maxSupportedClients", capability.call("getMaxSupportedClients"))
            .put("channels", channelsByBand)
    }

    private class CallbackSnapshot(
        val capability: Any?,
        val frequencies: List<Int>,
        val clientMacs: List<String>,
    )

    /** 註冊 SoftAP callback 後系統會立即回報目前狀態，收齊後即取消註冊。 */
    private fun collectCallbackSnapshot(): CallbackSnapshot {
        val callbackClass = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
        val capabilityLatch = CountDownLatch(1)
        var capability: Any? = null
        var frequencies = emptyList<Int>()
        var clientMacs = emptyList<String>()
        val callback = proxyOf(callbackClass) { name, args ->
            when (name) {
                "onCapabilityChanged" -> {
                    capability = args[0]
                    capabilityLatch.countDown()
                }
                "onInfoChanged" -> (args[0] as? List<*>)?.let { infos ->
                    frequencies = infos.mapNotNull { it?.call("getFrequency") as Int? }
                }
                "onConnectedClientsChanged" -> (args.last() as? List<*>)?.let { clients ->
                    clientMacs = clients.mapNotNull { it?.call("getMacAddress")?.toString() }
                }
            }
        }
        wifiManager.javaClass.getMethod("registerSoftApCallback", Executor::class.java, callbackClass)
            .invoke(wifiManager, directExecutor, callback)
        try {
            capabilityLatch.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            wifiManager.javaClass.getMethod("unregisterSoftApCallback", callbackClass)
                .invoke(wifiManager, callback)
        }
        return CallbackSnapshot(capability, frequencies, clientMacs)
    }

    private companion object {
        const val CONFIG_CLASS = "android.net.wifi.SoftApConfiguration"
        const val TETHERING_WIFI = 0
        const val START_TIMEOUT_SECONDS = 15L
        const val CALLBACK_TIMEOUT_SECONDS = 3L

        /** WPA2-PSK、WPA3-SAE 過渡模式、WPA3-SAE。 */
        val PASSPHRASE_SECURITY_TYPES = setOf(1, 2, 3)

        /** 2.4 GHz、5 GHz、6 GHz 的頻段位元。 */
        val SINGLE_BANDS = listOf(1, 2, 4)
    }
}

/**
 * 把 manager 內部持有的 Context 換成指定的包裝 Context。
 *
 * 系統建立 manager 時用的是底層的 ContextImpl，包裝層覆寫的套件名稱不會生效，
 * 所以直接改寫欄位，讓之後的呼叫帶上正確的呼叫端套件。
 */
private fun Any.replaceContext(context: Context) {
    javaClass.getDeclaredField("mContext").apply { isAccessible = true }.set(this, context)
}

/** 以方法名稱與參數個數尋找並呼叫方法；有同名同參數個數的多載時不可使用。 */
private fun Any.call(name: String, vararg args: Any?): Any? {
    val method = javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
        ?: throw NoSuchMethodException("${javaClass.name}.$name/${args.size}")
    return method.invoke(this, *args)
}

/** 建立介面的動態代理；`Object` 的基本方法另外處理，避免回傳 null 造成拆箱失敗。 */
private fun proxyOf(type: Class<*>, handler: (name: String, args: Array<Any?>) -> Unit): Any =
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "${type.simpleName}Proxy"
            else -> {
                handler(method.name, args ?: emptyArray())
                null
            }
        }
    }
