package com.lastgimbus.the.lastbluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.*
import java.io.IOException
import java.util.*


/** TheLastBluetoothPlugin */
class TheLastBluetoothPlugin : FlutterPlugin, MethodCallHandler {
    companion object {
        const val TAG = "TheLastBluetoothPlugin"
        const val PLUGIN_NAMESPACE = "the_last_bluetooth"

        fun socketId(dev: BluetoothDevice, serviceUUID: UUID) = "$PLUGIN_NAMESPACE/rfcomm/${dev.address}/$serviceUUID"
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var context : Context

    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel
    private var eventSinkAdapterInfo: EventChannel.EventSink? = null
    private var eventSinkPairedDevices: EventChannel.EventSink? = null

    private var eventSinkRfcomm: EventChannel.EventSink? = null
    private val rfcommSocketMap = mutableMapOf<String, BluetoothSocket>()

    private var bluetoothAdapter: BluetoothAdapter? = null

    private val broadcastReceiverAdapterInfo = object : BroadcastReceiver() {
        val listenedBroadcasts = listOf(
            BluetoothAdapter.ACTION_STATE_CHANGED,
            BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED,
        )
        val intentFilter = IntentFilter().apply {
            listenedBroadcasts.forEach { addAction(it) }
        }

        override fun onReceive(context: Context, intent: Intent) {
            eventSinkAdapterInfo?.success(getAdapterInfo())
        }

    }

    private val broadcastReceiverPairedDevices = object : BroadcastReceiver() {
        @SuppressLint("InlinedApi")
        val listenedBroadcasts = listOf(
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_ACL_DISCONNECTED,
            BluetoothDevice.ACTION_BOND_STATE_CHANGED,
            BluetoothDevice.ACTION_NAME_CHANGED,
            // TODO: Check if this doens't *require* check (eg crashes otherwise) - else, just leave it in the list
            BluetoothDevice.ACTION_ALIAS_CHANGED,
            // TODO: Actually include those two in the data?
            // BluetoothDevice.ACTION_UUID,
            // BluetoothDevice.ACTION_CLASS_CHANGED,
            // TODO: Listen and react to this (close connections)
            // BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED,
        )
        val intentFilter = IntentFilter().apply { listenedBroadcasts.forEach { addAction(it) } }

        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                in listenedBroadcasts -> {
                    Log.v(TAG, "Device changed (${intent.action}): ${intent.extras?.itemsToString()}")
                    eventSinkPairedDevices?.success(getPairedDevices())  // Just send list of all devices (not only one changed)
                }

                else -> Log.wtf(TAG, "This receiver should not get this intent: $intent")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getAdapterInfo(): HashMap<String, Any?> = hashMapOf(
        "isAvailable" to (bluetoothAdapter != null),
        "isEnabled" to (bluetoothAdapter?.isEnabled ?: false),
        "name" to (bluetoothAdapter?.name),
        "address" to null,  // Android studio doesn't recommend me this, so will be disabled on android for now
    )


    @SuppressLint("MissingPermission")
    private fun getPairedDevices(): List<HashMap<String, Any?>> = bluetoothAdapter!!.bondedDevices.map {
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.alias else null
        hashMapOf("name" to it.name, "alias" to alias, "address" to it.address, "isConnected" to it.isConnected)
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectRfcomm(dev: BluetoothDevice, serviceUUID: UUID): String? {
        bluetoothAdapter =  BluetoothAdapter.getDefaultAdapter()
        Log.w(TAG, "BT Adaptor")
        val id = socketId(dev, serviceUUID)
        if (rfcommSocketMap.containsKey(id)) {
            Log.w(TAG, "Already connected to device")
            return id
        }
        val successId = withContext(Dispatchers.IO) {
            try {
                setProxy()
                val socket = dev.createRfcommSocketToServiceRecord(serviceUUID)
                if (!socket.isConnected) socket.connect()
                rfcommSocketMap[id] = socket // this will exec only if .connect() is success
                return@withContext id
            } catch (e: IOException) {
                Log.e(TAG, "Error while connecting to device", e)
                return@withContext null
            }
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                while (true) {
                    // read 1024 bytes of data:
                    val buffer = ByteArray(1024)
                    var read = -10
                    var closed = false
                    try {
                        if (rfcommSocketMap.containsKey(id)) {
                            read = rfcommSocketMap[id]!!.inputStream.read(buffer)
                        }
                        if (read < 0) closed = true
                    } catch (e: IOException) {
                        closed = true
                    }
                    withContext(Dispatchers.Main) {
                        if (closed) {
                            rfcommSocketMap.remove(id)
                            eventSinkRfcomm?.success(hashMapOf("socketId" to id, "closed" to true))
                        } else {
                            // send to eventsink on ui thread:
                            eventSinkRfcomm?.success(
                                hashMapOf(
                                    "socketId" to id, "data" to buffer.copyOfRange(0, read)
                                )
                            )
                        }
                    }
                    if (closed) break
                }
            }
        }
        return successId
    }

    private suspend fun closeRfcomm(id: String) {
        withContext(Dispatchers.IO) {
            if (!rfcommSocketMap.containsKey(id)) Log.w(TAG, "Socket $id already disconnected")
            val sock = rfcommSocketMap[id]
            closeProxy()
//            sock?.inputStream?.close()
//            sock?.outputStream?.close()
            sock?.close()
            Log.w(TAG, "Socket CLOSE!!")
            rfcommSocketMap.remove(id)
        }
    }

    @Suppress("unused")
    private enum class ProfileType(private val profile: Int) {

        HEADSET_CLIENT(BluetoothProfile.HEADSET),
        A2DP_SINK(BluetoothProfile.A2DP);

        companion object {

            fun valueOf(profileType: Int) = values().first { it.profile == profileType }

            fun profileTypeToString(profile: Int) = valueOf(profile).name
        }
    }
    protected var bluetoothHeadsetClient: BluetoothHeadset? = null
    protected var bluetoothA2dpSink: BluetoothA2dp? = null

    private fun setProxy() {
        bluetoothAdapter!!.getProfileProxy(context,serviceListener, BluetoothProfile.HEADSET)
        bluetoothAdapter!!.getProfileProxy(context,serviceListener, BluetoothProfile.A2DP)
    }

    private fun closeProxy() {
        bluetoothAdapter!!.closeProfileProxy(BluetoothProfile.HEADSET,bluetoothHeadsetClient)
        bluetoothAdapter!!.closeProfileProxy(BluetoothProfile.A2DP,bluetoothA2dpSink)

        Log.w(TAG, "CLOSE Proxy!!")

    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {

        override fun onServiceDisconnected(profile: Int) {
            Log.v(
                    TAG,
                    "serviceListener - onServiceDisconnected: ${ProfileType.profileTypeToString(profile)}"
            )
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadsetClient = null
            }
            if (profile == BluetoothProfile.A2DP) {
                bluetoothA2dpSink = null
            }
        }

        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            Log.v(
                    TAG,
                    "serviceListener - onServiceConnected: ${ProfileType.profileTypeToString(profile)}"
            )
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadsetClient = proxy as BluetoothHeadset



            }
            if (profile == BluetoothProfile.A2DP) {
                bluetoothA2dpSink = proxy as BluetoothA2dp
            }
        }
    }

