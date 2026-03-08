package com.trtcdemo

/** Application-level configuration. */
object Config {
    /**
     * TRTC / Chat SDKAppId from the console.
     * This is a public identifier, safe to include in the client.
     */
    const val SDK_APP_ID: Int = 20026228

    /**
     * Backend server base URL.
     * All sensitive operations (UserSig generation, AI transcription API calls)
     * are proxied through this server — no secrets are stored in the client.
     *
     * For local development: use your machine's LAN IP so the Android device/emulator
     * can reach the server (e.g. "http://192.168.1.100:3000").
     * For production: replace with your deployed server URL.
     */
    const val SERVER_URL: String = "http://192.168.1.12:3000"  // LAN IP — accessible from real device

    /**
     * Default android user ID (can be changed in the UI).
     */
    const val DEFAULT_USER_ID: String = "android_user_001"
}
