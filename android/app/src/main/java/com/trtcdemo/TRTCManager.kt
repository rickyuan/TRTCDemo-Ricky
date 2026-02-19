package com.trtcdemo

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.tencent.rtmp.ui.TXCloudVideoView
import com.tencent.trtc.TRTCCloud
import com.tencent.trtc.TRTCCloudDef
import com.tencent.trtc.TRTCCloudListener

/**
 * Wraps TRTCCloud lifecycle: entering/leaving rooms, camera/screen-share toggling,
 * and resolution configuration.
 *
 * Usage:
 *   val mgr = TRTCManager(context)
 *   mgr.listener = myListener
 *   mgr.enterRoom(params, localView)
 *   mgr.startScreenCapture()   // optional — foreground service must be running first
 *   mgr.exitRoom()
 *   mgr.destroy()
 */
class TRTCManager(private val context: Context) {

    companion object {
        private const val TAG = "TRTCManager"

        /** Supported resolutions exposed to the UI (label → TRTCCloudDef constant). */
        val RESOLUTIONS: Map<String, Int> = linkedMapOf(
            "640×360"   to TRTCCloudDef.TRTC_VIDEO_RESOLUTION_640_360,
            "960×540"   to TRTCCloudDef.TRTC_VIDEO_RESOLUTION_960_540,
            "1280×720"  to TRTCCloudDef.TRTC_VIDEO_RESOLUTION_1280_720,
            "1920×1080" to TRTCCloudDef.TRTC_VIDEO_RESOLUTION_1920_1080,
        )
    }

    // ── TRTC SDK instance ──────────────────────────────────────────────────────
    private val trtcCloud: TRTCCloud = TRTCCloud.sharedInstance(context)

    // ── State ──────────────────────────────────────────────────────────────────
    var isInRoom = false
        private set
    var isCameraOn = false
        private set
    var isScreenSharing = false
        private set
    var isMicOn = true
        private set

    /** Callbacks delivered to the Activity / ViewModel. */
    var listener: Listener? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────────
    init {
        trtcCloud.setListener(InternalListener())
    }

    /**
     * Enter a TRTC room and immediately open the camera.
     *
     * @param sdkAppId       TRTC application ID
     * @param userId         Local user ID
     * @param userSig        Authentication signature
     * @param roomId         Room to join (numeric)
     * @param localView      TXCloudVideoView for local camera preview
     * @param resolutionKey  One of the keys from [RESOLUTIONS]
     */
    fun enterRoom(
        sdkAppId: Int,
        userId: String,
        userSig: String,
        roomId: Int,
        localView: TXCloudVideoView,
        resolutionKey: String = "960×540",
    ) {
        val params = TRTCCloudDef.TRTCParams().apply {
            this.sdkAppId = sdkAppId
            this.userId   = userId
            this.userSig  = userSig
            this.roomId   = roomId
            role          = TRTCCloudDef.TRTCRoleAnchor
        }
        trtcCloud.enterRoom(params, TRTCCloudDef.TRTC_APP_SCENE_VIDEOCALL)
        applyResolution(resolutionKey)
        // Camera will be started inside onEnterRoom callback to avoid race conditions
        pendingLocalView = localView
    }

    /** Leave the current room and clean up. */
    fun exitRoom() {
        if (isScreenSharing) stopScreenCapture()
        if (isCameraOn)      stopCamera()
        trtcCloud.exitRoom()
        isInRoom = false
    }

    /** Release all SDK resources — call this in Activity.onDestroy(). */
    fun destroy() {
        exitRoom()
        TRTCCloud.destroySharedInstance()
    }

    // ── Camera ─────────────────────────────────────────────────────────────────
    /**
     * Start the camera and show preview in [view].
     * @param frontCamera true = front camera, false = rear camera.
     */
    fun startCamera(view: TXCloudVideoView, frontCamera: Boolean = true) {
        trtcCloud.startLocalPreview(frontCamera, view)
        // FIT mode shows the full frame without cropping (avoids the "zoomed-in" look)
        val renderParams = TRTCCloudDef.TRTCRenderParams()
        renderParams.fillMode = TRTCCloudDef.TRTC_VIDEO_RENDER_MODE_FIT
        trtcCloud.setLocalRenderParams(renderParams)
        isCameraOn = true
        Log.d(TAG, "Camera started (front=$frontCamera)")
    }

    fun stopCamera() {
        trtcCloud.stopLocalPreview()
        isCameraOn = false
        Log.d(TAG, "Camera stopped")
    }

    /** Toggle between front / rear camera while the camera is on. */
    fun switchCamera() {
        trtcCloud.switchCamera()
    }

    // ── Screen Sharing ─────────────────────────────────────────────────────────
    /**
     * Start screen capture.
     * [ScreenShareService] (foreground service with mediaProjection type) must be
     * running before calling this. TRTC SDK 13.x handles MediaProjection internally.
     */
    fun startScreenCapture(resolutionKey: String = "1280×720") {
        val encParam = buildEncParam(resolutionKey).apply {
            // Use portrait mode so the phone's vertical screen is captured at full resolution
            videoResolutionMode = TRTCCloudDef.TRTC_VIDEO_RESOLUTION_MODE_PORTRAIT
            videoFps            = 10
            videoBitrate        = 1600
        }
        // enableForegroundService = false because we already run ScreenShareService ourselves.
        // Leaving it true (the default when null is passed) risks a conflict/failure on Android 14+.
        val shareParams = TRTCCloudDef.TRTCScreenShareParams().apply {
            enableForegroundService = false
        }
        trtcCloud.startScreenCapture(
            TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_SUB,
            encParam,
            shareParams,
        )
        isScreenSharing = true
        Log.d(TAG, "Screen capture started")
    }

