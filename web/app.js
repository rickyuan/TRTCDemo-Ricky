/**
 * TRTC 1v1 Video Call — Web Client
 *
 * Flow:
 *  1. User logs in → locally generate UserSig → login to Tencent Chat SDK
 *  2. Chat SDK receives Android's C2C "invite" message → incoming call UI shown
 *  3. User clicks "Accept" → send "accept" C2C → enterRoom → startLocalVideo
 *  4. Remote (Android) video is rendered automatically via REMOTE_VIDEO_AVAILABLE
 *  5. Either side hangs up → send "hangup" C2C → exitRoom
 */

'use strict';

// ─── Config (edit before use) ─────────────────────────────────────────────────
const CONFIG = {
  SDK_APP_ID: 0,   // TODO: Replace with your SDKAppID from https://console.trtc.io
  SECRET_KEY: '',  // TODO: Replace with your SecretKey  (demo/test only — do not ship in production)
};
// ─────────────────────────────────────────────────────────────────────────────

// ─── State ───────────────────────────────────────────────────────────────────
const state = {
  userId: '',
  userSig: '',
  chat: null,
  trtc: null,
  pendingInvite: null,   // { roomId, fromUserId }
  inCall: false,
  micMuted: false,
  camOff: false,
  timerInterval: null,
  callSeconds: 0,
  screenShareActive: false,
  remoteUserId: null,
};

// ─── DOM ──────────────────────────────────────────────────────────────────────
const $ = (id) => document.getElementById(id);

const loginScreen       = $('login-screen');
const incomingScreen    = $('incoming-screen');
const callScreen        = $('call-screen');
const loginStatus       = $('login-status');
const callerNameEl      = $('caller-name');
const callStatusText    = $('call-status-text');
const callTimer         = $('call-timer');
const remotePlaceholder = $('remote-placeholder');

// ─── Helpers ──────────────────────────────────────────────────────────────────
function showScreen(name) {
  [loginScreen, incomingScreen, callScreen].forEach((s) => s.classList.add('hidden'));
  if (name === 'login')    loginScreen.classList.remove('hidden');
  if (name === 'incoming') incomingScreen.classList.remove('hidden');
  if (name === 'call')     callScreen.classList.remove('hidden');
}

function setLoginStatus(msg, type = '') {
  loginStatus.textContent = msg;
  loginStatus.className = `status-text ${type}`;
}

function setCallStatus(msg) {
  callStatusText.textContent = msg;
}

function startTimer() {
  state.callSeconds = 0;
  state.timerInterval = setInterval(() => {
    state.callSeconds++;
    const m = String(Math.floor(state.callSeconds / 60)).padStart(2, '0');
    const s = String(state.callSeconds % 60).padStart(2, '0');
    callTimer.textContent = `${m}:${s}`;
  }, 1000);
}

function stopTimer() {
  clearInterval(state.timerInterval);
  state.timerInterval = null;
  callTimer.textContent = '00:00';
}

