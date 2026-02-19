package com.trtcdemo

/** Application-level configuration. */
object Config {
    /**
     * TRTC / Chat SDKAppId from the console.
     */
    const val SDK_APP_ID: Int = 0 // TODO: Replace with your SDKAppID from https://console.trtc.io

    /**
     * SecretKey for local UserSig generation (demo/testing only).
     * In production, generate UserSig on your server.
     */
    const val SECRET_KEY: String = "" // TODO: Replace with your SecretKey

    /**
     * Default android user ID (can be changed in the UI).
     */
    const val DEFAULT_USER_ID: String = "android_user_001"
}
