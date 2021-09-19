package com.example.aircraftcontroller

import android.annotation.SuppressLint
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.harrysoft.androidbluetoothserial.BluetoothManager

import com.harrysoft.androidbluetoothserial.BluetoothSerialDevice

import io.reactivex.android.schedulers.AndroidSchedulers

import io.reactivex.schedulers.Schedulers

import com.harrysoft.androidbluetoothserial.SimpleBluetoothDeviceInterface
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.media.MediaPlayer
import android.provider.MediaStore
import android.util.Log
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
import android.graphics.drawable.Drawable
import android.view.*
import android.widget.*
import com.google.android.material.slider.Slider


data class FlightModes(var mode: Char){
    val STABILIZE = 'S'
    val ALTOHOLD = 'H'
    val LOITER = 'L'
    val ACRO = 'A'
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
    private val flightModes: FlightModes = FlightModes('S')

    private val TAG = "BluetoothMain"

    private var connect: Button? = null
    private var flightModeSelector: Button? = null
    private var controlModeSelector: Button? = null

    private var throttle: ProgressBar? = null
    private var yaw: ProgressBar? = null
    private var roll: ProgressBar? = null
    private var pitch: ProgressBar? = null
    private var gainSlider: SeekBar? = null

    private var gain: Float = 1f

    private var throttleDeadZone: Boolean = true
    private var yawDeadZone: Boolean = true
    private var rollDeadZone: Boolean = true
    private var pitchDeadZone: Boolean = true
    private var restingThrottle: Float = 0f
    private val dpad = Dpad()


    private var dataReceived: TextView? = null
    private var gainText: TextView? = null
    private var connected = false

    private var alarm: MediaPlayer? = null

    private var arming: Boolean = false
    private var disarming: Boolean = false
    private var armingCounter: Int = 0
    private val armingTime: Float = 2.5f
    private val controllerPeriod: Long = 20
    private var controlMode: Int = 0
    private val numControlModes = 3



    private var flightMode: Char = flightModes.STABILIZE

    private var usingJoystick = false

    private val device = "24:6F:28:25:04:22" //Esp32 MAC address

    override fun onDestroy(){
        bluetoothManager?.close()
        super.onDestroy()
    }

