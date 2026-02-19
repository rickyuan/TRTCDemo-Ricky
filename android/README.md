# TRTC Demo Application

This is a simple Android video calling application demonstrating the use of Tencent's Real-Time Communication (TRTC) SDK. The application connects to a Node.js signaling server to establish calls between the Android app and a web client.

**GitHub Repository**: [https://github.com/rickyuan/TRTCDemo-OK](https://github.com/rickyuan/TRTCDemo-OK)

## Features

*   One-to-one video calls
*   Signaling for call setup, teardown, and control

## How to Use

### Prerequisites

*   Android Studio
*   An Android device or emulator
*   Node.js and npm

### Setup

1.  **Signaling Server**: This Android application requires a corresponding signaling server to function. A sample Node.js server is provided in the `server` directory of the root project. 
    *   Navigate to the `server` directory in your terminal.
    *   Install dependencies: `npm install`
    *   Start the server: `npm start`

2.  **Android App**:
    *   Open the `android` directory in Android Studio.
    *   In `SignalingClient.kt`, update the `serverUrl` variable to point to the IP address of your running signaling server (e.g., `ws://192.168.1.100:8080`).
    *   Build and run the app on your Android device or emulator.

## Dependencies

*   Tencent LiteAVSDK_TRTC
*   OkHttp for WebSocket communication
*   Kotlin Coroutines
*   Gson for JSON serialization
*   Standard AndroidX libraries
