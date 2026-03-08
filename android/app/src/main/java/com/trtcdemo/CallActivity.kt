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
import kotlinx.coroutines.launch

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
 *  - Real-time bilingual subtitles via AI transcription bot
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
    private var transcriptionTaskId: String? = null

    // Dual-ready gate: invite is sent only after BOTH TRTC has entered the room
    // AND the IM SDK has completed login. Without this gate, sendInvite() can be
    // called before IM login finishes, causing the C2C message to fail silently.
    private var trtcReady = false
    private var imReady   = false
    private var inviteSent = false

    private fun maybeSendInvite() {
        if (trtcReady && imReady && !inviteSent) {
            inviteSent = true
            toUserId?.let { signaling.sendInvite(roomId, it) }
        }
    }

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

    private val subtitleHideRunnable = Runnable {
        binding.subtitleOverlay.visibility = View.GONE
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

        binding.tvRoomId.text = getString(R.string.room_label, roomId)
        binding.tvUserId.text  = userId

        setupResolutionSpinner()
        setupButtons()
        initSignaling()
        initTRTC()
    }

    override fun onDestroy() {
        timerHandler.removeCallbacks(timerRunnable)
        timerHandler.removeCallbacks(subtitleHideRunnable)
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
            binding.btnToggleMic.text = getString(if (micMuted) R.string.btn_unmute else R.string.btn_mute)
            binding.btnToggleMic.alpha = if (micMuted) 0.5f else 1.0f
        }

        binding.btnToggleCam.setOnClickListener {
            if (trtcMgr.isCameraOn) {
                trtcMgr.stopCamera()
                binding.btnToggleCam.text = getString(R.string.btn_cam_on)
                binding.btnToggleCam.alpha = 0.5f
            } else {
                trtcMgr.startCamera(binding.viewLocalVideo, frontCamera)
                binding.btnToggleCam.text = getString(R.string.btn_cam_off)
                binding.btnToggleCam.alpha = 1.0f
            }
        }

        binding.btnSwitchCam.setOnClickListener {
            frontCamera = !frontCamera
            trtcMgr.switchCamera()
            binding.btnSwitchCam.text = getString(if (frontCamera) R.string.btn_switch_rear else R.string.btn_switch_front)
        }

        binding.btnScreenShare.setOnClickListener { toggleScreenShare() }
    }

    // ── TRTC ───────────────────────────────────────────────────────────────────
    private fun initTRTC() {
        trtcMgr = TRTCManager(this)
        trtcMgr.listener = object : TRTCManager.Listener {
            override fun onEnterRoom(result: Int) {
                if (result > 0) {
                    showToast(getString(R.string.toast_entered_room, roomId))
                    timerHandler.post(timerRunnable)
                    trtcReady = true
                    maybeSendInvite()
                    startTranscription()
                } else {
                    showError(getString(R.string.toast_failed_enter_room, result))
                }
            }

            override fun onExitRoom(reason: Int) {
                // handled in hangup()
            }

            override fun onRemoteUserEnter(uid: String) {
                // Ignore the AI transcription bot; track only the real call partner
                if (!uid.startsWith("trtc_bot_")) {
                    remoteUserId = uid
                    showToast(getString(R.string.toast_user_joined, uid))
                    binding.tvCallStatus.text = getString(R.string.status_in_call)
                }
            }

            override fun onRemoteUserLeave(uid: String, reason: Int) {
                // Only hang up when the actual call partner leaves, not the AI bot.
                // Previously any user leaving (including the transcription bot) would
                // trigger hangup(), ending the call prematurely.
                if (uid == remoteUserId) {
                    remoteUserId = null
                    showToast(getString(R.string.toast_user_left, uid))
                    hangup()
                }
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
                showToast(getString(R.string.toast_trtc_error, errCode, errMsg))
            }

            override fun onScreenCaptureStarted() {
                showToast(getString(R.string.toast_screen_share_started))
                binding.tvCallStatus.text = getString(R.string.status_screen_sharing)
            }

            override fun onScreenCaptureStopped(reason: Int) {
                showToast(getString(R.string.toast_screen_share_stopped, reason))
                binding.tvCallStatus.text = getString(
                    if (remoteUserId != null) R.string.status_in_call else R.string.status_connected
                )
                if (screenShare) {
                    screenShare = false
                    binding.btnScreenShare.text = getString(R.string.btn_share_screen)
                    binding.btnScreenShare.alpha = 1.0f
                    ScreenShareService.stop(this@CallActivity)
                    applyNormalLayout()
                }
            }

            override fun onTranscriptionMessage(userId: String, text: String, translations: Map<String, String>, isFinal: Boolean) {
                showSubtitle(text, translations, isFinal)
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
                binding.tvCallStatus.text = getString(R.string.status_waiting_answer)
                imReady = true
                maybeSendInvite()
            }

            override fun onDisconnected(reason: String) {
                showToast(getString(R.string.toast_sig_disconnected, reason))
            }

            override fun onAccepted(roomId: Int, fromUserId: String) {
                if (roomId != this@CallActivity.roomId) return // stale offline message
                showToast(getString(R.string.toast_accepted, fromUserId))
                binding.tvCallStatus.text = getString(R.string.status_in_call)
            }

            override fun onDeclined(roomId: Int, fromUserId: String) {
                if (roomId != this@CallActivity.roomId) return // stale offline message
                showToast(getString(R.string.toast_declined, fromUserId))
                hangup()
            }

            override fun onHangup(roomId: Int, fromUserId: String) {
                if (roomId != this@CallActivity.roomId) return // stale offline message
                showToast(getString(R.string.toast_hung_up, fromUserId))
                hangup()
            }

            override fun onError(message: String) {
                showToast(getString(R.string.toast_sig_error, message))
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
            binding.btnScreenShare.text = getString(R.string.btn_share_screen)
            binding.btnScreenShare.alpha = 1.0f
            applyNormalLayout()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                showToast(getString(R.string.toast_overlay_permission))
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"))
                )
                return
            }
            ScreenShareService.start(this)
            trtcMgr.startScreenCapture()
            screenShare = true
            binding.btnScreenShare.text = getString(R.string.btn_stop_share)
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

    // ── Transcription ──────────────────────────────────────────────────────────
    private fun startTranscription() {
        val botUserId = "trtc_bot_$roomId"
        lifecycleScope.launch {
            // Delegate to the backend server — no secrets needed on the client
            val taskId = ServerApi.startAITranscription(
                roomId    = roomId,
                botUserId = botUserId,
            )
            transcriptionTaskId = taskId
            if (taskId != null) {
                showToast("Transcription started (${taskId.takeLast(6)})")
            } else {
                showToast("Transcription start failed — check server logs")
            }
        }
    }

    private fun stopTranscription() {
        val taskId = transcriptionTaskId ?: return
        transcriptionTaskId = null
        lifecycleScope.launch {
            ServerApi.stopAITranscription(taskId)
        }
    }

    private fun showSubtitle(text: String, translations: Map<String, String>, isFinal: Boolean) {
        if (text.isEmpty()) return
        timerHandler.removeCallbacks(subtitleHideRunnable)

        binding.subtitleOverlay.visibility = View.VISIBLE
        // Interim results are dimmed; final results are fully opaque
        binding.subtitleOverlay.alpha = if (isFinal) 1.0f else 0.65f

        binding.tvSubtitleOriginal.text = text

        val en = translations["en"]
        if (en.isNullOrEmpty()) {
            binding.tvSubtitleTranslation.visibility = View.GONE
        } else {
            binding.tvSubtitleTranslation.text = en
            binding.tvSubtitleTranslation.visibility = View.VISIBLE
        }

        val id = translations["id"]
        if (id.isNullOrEmpty()) {
            binding.tvSubtitleTranslationId.visibility = View.GONE
        } else {
            binding.tvSubtitleTranslationId.text = id
            binding.tvSubtitleTranslationId.visibility = View.VISIBLE
        }

        if (isFinal) {
            // Auto-hide 5 seconds after the final result arrives
            timerHandler.postDelayed(subtitleHideRunnable, 5000)
        }
    }

    // ── Hangup ─────────────────────────────────────────────────────────────────
    private fun hangup() {
        timerHandler.removeCallbacks(timerRunnable)
        timerHandler.removeCallbacks(subtitleHideRunnable)
        stopTranscription()
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
