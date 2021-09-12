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
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import io.github.controlwear.virtual.joystick.android.JoystickView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import java.lang.Exception
import kotlin.concurrent.fixedRateTimer
import kotlin.coroutines.CoroutineContext
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.sin

data class FlightModes(var mode: Char){
    val STABILIZE = 'S'
    val ALTOHOLD = 'H'
    val LOITER = 'L'
    val ACRO = 'A'
    val BRAKE = 'B'
}

class MainActivity : AppCompatActivity(), CoroutineScope {
    override val coroutineContext: CoroutineContext = newSingleThreadContext("name")
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
    private val flightModes: FlightModes = FlightModes('T')

    private val TAG = "BluetoothMain"
    private var connect: Button? = null
    private var arm: Button? = null
    private var disarm: Button? = null
    private var flightModeSelector: Button? = null
    private var throttle: ProgressBar? = null
    private var yaw: ProgressBar? = null
    private var roll: ProgressBar? = null
    private var pitch: ProgressBar? = null

    private var dataReceived: TextView? = null

    private var arming: Boolean = false
    private var disarming: Boolean = false
    private var armingCounter: Int = 0
    private val armingTime: Float = 2.5f
    private val controllerPeriod: Long = 20
    private var flightMode: Char = flightModes.STABILIZE

    private var usingJoystick = false

    private val device = "24:6F:28:25:04:22"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        context = this.applicationContext
        bluetoothManager = BluetoothManager.getInstance()

        if (bluetoothManager == null) {
            Toast.makeText(context, "Bluetooth not available.", Toast.LENGTH_LONG)
                .show()
            finish()
        }

        dataReceived = findViewById(R.id.receivedData)

        connect = findViewById(R.id.connect)
        arm = findViewById(R.id.arm)
        disarm = findViewById(R.id.disarm)

        throttle = findViewById(R.id.throttle)
        yaw = findViewById(R.id.yaw)
        pitch = findViewById(R.id.pitch)
        roll = findViewById(R.id.roll)

        flightModeSelector = findViewById(R.id.flight_mode)
        flightModeSelector!!.text = "Flight mode:\nSTABILIZE"
        connect?.setOnClickListener {
            connectDevice(device)
        }
        arm?.setOnClickListener {
            arming = true
        }
        disarm?.setOnClickListener {
            arming = true
            disarming = true
        }
        flightModeSelector?.setOnClickListener{
            changeFlightMode()
        }

        throttle_yaw = findViewById(R.id.throttle_yaw)
        throttle_yaw?.setOnMoveListener { angle, strength ->
            if(!arming) {
                joystickData.throttle = (sin(angle * (PI / 180f)) * strength).toFloat()
                joystickData.yaw = (cos(angle * (PI / 180f)) * strength).toFloat()
                Log.i(TAG, "ThrottleYaw: ${joystickData.throttle}, ${joystickData.yaw}")
            }
        }

        roll_pitch = findViewById(R.id.roll_pitch)
        roll_pitch?.setOnMoveListener { angle, strength ->
            if(!arming) {
                joystickData.pitch = (sin(angle * (PI / 180f)) * strength).toFloat()
                joystickData.roll = (cos(angle * (PI / 180f)) * strength).toFloat()
                Log.i(TAG, "RollPitch: ${joystickData.roll}, ${joystickData.pitch}")
            }
        }

        connect!!.setBackgroundColor(Color.LTGRAY)
        arm!!.setBackgroundColor(Color.LTGRAY)
        disarm!!.setBackgroundColor(Color.LTGRAY)
        flightModeSelector!!.setBackgroundColor(Color.LTGRAY)

        Log.i(TAG,"${getGameControllerIds().size}")
        if(getGameControllerIds().size > 0) {
            Log.i(TAG,"${getGameControllerIds()[0]}")
            throttle_yaw?.isEnabled = false
            roll_pitch?.isEnabled = false
            throttle_yaw?.setBackgroundColor(Color.BLACK)
            roll_pitch?.setBackgroundColor(Color.BLACK)
            usingJoystick = true
        }