    private fun showPopupMenu(view: View) = PopupMenu(view.context, view).run {
        menuInflater.inflate(R.menu.flight_mode_menu, menu)
        setOnMenuItemClickListener { item ->
            flightModeSelector?.text = "Flight Mode:\n$item"
            when(item.itemId){
                R.id.stabilize ->
                    flightMode = flightModes.STABILIZE
                R.id.acro ->
                    flightMode = flightModes.ACRO
                R.id.loiter ->
                    flightMode = flightModes.LOITER
                R.id.althold ->
                    flightMode = flightModes.ALTOHOLD
            }
            true
        }
        show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        context = this.applicationContext
        bluetoothManager = BluetoothManager.getInstance()

        alarm = MediaPlayer.create(this, R.raw.alarm);

        if (bluetoothManager == null) {
            Toast.makeText(context, "Bluetooth not available.", Toast.LENGTH_LONG)
                .show()
            finish()
        }

        dataReceived = findViewById(R.id.receivedData)

        connect = findViewById(R.id.connect)
        controlModeSelector = findViewById(R.id.controlMode)

        gainText = findViewById(R.id.gainText)
        gainSlider = findViewById(R.id.gain)
        gainSlider?.min = 0
        gainSlider?.max = 1000
        gainSlider?.setProgress(500,false)
        gainSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, i: Int, b: Boolean) {
                gain = if(i > 500) {
                    1f + (i - 500f) / 1000f
                } else{
                    constrain(0.1f + i * 0.9f / 500f, 0.1f, 1f)
                }
                gainText?.text = "Gain: " + gain.toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })


        throttle = findViewById(R.id.throttle)
        yaw = findViewById(R.id.yaw)
        pitch = findViewById(R.id.pitch)
        roll = findViewById(R.id.roll)
        setProgressBarClickers()

        flightModeSelector = findViewById(R.id.flight_mode)
        flightModeSelector!!.text = "Flight mode:\nSTABILIZE"

        setupButtonClickers()

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
        flightModeSelector!!.setBackgroundColor(Color.LTGRAY)
        controlModeSelector!!.setBackgroundColor(Color.LTGRAY)

        if(getGameControllerIds().isNotEmpty()) {
            Log.i(TAG,"${getGameControllerIds()[0]}")
            throttle_yaw?.isEnabled = false
            roll_pitch?.isEnabled = false
            throttle_yaw?.setBackgroundColor(Color.BLACK)
            roll_pitch?.setBackgroundColor(Color.BLACK)
            usingJoystick = true
        }

        setupTimer()

        connectDevice(device)
    }

    private fun setupTimer() {
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

    private fun setupButtonClickers() {
        connect?.setOnClickListener {
            connectDevice(device)
        }
        flightModeSelector?.setOnClickListener{
            Log.i("CLICKED", "CLICK")
            showPopupMenu(view = findViewById(R.id.flight_mode))
        }
        controlModeSelector?.setOnClickListener {
            controlMode++
            if(controlMode == numControlModes){
                controlMode = 0
            }
            controlModeSelector?.text = "Control Mode: $controlMode"
        }
    }

    private fun setProgressBarClickers(){
        roll?.setOnClickListener {
            rollDeadZone = !rollDeadZone
            val progressDrawable: Drawable = roll!!.progressDrawable.mutate()
            if(!rollDeadZone) {
                progressDrawable.setColorFilter(Color.RED, PorterDuff.Mode.SRC_IN)
            }
            else{
                progressDrawable.setColorFilter(Color.GREEN, PorterDuff.Mode.SRC_IN)
            }
            roll?.progressDrawable = progressDrawable
        }
        throttle?.setOnClickListener {
            throttleDeadZone = !throttleDeadZone
            val progressDrawable: Drawable = throttle!!.progressDrawable.mutate()
            if(!throttleDeadZone) {
                progressDrawable.setColorFilter(Color.RED, PorterDuff.Mode.SRC_IN)
            }
            else{
                progressDrawable.setColorFilter(Color.GREEN, PorterDuff.Mode.SRC_IN)
            }
            throttle?.progressDrawable = progressDrawable
        }
        pitch?.setOnClickListener {
            pitchDeadZone = !pitchDeadZone
            val progressDrawable: Drawable = pitch!!.progressDrawable.mutate()
            if(!pitchDeadZone) {
                progressDrawable.setColorFilter(Color.RED, PorterDuff.Mode.SRC_IN)
            }
            else{
                progressDrawable.setColorFilter(Color.GREEN, PorterDuff.Mode.SRC_IN)
            }
            pitch?.progressDrawable = progressDrawable
        }
        yaw?.setOnClickListener {
            yawDeadZone = !yawDeadZone
            val progressDrawable: Drawable = yaw!!.progressDrawable.mutate()
            if(!yawDeadZone) {
                progressDrawable.setColorFilter(Color.RED, PorterDuff.Mode.SRC_IN)
            }
            else{
                progressDrawable.setColorFilter(Color.GREEN, PorterDuff.Mode.SRC_IN)
            }
            yaw?.progressDrawable = progressDrawable
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
            else -> {
                flightMode = flightModes.STABILIZE
                flightModeSelector!!.text = "Flight mode:\nSTABILIZE"
            }
        }
    }



    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Log.e(TAG, "EVNT")
        return if (event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) {
            usingJoystick = true
            if (event.repeatCount == 0) {
                Log.i(TAG, "Pressed $keyCode")
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
                    KeyEvent.KEYCODE_BUTTON_Y -> {
                        Toast.makeText(context, "Regaining control...", Toast.LENGTH_SHORT).show()
                        launch{
                            flightMode = flightModes.LOITER
                            delay(500)
                            flightMode = flightModes.STABILIZE
                            flightModeSelector?.text = "Flight Mode:\nStabilize"
                        }
                    }
                }
            }
            true
        }
        else {
            super.onKeyDown(keyCode, event)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            && event.action == MotionEvent.ACTION_MOVE) {
            processJoystickInput(event)
            usingJoystick = true

            if (Dpad.isDpadDevice(event)) {
                when (dpad.getDirectionPressed(event)) {
                    Dpad.DOWN -> {
                        Log.i(TAG, "DOWN")
                        restingThrottle -= 2f
                        restingThrottle = constrain(restingThrottle, -100f, 100f)
                    }
                    Dpad.UP -> {
                        Log.i(TAG, "UP")
                        restingThrottle += 2f
                        restingThrottle = constrain(restingThrottle, -100f, 100f)
                    }
                    Dpad.LEFT ->{
                        restingThrottle = 0f
                    }
                    Dpad.RIGHT ->{
                        restingThrottle = 0f
                    }
                }
            }
        }
        else {
            return super.onGenericMotionEvent(event)
        }
        return true
    }

    private fun processDeadZone(value: Float, deadZoneVal: Float): Float{
        var deadZone = deadZoneVal
        if(gain < 1){
            deadZone = constrain( deadZone * gain, 0.3f, deadZone)
        }
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

        // TODO verify input device
        val inputDevice = event.device

        val AXIS_LX = event.getAxisValue(MotionEvent.AXIS_X)
        val AXIS_LY = event.getAxisValue(MotionEvent.AXIS_Y)
        val AXIS_RX = event.getAxisValue(MotionEvent.AXIS_Z)
        val AXIS_RY = event.getAxisValue(MotionEvent.AXIS_RZ)
        val TRIG_L = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        val TRIG_R = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
        Log.d(TAG, "$AXIS_LX $AXIS_LY $AXIS_RX $TRIG_L $TRIG_R")

        var channel0 = 0f
        var channel1 = 0f
        var channel2 = 0f
        var channel3 = 0f
        var channel4 = 0f

        //Scaling all channels from -100 to 100
        channel0 = if(TRIG_R > TRIG_L) {
            ((0.5f + TRIG_R/2f) * 200 - 100) * gain
        } else{
            ((0.5f - TRIG_L/2f) * 200 - 100) * gain
        }
        channel1 = AXIS_RX * 100 * gain
        channel2 = -AXIS_RY * 100 * gain
        channel3 = AXIS_LX * 100 * gain
        channel4 = -AXIS_LY * 100 * gain

        when(controlMode){
            0 -> {
                assignChannels(
                    throttleCh = applyRestingThrottle(channel0),
                    yawCh = channel1,
                    rollCh = channel3,
                    pitchCh = channel4)
            }
            1 -> {
                assignChannels(
                    throttleCh = applyRestingThrottle(channel4),
                    yawCh = channel0,
                    rollCh = channel1,
                    pitchCh = channel2)
            }
            2 -> {
                assignChannels(
                    throttleCh = applyRestingThrottle(channel4),
                    yawCh = channel3,
                    rollCh = channel1,
                    pitchCh = channel2)
            }
        }

    }

    private fun applyRestingThrottle(channel: Float): Float {
        var scaledValue = channel / gain
        if(channel >0) {
            scaledValue = (restingThrottle + scaledValue * ((100 - restingThrottle) / 100f))
        }
        else{
            scaledValue = (restingThrottle + scaledValue * ((100 + restingThrottle) / 100f))
        }
        return scaledValue
    }

    private fun assignChannels(throttleCh: Float, yawCh: Float, rollCh: Float, pitchCh:Float){
        joystickData.throttle = throttleCh
        joystickData.yaw = if(yawDeadZone) processDeadZone(value = yawCh, deadZoneVal = 10f) else yawCh
        joystickData.roll = if(rollDeadZone) processDeadZone(value = rollCh, deadZoneVal = 10f) else rollCh
        joystickData.pitch = if(pitchDeadZone) processDeadZone(value = pitchCh, deadZoneVal = 10f) else pitchCh
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

        if(connected) {
            try {
                deviceInterface?.sendMessage(string)
                Log.d(TAG, string)
            } catch (e: Exception) {
                Log.i(TAG, "Error sending. Reset connection!")
                onError(Throwable())
                connected = false
            }
        }
    }

    private fun scaleAndOffset(value: Float, range: Float, new_range: Float): Int {
        val offset = new_range/2
        return constrain(
            (value * (new_range / range) + offset),
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
        connected = false
        Toast.makeText(context, "Connecting...", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(context, "Connected BT.", Toast.LENGTH_LONG).show()
        launch{
            delay(1000)
            connected = true
        }
    }

    private fun onMessageSent(message: String) {}

    private fun onMessageReceived(message: String) {
        Log.i(TAG, "Received: $message")
        if(message.last() == '?'){
            dataReceived?.text = dataReceived?.text.toString() + "\n" + message.substring(startIndex = 0, endIndex = message.length-2) + "s"
        }
        else{
            dataReceived?.text = message
        }
        val text = dataReceived?.text
        try {
            if(text!!.indexOf("radio") > -1){
                    if (alarm?.isPlaying == false) {
                        alarm?.start()
                    }
                Log.e(TAG, "No response from radio!")
            }
            else{

                val index = text.indexOf("Last")
                if (index > -1) {
                    val timeString = text.substring(
                        startIndex = index + 15,
                        endIndex = text.length - 1
                    )
                    Log.i(TAG, "Last response calcs $timeString")
                    val time = timeString.toFloat()
                    Log.e(TAG, "Response time $time")
                    if (time > 1.5) {
                        if(alarm?.isPlaying == false){
                            alarm?.start()
                        }
                    } else {
                        if(alarm?.isPlaying == true){
                            alarm?.pause()
                        }
                    }
                }
            }
        }
        catch (e:Exception){}
    }

    private fun onError(error: Throwable) {
        error.message?.let { Log.e(TAG, it) }
        connect!!.setBackgroundColor(Color.RED)
        dataReceived?.text = ""
        try {
            bluetoothManager?.close()
            bluetoothManager = BluetoothManager.getInstance()
            Toast.makeText(context, "BT down, trying to connected.", Toast.LENGTH_LONG).show()
            if(alarm?.isPlaying == false){
                alarm?.start()
            }
            connectDevice(device)
        }
        catch (e: Exception){}
    }

    class Dpad {

        private var directionPressed = -1 // initialized to -1
        private var up = false
        private var down = false
        private var left = false
        private var right = false

        fun getDirectionPressed(event: InputEvent): Int {
            // If the input event is a MotionEvent, check its hat axis values.
            (event as? MotionEvent)?.apply {

                // Use the hat axis value to find the D-pad direction
                val xAxis: Float = event.getAxisValue(MotionEvent.AXIS_HAT_X)
                val yAxis: Float = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

                if(yAxis == -1f){
                    if(!up){
                        up = true
                        down = false
                        return Dpad.UP
                    }
                }
                else if(yAxis == 1f){
                    if(!down){
                        down = true
                        up = false
                        return Dpad.DOWN
                    }
                }else{
                    up = false
                    down = false
                }

                if(xAxis == -1f){
                    if(!left){
                        left = true
                        right = false
                        return Dpad.LEFT
                    }
                }
                else if(xAxis == 1f){
                    if(!right){
                        right = true
                        left = false
                        return Dpad.RIGHT
                    }
                }else{
                    left = false
                    right = false
                }
                return -1
            }

            return directionPressed
        }

        companion object {
            internal const val UP = 0
            internal const val LEFT = 1
            internal const val RIGHT = 2
            internal const val DOWN = 3

            fun isDpadDevice(event: InputEvent): Boolean =
                // Check that input comes from a device with directional pads.
                event.source and InputDevice.SOURCE_DPAD != InputDevice.SOURCE_DPAD
        }
    }

}