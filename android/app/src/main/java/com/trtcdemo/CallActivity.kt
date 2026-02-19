package com.trtcdemo

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.trtcdemo.databinding.ActivityCallBinding

/**
 * 1v1 Video Call screen (Android side).
 *
 * Features:
 *  - Camera preview (local) + remote video view
 *  - Toggle camera / microphone
 *  - Switch camera (front / rear)
 *  - Screen share (toggle replaces camera stream with sub-stream)
 *  - Resolution picker (applied to camera stream)
 *  - Hang up
 *  - Call timer
 *  - Sends invite signal via Chat SDK C2C custom message
 *
 * Navigation:
 *   MainActivity -> (provides extras) -> CallActivity
 */
class CallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_USER_ID    = "userId"
        const val EXTRA_TO_USER_ID = "toUserId"
        const val EXTRA_USER_SIG   = "userSig"
        const val EXTRA_SDK_APP_ID = "sdkAppId"
        const val EXTRA_ROOM_ID    = "roomId"

        // UI Texts
        private const val MIC_ON_TEXT = "静音"
        private const val MIC_OFF_TEXT = "开麦"
        private const val CAM_ON_TEXT = "关摄像头"
        private const val CAM_OFF_TEXT = "开摄像头"
        private const val CAM_SWITCH_FRONT_TEXT = "后置"
        private const val CAM_SWITCH_REAR_TEXT = "前置"
        private const val SCREEN_SHARE_ON_TEXT = "停止共享"
        private const val SCREEN_SHARE_OFF_TEXT = "共享屏幕"
    }

    private lateinit var binding: ActivityCallBinding
    private lateinit var trtcMgr: TRTCManager
    private lateinit var signaling: SignalingClient

    // Extras
    private lateinit var userId: String
    private lateinit var userSig: String
    private var sdkAppId: Int = 0
    private var roomId: Int   = 0
    private var toUserId: String? = null

    // State
    private var frontCamera    = true
    private var micMuted       = false
    private var screenShare    = false
    private var remoteUserId: String? = null
    private var callSeconds = 0
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            callSeconds++
            val m = "%02d".format(callSeconds / 60)
            val s = "%02d".format(callSeconds % 60)
            binding.tvTimer.text = "$m:$s"
            timerHandler.postDelayed(this, 1000)
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Read extras
        userId    = intent.getStringExtra(EXTRA_USER_ID)    ?: Config.DEFAULT_USER_ID
        userSig   = intent.getStringExtra(EXTRA_USER_SIG)   ?: ""
        sdkAppId  = intent.getIntExtra(EXTRA_SDK_APP_ID, Config.SDK_APP_ID)
        roomId    = intent.getIntExtra(EXTRA_ROOM_ID, 0)
        toUserId  = intent.getStringExtra(EXTRA_TO_USER_ID)

        binding.tvRoomId.text = "房间: $roomId"
        binding.tvUserId.text  = userId

        setupResolutionSpinner()
        setupButtons()
        initSignaling()
        initTRTC()
    }

    override fun onDestroy() {
        timerHandler.removeCallbacks(timerRunnable)
        trtcMgr.destroy()
        signaling.disconnect()
        super.onDestroy()
    }

    // ── Setup ──────────────────────────────────────────────────────────────────
    private fun setupResolutionSpinner() {
        val labels = TRTCManager.RESOLUTIONS.keys.toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerResolution.adapter = adapter
        // Default to 960x540
        binding.spinnerResolution.setSelection(labels.indexOf("960×540").coerceAtLeast(0))
        binding.spinnerResolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                trtcMgr.applyResolution(labels[pos])
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setupButtons() {
        binding.btnHangup.setOnClickListener { hangup() }

        binding.btnToggleMic.setOnClickListener {
            micMuted = !micMuted
            trtcMgr.setMicMuted(micMuted)
            binding.btnToggleMic.text = if (micMuted) MIC_OFF_TEXT else MIC_ON_TEXT
            binding.btnToggleMic.alpha = if (micMuted) 0.5f else 1.0f
        }

        binding.btnToggleCam.setOnClickListener {
            if (trtcMgr.isCameraOn) {
                trtcMgr.stopCamera()
                binding.btnToggleCam.text = CAM_OFF_TEXT
                binding.btnToggleCam.alpha = 0.5f
            } else {
                trtcMgr.startCamera(binding.viewLocalVideo, frontCamera)
                binding.btnToggleCam.text = CAM_ON_TEXT
                binding.btnToggleCam.alpha = 1.0f
            }
        }

        binding.btnSwitchCam.setOnClickListener {
            frontCamera = !frontCamera
            trtcMgr.switchCamera()
            binding.btnSwitchCam.text = if (frontCamera) CAM_SWITCH_FRONT_TEXT else CAM_SWITCH_REAR_TEXT
        }

        binding.btnScreenShare.setOnClickListener { toggleScreenShare() }
    }

    // ── TRTC ───────────────────────────────────────────────────────────────────
    private fun initTRTC() {
        trtcMgr = TRTCManager(this)
        trtcMgr.listener = object : TRTCManager.Listener {
            override fun onEnterRoom(result: Int) {
                if (result > 0) {
                    showToast("已进入房间 $roomId")
                    timerHandler.post(timerRunnable)
                    // Once we're in the room, invite the target user (if any)
                    toUserId?.let { signaling.sendInvite(roomId, it) }
                } else {
                    showError("进入房间失败，错误码: $result")
                }
            }

            override fun onExitRoom(reason: Int) {
                // handled in hangup()
            }

            override fun onRemoteUserEnter(uid: String) {
                remoteUserId = uid
                showToast("$uid 已加入")
                binding.tvCallStatus.text = "通话中"
            }

            override fun onRemoteUserLeave(uid: String, reason: Int) {
                remoteUserId = null
                showToast("$uid 已离开")
                hangup()
            }

            override fun onRemoteVideoAvailable(uid: String, streamType: Int, available: Boolean) {
                if (available) {
                    trtcMgr.startRemoteView(uid, binding.viewRemoteVideo, streamType)
                    binding.viewRemoteVideo.visibility = View.VISIBLE
                    binding.tvRemotePlaceholder.visibility = View.GONE
                } else {
                    trtcMgr.stopRemoteView(uid)
                    binding.viewRemoteVideo.visibility = View.GONE
                    binding.tvRemotePlaceholder.visibility = View.VISIBLE
                }
            }

            override fun onError(errCode: Int, errMsg: String) {
                showToast("TRTC 错误 $errCode: $errMsg")
            }

            override fun onScreenCaptureStarted() {
                showToast("屏幕共享已开始 — 切换到其他应用以共享内容")
                binding.tvCallStatus.text = "屏幕共享中"
            }

            override fun onScreenCaptureStopped(reason: Int) {
                showToast("屏幕共享已停止 (reason=$reason)")
                binding.tvCallStatus.text = if (remoteUserId != null) "通话中" else "已连接"
                if (screenShare) {
                    screenShare = false
                    binding.btnScreenShare.text = SCREEN_SHARE_OFF_TEXT
                    binding.btnScreenShare.alpha = 1.0f
                    ScreenShareService.stop(this@CallActivity)
                    applyNormalLayout()
                }
            }
        }

        trtcMgr.enterRoom(
            sdkAppId  = sdkAppId,
            userId    = userId,
            userSig   = userSig,
            roomId    = roomId,
            localView = binding.viewLocalVideo,
        )
    }

    // ── Signaling ──────────────────────────────────────────────────────────────
    private fun initSignaling() {
        val signalingListener = object : SignalingClient.Listener {
            override fun onConnected() {
                binding.tvCallStatus.text = "已连接，等待对方接听..."
            }

            override fun onDisconnected(reason: String) {
                showToast("信令断开: $reason")
            }

            override fun onAccepted(roomId: Int, fromUserId: String) {
                showToast("$fromUserId 已接听")
                binding.tvCallStatus.text = "通话中"
            }

            override fun onDeclined(roomId: Int, fromUserId: String) {
                showToast("$fromUserId 已拒绝")
                hangup()
            }

            override fun onHangup(roomId: Int, fromUserId: String) {
                showToast("$fromUserId 已挂断")
                hangup()
            }

            override fun onError(message: String) {
                showToast("信令错误: $message")
            }
        }

        signaling = SignalingClient(
            context   = applicationContext,
            sdkAppId  = sdkAppId,
            userId    = userId,
            userSig   = userSig,
            scope     = lifecycleScope,
            listener  = signalingListener
        )
        signaling.connect()
    }

    // ── Screen Share ───────────────────────────────────────────────────────────
    private fun toggleScreenShare() {
        if (screenShare) {
            trtcMgr.stopScreenCapture()
            ScreenShareService.stop(this)
            screenShare = false
            binding.btnScreenShare.text = SCREEN_SHARE_OFF_TEXT
            binding.btnScreenShare.alpha = 1.0f
            applyNormalLayout()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                showToast("请授予\"显示在其他应用上方\"权限以开启屏幕共享")
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"))
                )
                return
            }
            ScreenShareService.start(this)
            trtcMgr.startScreenCapture()
            screenShare = true
            binding.btnScreenShare.text = SCREEN_SHARE_ON_TEXT
            binding.btnScreenShare.alpha = 0.6f
            applyScreenShareLayout()
        }
    }

    private fun applyScreenShareLayout() {
        // No view changes — camera PiP and remote video stay as they are
    }

    private fun applyNormalLayout() {
        // No view changes needed
    }

    // ── Hangup ─────────────────────────────────────────────────────────────────
    private fun hangup() {
        timerHandler.removeCallbacks(timerRunnable)
        // Hangup can be initiated by either user. We need to signal the other party.
        // 'remoteUserId' is set when another user joins the room.
        // 'toUserId' is the initial user we invited.
        val target = remoteUserId ?: toUserId
        if (target != null) {
            signaling.sendHangup(roomId, target)
        }
        trtcMgr.exitRoom()
        finish()
    }

    // ── Utils ──────────────────────────────────────────────────────────────────
    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showError(msg: String) {
        android.util.Log.e("CallActivity", msg)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