    // ##### FlutterPlugin stuff #####
    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "$PLUGIN_NAMESPACE/methods")
        channel.setMethodCallHandler(this)

        EventChannel(flutterPluginBinding.binaryMessenger, "$PLUGIN_NAMESPACE/adapterInfo").apply {
            setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSinkAdapterInfo = events
                    eventSinkAdapterInfo!!.success(getAdapterInfo())
                    context.registerReceiver(broadcastReceiverAdapterInfo, broadcastReceiverAdapterInfo.intentFilter)
                }

                override fun onCancel(arguments: Any?) {
                    context.unregisterReceiver(broadcastReceiverAdapterInfo)
                    eventSinkAdapterInfo = null
                }
            })
        }
        EventChannel(flutterPluginBinding.binaryMessenger, "$PLUGIN_NAMESPACE/pairedDevices").apply {
            setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSinkPairedDevices = events
                    eventSinkPairedDevices!!.success(getPairedDevices())
                    context.registerReceiver(
                        broadcastReceiverPairedDevices, broadcastReceiverPairedDevices.intentFilter
                    )
                }

                override fun onCancel(arguments: Any?) {
                    context.unregisterReceiver(broadcastReceiverPairedDevices)
                    eventSinkPairedDevices = null
                }
            })
        }
        EventChannel(flutterPluginBinding.binaryMessenger, "$PLUGIN_NAMESPACE/rfcomm").apply {
            setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSinkRfcomm = events
                }

                override fun onCancel(arguments: Any?) {
                    eventSinkRfcomm = null
                }

            })
        }

        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        bluetoothAdapter = bm?.adapter
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }


    // ##### MethodCallHandler stuff #####
    @SuppressLint("MissingPermission")
    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        // TODO: More elegant logs
//        Log.d(TAG, "Method call: $call")
        if (bluetoothAdapter == null) {
            if (call.method == "isAvailable") result.success(false)
            else result.error("bluetooth_unavailable", "bluetooth is not available", null)
            return
        }
        when (call.method) {
            "isAvailable" -> {
                result.success(true)
            }
            "isEnabled" -> {
                result.success(bluetoothAdapter!!.isEnabled)
            }
            "getName" -> {
                result.success(bluetoothAdapter!!.name)
            }
            "getPairedDevices" -> {
                result.success(getPairedDevices())
            }
            "connectRfcomm" -> {
                val address = call.argument<String>("address")!!
                val uuid = UUID.fromString(call.argument<String>("uuid")!!)
                val dev = bluetoothAdapter!!.getRemoteDevice(address)
                scope.launch {
                    val id = connectRfcomm(dev, uuid)
                    withContext(Dispatchers.Main) {
                        if (id == null) result.error("connect_failed", "failed to connect to device", null)
                        else result.success(id)
                    }
                }
            }

            "closeRfcomm" -> {
                val id = call.argument<String>("socketId") ?: return
                scope.launch {
                    closeRfcomm(id)
                    withContext(Dispatchers.Main) { result.success(null) }
                }
            }

            "rfcommWrite" -> {
                val id = call.argument<String>("socketId")!!
                val sock = rfcommSocketMap[id]
                if (sock != null && sock.isConnected) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            try {
                                sock.outputStream.write(call.argument<ByteArray>("data")!!)
                                withContext(Dispatchers.Main) { result.success(true) }
                            } catch (e: IOException) {
                                withContext(Dispatchers.Main) {
                                    result.error(
                                        "write_failed", "failed to write to socket", e.toString()
                                    )
                                }
                            }
                        }
                    }
                } else {
                    result.error("socket_not_connected", "socket is not connected", null)
                }
            }

            else -> {
                result.notImplemented()
            }
        }

    }
}