        fixedRateTimer("ControlTimer", false, 0, controllerPeriod){
            if(arming){
                if(disarming){
                    joystickData.throttle = -100f
                    joystickData.yaw = -100f
                }
                else{
                    joystickData.throttle = -100f
                    joystickData.yaw = 100f
                }

                sendJoystickData()

                if(armingCounter < armingTime * (1000/controllerPeriod)){
                    armingCounter++
                }
                else{
                    armingCounter = 0
                    arming = false
                    disarming = false
                    joystickData.throttle = -100f
                    joystickData.yaw = 0f
                }
            }
            else{
                sendJoystickData()
            }
        }
    }

    private fun changeFlightMode(){
        when (flightMode) {
            flightModes.STABILIZE -> {
                flightMode = flightModes.ALTOHOLD
                flightModeSelector!!.text = "Flight mode:\nALTHOLD"
            }
            flightModes.ALTOHOLD -> {
                flightMode = flightModes.LOITER
                flightModeSelector!!.text = "Flight mode:\nLOITER"
            }
            flightModes.LOITER -> {
                flightMode = flightModes.ACRO
                flightModeSelector!!.text = "Flight mode:\nACRO"
            }
            flightModes.ACRO -> {
                flightMode = flightModes.BRAKE
                flightModeSelector!!.text = "Flight mode:\nBRAKE"
            }
            else -> {
                flightMode = flightModes.STABILIZE
                flightModeSelector!!.text = "Flight mode:\nSTABILIZE"
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) {
            if (event.repeatCount == 0) {
                Log.i(TAG, "$keyCode")
                when (keyCode) {
                    KeyEvent.KEYCODE_BUTTON_A -> {
                        changeFlightMode()
                    }
                    KeyEvent.KEYCODE_BUTTON_START -> {
                        arming = true
                    }
                    KeyEvent.KEYCODE_BUTTON_SELECT -> {
                        arming = true
                        disarming = true
                    }
                }
            }
            return true
        }
        else {
            return super.onKeyDown(keyCode, event)
        }


    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {

        return if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            && event.action == MotionEvent.ACTION_MOVE) {

            processJoystickInput(event)
            true
        } else {
            super.onGenericMotionEvent(event)
        }
    }

    private fun processDeadZone(value: Float, deadZone: Float): Float{
        var output = 0f
        if(value.absoluteValue > deadZone){
            output = if(value > 0){
                (value - deadZone) * 100 / (100 - deadZone)
            } else{
                (value + deadZone) * 100 / (100 - deadZone)
            }

        }
        return output
    }

    private fun processJoystickInput(event: MotionEvent) {

        val inputDevice = event.device
        val AXIS_LX = event.getAxisValue(MotionEvent.AXIS_X)
        val AXIS_LY = event.getAxisValue(MotionEvent.AXIS_Y)
        val AXIS_RX = event.getAxisValue(MotionEvent.AXIS_Z)
        val TRIG_L = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        val TRIG_R = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)

        if(TRIG_R > TRIG_L) {
            joystickData.throttle = (0.5f + TRIG_R/2f) * 200 - 100
        }
        else{
            joystickData.throttle = (0.5f - TRIG_L/2f) * 200 - 100
        }

        joystickData.yaw = processDeadZone(value = AXIS_RX * 100, deadZone = 10f)

        joystickData.roll = processDeadZone(value = AXIS_LX * 100, deadZone = 10f)
        joystickData.pitch = processDeadZone(value = -AXIS_LY * 100, deadZone = 10f)

    }




    private fun getGameControllerIds(): List<Int> {
        val gameControllerDeviceIds = mutableListOf<Int>()
        val deviceIds = InputDevice.getDeviceIds()
        deviceIds.forEach { deviceId ->
            InputDevice.getDevice(deviceId).apply {

                // Verify that the device has gamepad buttons, control sticks, or both.
                if (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
                    || sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) {
                    // This device is a game controller. Store its device ID.
                    gameControllerDeviceIds
                        .takeIf { !it.contains(deviceId) }
                        ?.add(deviceId)
                }
            }
        }
        return gameControllerDeviceIds
    }


    private fun sendJoystickData() {
        throttle?.progress = scaleAndOffset(joystickData.throttle, 200f, 1000f)/10
        yaw?.progress = scaleAndOffset(joystickData.yaw, 200f, 1000f)/10
        roll?.progress = scaleAndOffset(joystickData.roll, 200f, 1000f)/10
        pitch?.progress = scaleAndOffset(joystickData.pitch, 200f, 1000f)/10

        val string: String =
            "${scaleAndOffset(joystickData.throttle, 200f, 1000f)}"
                .padStart(4, '0') +
                    "${scaleAndOffset(joystickData.yaw, 200f, 1000f)}"
                        .padStart(4, '0') +
                    "${scaleAndOffset(joystickData.roll, 200f, 1000f)}"
                        .padStart(4, '0') +
                    "${1000-scaleAndOffset(joystickData.pitch, 200f, 1000f)}"
                        .padStart(4, '0') +
                    flightMode +
                    "?"
        try {
            deviceInterface?.sendMessage(string)
            Log.d(TAG, string)
        }
        catch (e: Exception){
            Log.i(TAG,"Error sending. Reset connection!")
            onError(Throwable())
        }
    }

    private fun scaleAndOffset(value: Float, range: Float, new_range: Float): Int {
        var gain = 1.0f
        if(!usingJoystick) {
            gain = 1.5f
        }
        val offset = new_range/2
        return constrain(
            (value * gain * (new_range / range) + offset),
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
        deviceInterface = connectedDevice.toSimpleDeviceInterface()

        deviceInterface!!.setListeners(
            { message: String -> onMessageReceived(message) },
            { message: String -> onMessageSent(message) },
            { error: Throwable -> onError(error) }
        )
        connect!!.setBackgroundColor(Color.GREEN)
    }

    private fun onMessageSent(message: String) {}

    private fun onMessageReceived(message: String) {
        Log.i(TAG, "Received: $message")
        dataReceived?.text = message
    }

    private fun onError(error: Throwable) {
        error.message?.let { Log.e(TAG, it) }
        connect!!.setBackgroundColor(Color.RED)
        dataReceived?.text = ""
        try {
            bluetoothManager?.close()
            bluetoothManager = BluetoothManager.getInstance()
        }
        catch (e: Exception){}
    }
}