// ─── UserSig (local generation — demo/test only) ──────────────────────────────
// Implements the same algorithm as tls-sig-api-v2:
//   HMAC-SHA256 sign → JSON pack → zlib deflate → base64url encode
async function genTestUserSig(userId, expire = 604800) {
  const { SDK_APP_ID: sdkAppId, SECRET_KEY: secretKey } = CONFIG;
  const currTime = Math.floor(Date.now() / 1000);

  const plaintext =
    `TLS.identifier:${userId}\nTLS.sdkappid:${sdkAppId}\nTLS.time:${currTime}\nTLS.expire:${expire}\n`;

  const enc = new TextEncoder();
  const cryptoKey = await crypto.subtle.importKey(
    'raw', enc.encode(secretKey), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
  );
  const sigBuf    = await crypto.subtle.sign('HMAC', cryptoKey, enc.encode(plaintext));
  const sigBase64 = btoa(String.fromCharCode(...new Uint8Array(sigBuf)));

  const jsonStr = JSON.stringify({
    'TLS.ver':        '2.0',
    'TLS.identifier': String(userId),
    'TLS.sdkappid':   Number(sdkAppId),
    'TLS.expire':     Number(expire),
    'TLS.time':       Number(currTime),
    'TLS.sig':        sigBase64,
  });

  const compressed = pako.deflate(jsonStr);
  return btoa(String.fromCharCode(...compressed))
    .replace(/\+/g, '-')
    .replace(/\//g, '_');
}

// ─── Chat SDK Signaling ───────────────────────────────────────────────────────
function handleChatMessages({ data: messages }) {
  for (const msg of messages) {
    if (msg.type !== TencentCloudChat.TYPES.MSG_CUSTOM) continue;
    if (msg.conversationType !== TencentCloudChat.TYPES.CONV_C2C) continue;

    let payload;
    try { payload = JSON.parse(msg.payload.data); } catch { continue; }
    console.log('[Chat] ←', payload);

    const { action, roomId } = payload;
    switch (action) {
      case 'invite':
        handleIncomingInvite({ roomId, fromUserId: msg.from });
        break;
      case 'hangup':
        if (state.inCall) endCall('对方已挂断');
        break;
    }
  }
}

async function connectSignaling(userId, userSig) {
  state.chat = TencentCloudChat.create({ SDKAppID: CONFIG.SDK_APP_ID });
  state.chat.on(TencentCloudChat.EVENT.MESSAGE_RECEIVED, handleChatMessages);
  state.chat.on(TencentCloudChat.EVENT.KICKED_OUT, () => {
    if (state.inCall) endCall('被踢下线');
    else setLoginStatus('已被踢下线，请重新登录', 'error');
  });
  await state.chat.login({ userID: userId, userSig });
}

function sigSend(toUserId, payload) {
  if (!state.chat) return;
  const msg = state.chat.createCustomMessage({
    to: toUserId,
    conversationType: TencentCloudChat.TYPES.CONV_C2C,
    payload: { data: JSON.stringify(payload), description: '', extension: '' },
  });
  state.chat.sendMessage(msg).catch((e) => console.error('[Chat] send error:', e));
}

async function disconnectSignaling() {
  if (!state.chat) return;
  state.chat.off(TencentCloudChat.EVENT.MESSAGE_RECEIVED, handleChatMessages);
  try { await state.chat.logout(); } catch (_) {}
  state.chat.destroy();
  state.chat = null;
}

// ─── Incoming call ────────────────────────────────────────────────────────────
function handleIncomingInvite({ roomId, fromUserId }) {
  if (state.inCall) {
    sigSend(fromUserId, { action: 'decline', roomId });
    return;
  }
  state.pendingInvite = { roomId, fromUserId };
  callerNameEl.textContent = fromUserId || 'Android 用户';
  showScreen('incoming');
}

// ─── TRTC ─────────────────────────────────────────────────────────────────────
async function joinRoom(roomId, userId, userSig, sdkAppId) {
  state.trtc = TRTC.create();

  // TRTC Web SDK v5: stream types are plain strings
  const ST_MAIN = 'main';
  const ST_SUB  = 'sub';

  state.trtc.on(TRTC.EVENT.REMOTE_VIDEO_AVAILABLE, async ({ userId: remoteId, streamType }) => {
    const isSub = streamType === ST_SUB;
    console.log(`VIDEO_AVAIL: ${remoteId} type=${streamType}`);
    setCallStatus(`收到视频流: type=${streamType}`);
    state.remoteUserId = remoteId;

    if (isSub) {
      // ── Screen share started ──────────────────────────────────────────────
      setCallStatus('对方屏幕共享中...');
      try { await state.trtc.stopRemoteVideo({ userId: remoteId, streamType: ST_MAIN }); } catch (_) {}
      remotePlaceholder.querySelector('span').textContent = '等待分享画面...';
      remotePlaceholder.style.display = 'flex';
      await state.trtc.startRemoteVideo({ userId: remoteId, streamType: ST_SUB, view: 'remote-video' })
        .then(() => { remotePlaceholder.style.display = 'none'; setCallStatus('屏幕共享显示中'); })
        .catch((e) => setCallStatus(`屏幕流错误: ${e.message}`));
      state.screenShareActive = true;
    } else {
      // ── Camera (MAIN) stream ──────────────────────────────────────────────
      if (!state.screenShareActive) {
        remotePlaceholder.style.display = 'none';
        await state.trtc.startRemoteVideo({ userId: remoteId, streamType: ST_MAIN, view: 'remote-video' })
          .then(() => setCallStatus('通话中'))
          .catch((e) => setCallStatus(`视频流错误: ${e.message}`));
      }
    }
  });

  state.trtc.on(TRTC.EVENT.REMOTE_VIDEO_UNAVAILABLE, async ({ userId: remoteId, streamType }) => {
    if (streamType === ST_SUB && state.screenShareActive) {
      // ── Screen share stopped — restore normal layout ──────────────────────
      state.screenShareActive = false;
      try { await state.trtc.stopRemoteVideo({ userId: remoteId, streamType: ST_SUB }); } catch (_) {}
      remotePlaceholder.querySelector('span').textContent = '等待对方视频...';
      remotePlaceholder.style.display = 'none';
      await state.trtc.startRemoteVideo({ userId: remoteId, streamType: ST_MAIN, view: 'remote-video' }).catch(console.error);
    } else if (streamType === ST_MAIN && !state.screenShareActive) {
      try { await state.trtc.stopRemoteVideo({ userId: remoteId, streamType }); } catch (_) {}
      remotePlaceholder.style.display = 'flex';
    }
  });

  state.trtc.on(TRTC.EVENT.REMOTE_USER_EXIT, () => {
    remotePlaceholder.style.display = 'flex';
    endCall('对方已退出');
  });

  state.trtc.on(TRTC.EVENT.ERROR, (error) => {
    console.error('[TRTC] Error:', error);
    setCallStatus(`错误: ${error.message}`);
  });

  await state.trtc.enterRoom({ sdkAppId: Number(sdkAppId), userId, userSig, roomId: Number(roomId) });
  await state.trtc.startLocalAudio();
  await state.trtc.startLocalVideo({ view: 'local-video' });
}

async function leaveRoom() {
  if (!state.trtc) return;
  try {
    await state.trtc.stopLocalVideo();
    await state.trtc.stopLocalAudio();
    await state.trtc.exitRoom();
    state.trtc.destroy();
  } catch (e) {
    console.warn('[TRTC] Cleanup error:', e);
  }
  state.trtc = null;
}

// ─── Call lifecycle ───────────────────────────────────────────────────────────
async function acceptCall() {
  const { roomId, fromUserId } = state.pendingInvite;
  showScreen('call');
  setCallStatus('正在连接...');

  try {
    sigSend(fromUserId, { action: 'accept', roomId });
    await joinRoom(roomId, state.userId, state.userSig, CONFIG.SDK_APP_ID);
    state.inCall = true;
    setCallStatus('通话中');
    startTimer();
  } catch (err) {
    console.error('[acceptCall]', err);
    setCallStatus(`连接失败: ${err.message}`);
    setTimeout(() => endCall('连接失败'), 2000);
  }
}

async function endCall(reason = '') {
  if (state.timerInterval) stopTimer();

  if (state.pendingInvite) {
    sigSend(state.pendingInvite.fromUserId, {
      action: 'hangup',
      roomId: state.pendingInvite.roomId,
    });
  }

  await leaveRoom();
  state.inCall          = false;
  state.pendingInvite   = null;
  state.micMuted        = false;
  state.camOff          = false;
  state.screenShareActive = false;
  state.remoteUserId    = null;

  remotePlaceholder.style.display = 'flex';
  showScreen('login');
  if (reason) setLoginStatus(reason);
}

// ─── Controls ─────────────────────────────────────────────────────────────────
$('btn-login').addEventListener('click', async () => {
  const userId = $('input-userid').value.trim();
  if (!userId) return setLoginStatus('请输入用户 ID', 'error');
  if (!CONFIG.SDK_APP_ID || !CONFIG.SECRET_KEY) {
    return setLoginStatus('请先在 app.js 中填写 SDK_APP_ID 和 SECRET_KEY', 'error');
  }

  $('btn-login').disabled = true;
  setLoginStatus('正在连接...');

  try {
    state.userId  = userId;
    state.userSig = await genTestUserSig(userId);
    await connectSignaling(userId, state.userSig);
    setLoginStatus(`已连接，等待来电 (${userId})`, 'success');
  } catch (err) {
    setLoginStatus(`连接失败: ${err.message}`, 'error');
    $('btn-login').disabled = false;
  }
});

$('btn-accept').addEventListener('click', () => acceptCall());

$('btn-decline').addEventListener('click', () => {
  if (state.pendingInvite) {
    sigSend(state.pendingInvite.fromUserId, {
      action: 'decline',
      roomId: state.pendingInvite.roomId,
    });
  }
  state.pendingInvite = null;
  showScreen('login');
  setLoginStatus('已拒绝来电');
});

$('btn-hangup').addEventListener('click', () => endCall());

$('btn-toggle-mic').addEventListener('click', async () => {
  if (!state.trtc) return;
  state.micMuted = !state.micMuted;
  await state.trtc.updateLocalAudio({ mute: state.micMuted });
  $('btn-toggle-mic').classList.toggle('muted', state.micMuted);
  $('btn-toggle-mic').querySelector('.label').textContent = state.micMuted ? '已静音' : '麦克风';
});

$('btn-toggle-cam').addEventListener('click', async () => {
  if (!state.trtc) return;
  state.camOff = !state.camOff;
  if (state.camOff) {
    await state.trtc.stopLocalVideo();
  } else {
    await state.trtc.startLocalVideo({ view: 'local-video' });
  }
  $('btn-toggle-cam').classList.toggle('muted', state.camOff);
  $('btn-toggle-cam').querySelector('.label').textContent = state.camOff ? '摄像头关' : '摄像头';
});
