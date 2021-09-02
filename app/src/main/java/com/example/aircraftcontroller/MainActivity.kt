package com.example.aircraftcontroller

import android.annotation.SuppressLint
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.harrysoft.androidbluetoothserial.BluetoothManager
import android.widget.Toast

import com.harrysoft.androidbluetoothserial.BluetoothSerialDevice

import io.reactivex.android.schedulers.AndroidSchedulers

import io.reactivex.schedulers.Schedulers

import com.harrysoft.androidbluetoothserial.SimpleBluetoothDeviceInterface
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import io.github.controlwear.virtual.joystick.android.JoystickView
import java.lang.Exception
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin


class MainActivity : AppCompatActivity() {
    private var context: Context? = null
    private var bluetoothManager: BluetoothManager? = null
    private var deviceInterface: SimpleBluetoothDeviceInterface? = null
    private var throttle_yaw: JoystickView? = null
    private var roll_pitch: JoystickView? = null
    private val joystickData: JoystickData = JoystickData(
        0f,
        0f,
        0f,
        0f
    )

    private val TAG = "BluetoothMain"
    private var connect: Button? = null
    private var send: Button? = null
    private var dataReceived: TextView? = null

    private val device = "24:6F:28:25:04:22"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        context = this.applicationContext
        bluetoothManager = BluetoothManager.getInstance()

        // Setup our BluetoothManager
        if (bluetoothManager == null) {
            // Bluetooth unavailable on this device :( tell the user
            Toast.makeText(context, "Bluetooth not available.", Toast.LENGTH_LONG)
                .show() // Replace context with your context instance.
            finish()
        }

        dataReceived = findViewById(R.id.receivedData)

        connect = findViewById(R.id.connect)
        send = findViewById(R.id.send)

        connect?.setOnClickListener {
            connectDevice(device)
        }

        send?.setOnClickListener {
            deviceInterface!!.sendMessage("Hello world!")
        }

        throttle_yaw = findViewById(R.id.throttle_yaw)
        throttle_yaw?.setOnMoveListener { angle, strength ->

            joystickData.throttle = (sin(angle * (PI / 180f)) * strength).toFloat()
            joystickData.yaw = (cos(angle * (PI / 180f)) * strength).toFloat()
            Log.i(TAG, "ThrottleYaw: ${joystickData.throttle}, ${joystickData.yaw}")
            Log.i(TAG, "$angle,$strength")
            sendJoystickData()
        }

        roll_pitch = findViewById(R.id.roll_pitch)
        roll_pitch?.setOnMoveListener { angle, strength ->
            joystickData.pitch = (sin(angle * (PI / 180f)) * strength).toFloat()
            joystickData.roll = (cos(angle * (PI / 180f)) * strength).toFloat()
            Log.i(TAG, "RollPitch: ${joystickData.roll}, ${joystickData.pitch}")
            sendJoystickData()
        }

        connect!!.setBackgroundColor(Color.LTGRAY)
        send!!.setBackgroundColor(Color.LTGRAY)
    }

    private fun sendJoystickData() {
        val string: String =
            "${scaleAndOffset(joystickData.throttle, 200f, 1000f)}"
                .padStart(4, '0') +
                    "${scaleAndOffset(joystickData.yaw, 200f, 1000f)}"
                        .padStart(4, '0') +
                    "${scaleAndOffset(joystickData.roll, 200f, 1000f)}"
                        .padStart(4, '0') +
                    "${scaleAndOffset(joystickData.pitch, 200f, 1000f)}"
                        .padStart(4, '0') +
                    "?"
        try {
            deviceInterface?.sendMessage(string)
            Log.i(TAG, string)
        }
        catch (e: Exception){
            Log.i(TAG,"Error sending. Reset connection!")
            onError(Throwable())
        }
    }

    private fun scaleAndOffset(value: Float, range: Float, new_range: Float): Int {
        return constrain(
            (value * (new_range / range) + new_range / 2)* 1200f,
            0f,
            1000f).toInt()
    }

    fun constrain(value: Float, min:Float, max:Float): Float{
        if(value < min){
            return min
        }
        else if(value > max){
            return max
        }
        return value
    }
    @SuppressLint("CheckResult")
    private fun connectDevice(mac: String) {
        bluetoothManager!!.openSerialDevice(mac)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { connectedDevice: BluetoothSerialDevice -> onConnected(connectedDevice) },
                { error: Throwable -> onError(error) }
            )
    }

    private fun onConnected(connectedDevice: BluetoothSerialDevice) {
        // You are now connected to this device!
        // Here you may want to retain an instance to your device:
        deviceInterface = connectedDevice.toSimpleDeviceInterface()

        // Listen to bluetooth events
        deviceInterface!!.setListeners(
            { message: String -> onMessageReceived(message) },
            { message: String -> onMessageSent(message) },
            { error: Throwable -> onError(error) }
        )
        connect!!.setBackgroundColor(Color.GREEN)
        Toast.makeText(context, "Connected do $device", Toast.LENGTH_SHORT).show()
    }

    private fun onMessageSent(message: String) {}

    private fun onMessageReceived(message: String) {
        Log.i(TAG, "Received: $message")
        dataReceived?.text = message
    }

    private fun onError(error: Throwable) {
        // Handle the error
        error.message?.let { Log.e(TAG, it) }
        connect!!.setBackgroundColor(Color.RED)
        try {
            bluetoothManager?.close()
            bluetoothManager = BluetoothManager.getInstance()
        }
        catch (e: Exception){}
    }
}