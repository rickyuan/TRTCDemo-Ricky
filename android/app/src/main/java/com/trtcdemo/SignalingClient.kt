package com.trtcdemo

import android.content.Context
import android.util.Log
import com.tencent.imsdk.v2.V2TIMCallback
import com.tencent.imsdk.v2.V2TIMManager
import com.tencent.imsdk.v2.V2TIMMessage
import com.tencent.imsdk.v2.V2TIMSDKConfig
import com.tencent.imsdk.v2.V2TIMSDKListener
import com.tencent.imsdk.v2.V2TIMSimpleMsgListener
import com.tencent.imsdk.v2.V2TIMUserInfo
import com.tencent.imsdk.v2.V2TIMValueCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Signaling client backed by Tencent Cloud Chat (IM) SDK.
 *
 * Uses C2C custom messages as the signaling transport.
 * Each message's custom data is a JSON string with an "action" field:
 *   invite / accept / decline / hangup
 *
 * All [Listener] callbacks are delivered on the **main thread**.
 */
class SignalingClient(
    private val context: Context,
    private val sdkAppId: Int,
    private val userId: String,
    private val userSig: String,
    private val scope: CoroutineScope,
    val listener: Listener
) {
    companion object {
        private const val TAG = "SignalingClient"
        // Protocol fields
        private const val KEY_ACTION = "action"
        private const val KEY_ROOM_ID = "roomId"
        private const val KEY_SDK_APP_ID = "sdkAppId"
        // Actions
        private const val ACTION_ACCEPT = "accept"
        private const val ACTION_DECLINE = "decline"
        private const val ACTION_HANGUP = "hangup"
        private const val ACTION_INVITE = "invite"
    }

    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onAccepted(roomId: Int, fromUserId: String)
        fun onDeclined(roomId: Int, fromUserId: String)
        fun onHangup(roomId: Int, fromUserId: String)
        fun onError(message: String)
    }

    private val simpleMsgListener = object : V2TIMSimpleMsgListener() {
        override fun onRecvC2CCustomMessage(
            msgID: String?,
            sender: V2TIMUserInfo?,
            customData: ByteArray?,
        ) {
            if (customData == null || sender == null) return
            val text = String(customData, Charsets.UTF_8)
            Log.d(TAG, "← C2C custom from ${sender.userID}: $text")
            try {
                val json = JSONObject(text)
                val action = json.optString(KEY_ACTION, "")
                val roomId = json.optInt(KEY_ROOM_ID, 0)
                val from = sender.userID ?: ""

                scope.launch(Dispatchers.Main) {
                    when (action) {
                        ACTION_ACCEPT  -> listener.onAccepted(roomId, from)
                        ACTION_DECLINE -> listener.onDeclined(roomId, from)
                        ACTION_HANGUP  -> listener.onHangup(roomId, from)
                        else      -> Log.w(TAG, "Unknown action: $action")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse custom message: $text", e)
            }
        }
    }

    // ── Connect (init + login) ──────────────────────────────────────────────────
    fun connect() {
        val config = V2TIMSDKConfig()
        config.logLevel = V2TIMSDKConfig.V2TIM_LOG_INFO

        V2TIMManager.getInstance().addIMSDKListener(object : V2TIMSDKListener() {
            override fun onConnecting() {
                Log.d(TAG, "Chat SDK connecting...")
            }
            override fun onConnectSuccess() {
                Log.d(TAG, "Chat SDK connected")
            }
            override fun onConnectFailed(code: Int, error: String?) {
                Log.e(TAG, "Chat SDK connect failed: $code $error")
                scope.launch(Dispatchers.Main) {
                    listener.onError("Chat 连接失败: $code $error")
                }
            }
            override fun onKickedOffline() {
                scope.launch(Dispatchers.Main) {
                    listener.onDisconnected("被踢下线")
                }
            }
            override fun onUserSigExpired() {
                scope.launch(Dispatchers.Main) {
                    listener.onError("UserSig 已过期")
                }
            }
        })

        V2TIMManager.getInstance().initSDK(context, sdkAppId, config)
        V2TIMManager.getInstance().addSimpleMsgListener(simpleMsgListener)

        V2TIMManager.getInstance().login(userId, userSig, object : V2TIMCallback {
            override fun onSuccess() {
                Log.d(TAG, "Chat login success: $userId")
                scope.launch(Dispatchers.Main) {
                    listener.onConnected()
                }
            }

            override fun onError(code: Int, desc: String?) {
                Log.e(TAG, "Chat login failed: $code $desc")
                scope.launch(Dispatchers.Main) {
                    listener.onError("Chat 登录失败: $code $desc")
                }
            }
        })
    }

    fun disconnect() {
        V2TIMManager.getInstance().removeSimpleMsgListener(simpleMsgListener)
        V2TIMManager.getInstance().logout(null)
        V2TIMManager.getInstance().unInitSDK()
    }

    // ── Send helpers ────────────────────────────────────────────────────────────
    fun sendInvite(roomId: Int, toUserId: String) {
        val payload = JSONObject().apply {
            put(KEY_ACTION, ACTION_INVITE)
            put(KEY_ROOM_ID, roomId)
            put(KEY_SDK_APP_ID, sdkAppId)
        }
        sendCustomMessage(toUserId, payload)
        Log.d(TAG, "Invite sent: roomId=$roomId toUserId=$toUserId")
    }

    fun sendHangup(roomId: Int, toUserId: String) {
        val payload = JSONObject().apply {
            put(KEY_ACTION, ACTION_HANGUP)
            put(KEY_ROOM_ID, roomId)
        }
        sendCustomMessage(toUserId, payload)
    }

    private fun sendCustomMessage(toUserId: String, payload: JSONObject) {
        val data = payload.toString().toByteArray(Charsets.UTF_8)
        V2TIMManager.getInstance().sendC2CCustomMessage(
            data, toUserId,
            object : V2TIMValueCallback<V2TIMMessage> {
                override fun onSuccess(msg: V2TIMMessage?) {
                    Log.d(TAG, "→ sent to $toUserId: ${payload.optString(KEY_ACTION)}")
                }

                override fun onError(code: Int, desc: String?) {
                    Log.e(TAG, "Send failed to $toUserId: $code $desc")
                    scope.launch(Dispatchers.Main) {
                        listener.onError("发送失败: $code $desc")
                    }
                }
            }
        )
    }
}
