package com.example.aircraftcontroller

data class JoystickData(
    val throttle_data: Float,
    val yaw_data: Float,
    val roll_data: Float,
    val pitch_data: Float) {
    var throttle: Float = throttle_data
    var yaw: Float = yaw_data
    var roll: Float = roll_data
    var pitch: Float = pitch_data
}