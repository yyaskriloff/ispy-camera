package com.ispy.camera.signaling

object SignalingMessages {
    const val TYPE_HELLO = "hello"
    const val TYPE_WELCOME = "welcome"
    const val TYPE_ERROR = "error"
    const val TYPE_OFFER = "offer"
    const val TYPE_ANSWER = "answer"
    const val TYPE_ICE = "ice"
    const val TYPE_BYE = "bye"

    const val ROLE_VIEWER = "viewer"
    const val ROLE_CAMERA = "camera"

    const val ERR_BAD_PIN = "bad_pin"
    const val ERR_BUSY = "busy"
    const val ERR_INVALID = "invalid"
}
