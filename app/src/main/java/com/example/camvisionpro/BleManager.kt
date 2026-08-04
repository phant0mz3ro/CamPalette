package com.example.camvisionpro

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.util.Log
import java.util.*
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat

class BleManager(
    private val context: Context,
    private val onModeCycle: (direction: Int) -> Unit,
    private val onIntensityDelta: (delta: Int) -> Unit,
    private val onShutter: () -> Unit,
    private val onSave: () -> Unit,
    private val onConnectionChanged: (connected: Boolean) -> Unit
) {
    private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val CHARACTERISTIC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")

    private var bluetoothGatt: BluetoothGatt? = null
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private val DISPLAY_CHARACTERISTIC_UUID = UUID.fromString("a1b2c3d4-1234-5678-9abc-def012345678")
    private var displayCharacteristic: BluetoothGattCharacteristic? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendDisplayUpdate(text: String) {
        val characteristic = displayCharacteristic ?: return
        val gatt = bluetoothGatt ?: return
        val data = text.toByteArray(Charsets.UTF_8)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }


    fun startScan() {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasPermission) {
            Log.e("BleManager", "Missing Bluetooth scan permission")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            Log.e("BleManager", "Bluetooth is turned off")
            return
        }


        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e("BleManager", "BluetoothLeScanner unavailable")
            return
        }

        Log.d("BleManager", "Scan started, looking for CamStick...")
        scanner.startScan(object : ScanCallback() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                Log.d("BleManager", "Found device: ${result.device.name ?: "unnamed"}")
                if (result.device.name == "CamStick") {
                    scanner.stopScan(this)
                    connect(result.device)
                }

                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return
                }
                if (result.device.name == "CamStick") {
                    scanner.stopScan(this)
                    connect(result.device)
                }
            }
        })
    }


    private fun connect(device: BluetoothDevice) {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasPermission) {
            Log.e("BleManager", "Missing Bluetooth connect permission")
            return
        }

        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d("BleManager", "Connected to ESP32")
                    gatt.requestMtu(512)
                    onConnectionChanged(true)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d("BleManager", "Disconnected from ESP32")
                    gatt.close()
                    onConnectionChanged(false)
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d("BleManager", "MTU changed to: $mtu")
            gatt.discoverServices()
        }



        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            displayCharacteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(DISPLAY_CHARACTERISTIC_UUID)
            val characteristic = gatt.getService(SERVICE_UUID)
                ?.getCharacteristic(CHARACTERISTIC_UUID) ?: return

            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            )
            descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val rawBytes = characteristic.value
            Log.d("BleManager", "Raw byte count: ${rawBytes?.size ?: 0}")
            val payload = String(rawBytes ?: ByteArray(0), Charsets.UTF_8)
            Log.d("BleManager", "Received: $payload")
            parseAndNotify(payload)
        }
    }

    private fun parseAndNotify(payload: String) {
        try {
            when {
                payload.startsWith("shutter") -> onShutter()
                payload.startsWith("save") -> onSave()
                payload.startsWith("mode_cycle") -> {
                    val direction = payload.split(":")[1].toInt()
                    onModeCycle(direction)
                }
                payload.startsWith("intensity_delta") -> {
                    val delta = payload.split(":")[1].toInt()
                    onIntensityDelta(delta)
                }
                else -> Log.e("BleManager", "Unknown payload: $payload")
            }
        } catch (e: Exception) {
            Log.e("BleManager", "Failed to parse: $payload", e)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}