    fun stopScreenCapture() {
        trtcCloud.stopScreenCapture()
        isScreenSharing = false
        Log.d(TAG, "Screen capture stopped")
    }

    // ── Audio ──────────────────────────────────────────────────────────────────
    fun setMicMuted(muted: Boolean) {
        trtcCloud.muteLocalAudio(muted)
        isMicOn = !muted
    }

    // ── Resolution ─────────────────────────────────────────────────────────────
    /**
     * Apply a named resolution to the camera (main) stream.
     * The resolution list is defined in [RESOLUTIONS].
     */
    fun applyResolution(key: String) {
        val encParam = buildEncParam(key)
        trtcCloud.setVideoEncoderParam(encParam)
        Log.d(TAG, "Resolution set to $key")
    }

    // ── Remote video ───────────────────────────────────────────────────────────
    /** Subscribe to a remote user's main (camera) or sub (screen) stream. */
    fun startRemoteView(userId: String, view: TXCloudVideoView, streamType: Int = TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_BIG) {
        trtcCloud.startRemoteView(userId, streamType, view)
        // FIT mode shows the full frame without cropping (avoids the "zoomed-in" look)
        val renderParams = TRTCCloudDef.TRTCRenderParams()
        renderParams.fillMode = TRTCCloudDef.TRTC_VIDEO_RENDER_MODE_FIT
        trtcCloud.setRemoteRenderParams(userId, streamType, renderParams)
    }

    fun stopRemoteView(userId: String) {
        trtcCloud.stopRemoteView(userId, TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_BIG)
        trtcCloud.stopRemoteView(userId, TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_SUB)
    }

    fun stopRemoteView(userId: String, streamType: Int) {
        trtcCloud.stopRemoteView(userId, streamType)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────
    private var pendingLocalView: TXCloudVideoView? = null

    private fun buildEncParam(key: String): TRTCCloudDef.TRTCVideoEncParam {
        val resolution = RESOLUTIONS[key] ?: TRTCCloudDef.TRTC_VIDEO_RESOLUTION_960_540
        return TRTCCloudDef.TRTCVideoEncParam().apply {
            videoResolution     = resolution
            videoResolutionMode = TRTCCloudDef.TRTC_VIDEO_RESOLUTION_MODE_PORTRAIT
            videoFps            = 15
            videoBitrate        = when (resolution) {
                TRTCCloudDef.TRTC_VIDEO_RESOLUTION_640_360   -> 500
                TRTCCloudDef.TRTC_VIDEO_RESOLUTION_960_540   -> 850
                TRTCCloudDef.TRTC_VIDEO_RESOLUTION_1280_720  -> 1200
                TRTCCloudDef.TRTC_VIDEO_RESOLUTION_1920_1080 -> 2000
                else -> 900
            }
        }
    }

    // ── Listener interface ─────────────────────────────────────────────────────
    interface Listener {
        fun onEnterRoom(result: Int)
        fun onExitRoom(reason: Int)
        fun onRemoteUserEnter(userId: String)
        fun onRemoteUserLeave(userId: String, reason: Int)
        fun onRemoteVideoAvailable(userId: String, streamType: Int, available: Boolean)
        fun onError(errCode: Int, errMsg: String)
        fun onScreenCaptureStarted() {}
        fun onScreenCaptureStopped(reason: Int) {}
    }

    // ── Internal TRTC callbacks ────────────────────────────────────────────────
    private inner class InternalListener : TRTCCloudListener() {

        override fun onEnterRoom(result: Long) {
            Log.d(TAG, "onEnterRoom: result=$result")
            if (result > 0) {
                isInRoom = true
                // Start camera once we're in the room
                pendingLocalView?.let { startCamera(it) }
                pendingLocalView = null
            }
            listener?.onEnterRoom(result.toInt())
        }

        override fun onExitRoom(reason: Int) {
            Log.d(TAG, "onExitRoom: reason=$reason")
            isInRoom = false
            listener?.onExitRoom(reason)
        }

        override fun onRemoteUserEnterRoom(userId: String) {
            Log.d(TAG, "onRemoteUserEnterRoom: $userId")
            listener?.onRemoteUserEnter(userId)
        }

        override fun onRemoteUserLeaveRoom(userId: String, reason: Int) {
            Log.d(TAG, "onRemoteUserLeaveRoom: $userId reason=$reason")
            listener?.onRemoteUserLeave(userId, reason)
        }

        override fun onUserVideoAvailable(userId: String, available: Boolean) {
            Log.d(TAG, "onUserVideoAvailable: $userId available=$available")
            listener?.onRemoteVideoAvailable(userId, TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_BIG, available)
        }

        override fun onUserSubStreamAvailable(userId: String, available: Boolean) {
            Log.d(TAG, "onUserSubStreamAvailable: $userId available=$available")
            listener?.onRemoteVideoAvailable(userId, TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_SUB, available)
        }

        override fun onError(errCode: Int, errMsg: String, extraInfo: Bundle?) {
            Log.e(TAG, "onError: $errCode $errMsg")
            listener?.onError(errCode, errMsg)
        }

        override fun onScreenCaptureStarted() {
            Log.d(TAG, "onScreenCaptureStarted")
            isScreenSharing = true
            listener?.onScreenCaptureStarted()
        }

        override fun onScreenCaptureStopped(reason: Int) {
            Log.d(TAG, "onScreenCaptureStopped: reason=$reason")
            isScreenSharing = false
            listener?.onScreenCaptureStopped(reason)
        }

    }
}
