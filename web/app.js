/**
 * TRTC 1v1 Video Call — Web Client
 *
 * Signaling is done via Tencent Cloud Chat SDK (C2C custom messages).
 *
 * Flow:
 *  1. User logs in → Chat SDK login → waiting for incoming call
 *  2. Android sends "invite" custom message → incoming call UI shown
 *  3. User clicks "Accept" → enterRoom → startLocalVideo
 *  4. Remote (Android) video is rendered automatically via REMOTE_VIDEO_AVAILABLE
 *  5. Either side hangs up → exitRoom
 */

'use strict';

// ─── Config ──────────────────────────────────────────────────────────────────
// SDK_APP_ID is a public identifier, safe to include in the client.
const SDK_APP_ID = 20026228;

// Backend server URL — all sensitive operations are proxied through here.
// Change to your deployed server URL in production.
const SERVER_URL = 'http://192.168.1.12:3000';

// ─── i18n ─────────────────────────────────────────────────────────────────────
const STRINGS = {
  en: {
    appTitle:        'TRTC Video Call',
    appSubtitle:     'Enter your User ID to start',
    labelUserId:     'User ID',
    hintUserId:      'e.g. web_user_001',
    btnLogin:        'Login & Wait for Call',
    incomingTitle:   'Incoming Call',
    callType:        'Incoming video call',
    btnDecline:      'Decline',
    btnAccept:       'Accept',
    remotePlaceholder: 'Waiting for remote video...',
    statusConnecting:'Connecting...',
    btnMute:         'Mute',
    btnCamera:       'Camera',
    btnHangUp:       'Hang Up',
    langToggle:      '中文',
    // Dynamic
    statusLoggingIn: 'Connecting to Chat SDK...',
    statusLoggedIn:  (id) => `Logged in, waiting for call (${id})`,
    statusLoginFail: (e)  => `Login failed: ${e}`,
    statusConnFail:  (e)  => `Connection failed: ${e}`,
    statusInCall:    'In call',
    statusStream:    (t)  => `Stream received: type=${t}`,
    statusScreenShare: 'Remote screen sharing...',
    statusScreenShareActive: 'Screen share active',
    statusScreenShareErr: (e) => `Screen stream error: ${e}`,
    statusVideoErr:  (e)  => `Video stream error: ${e}`,
    statusError:     (e)  => `Error: ${e}`,
    waitingScreenShare: 'Waiting for screen share...',
    waitingRemote:   'Waiting for remote video...',
    callDeclined:    'Call declined',
    callEndedRemote: 'Call ended by remote',
    remoteLeft:      'Remote user left',
    connFailed:      'Connection failed',
    errEnterUserId:  'Please enter a User ID',
    defaultCaller:   'Android User',
  },
  zh: {
    appTitle:        'TRTC 视频通话',
    appSubtitle:     '输入用户 ID 开始',
    labelUserId:     '用户 ID',
    hintUserId:      '例如: web_user_001',
    btnLogin:        '登录并等待来电',
    incomingTitle:   '来电',
    callType:        '视频通话邀请',
    btnDecline:      '拒绝',
    btnAccept:       '接听',
    remotePlaceholder: '等待对方视频...',
    statusConnecting:'连接中...',
    btnMute:         '静音',
    btnCamera:       '摄像头',
    btnHangUp:       '挂断',
    langToggle:      'EN',
    // Dynamic
    statusLoggingIn: '正在连接 Chat SDK...',
    statusLoggedIn:  (id) => `已登录，等待来电 (${id})`,
    statusLoginFail: (e)  => `登录失败: ${e}`,
    statusConnFail:  (e)  => `连接失败: ${e}`,
    statusInCall:    '通话中',
    statusStream:    (t)  => `收到视频流: type=${t}`,
    statusScreenShare: '对方屏幕共享中...',
    statusScreenShareActive: '屏幕共享显示中',
    statusScreenShareErr: (e) => `屏幕流错误: ${e}`,
    statusVideoErr:  (e)  => `视频流错误: ${e}`,
    statusError:     (e)  => `错误: ${e}`,
    waitingScreenShare: '等待分享画面...',
    waitingRemote:   '等待对方视频...',
    callDeclined:    '已拒绝来电',
    callEndedRemote: '对方已挂断',
    remoteLeft:      '对方已退出',
    connFailed:      '连接失败',
    errEnterUserId:  '请输入用户 ID',
    defaultCaller:   'Android 用户',
  },
};

let lang = localStorage.getItem('trtc_lang') || 'en';

function t(key, ...args) {
  const val = STRINGS[lang][key];
  return typeof val === 'function' ? val(...args) : (val ?? key);
}

function applyLanguage() {
  localStorage.setItem('trtc_lang', lang);
  $('btn-lang').textContent = t('langToggle');

  // Login screen
  $('app-title').textContent    = t('appTitle');
  $('app-subtitle').textContent = t('appSubtitle');
  document.querySelector('label[for="input-userid"]').textContent = t('labelUserId');
  $('input-userid').placeholder = t('hintUserId');
  $('btn-login').textContent    = t('btnLogin');

  // Incoming screen — .call-type text & .action-label spans (not the buttons themselves)
  const callTypeEl = document.querySelector('#incoming-screen .call-type');
  if (callTypeEl) callTypeEl.textContent = t('callType');
  const declineLabel = $('btn-decline').parentElement.querySelector('.action-label');
  const acceptLabel  = $('btn-accept').parentElement.querySelector('.action-label');
  if (declineLabel) declineLabel.textContent = t('btnDecline');
  if (acceptLabel)  acceptLabel.textContent  = t('btnAccept');

  // Call screen — .ctrl-label is a sibling of the button, not a child
  remotePlaceholder.querySelector('span:last-child').textContent = t('remotePlaceholder');
  const micLabel    = $('btn-toggle-mic').parentElement.querySelector('.ctrl-label');
  const camLabel    = $('btn-toggle-cam').parentElement.querySelector('.ctrl-label');
  const hangupLabel = $('btn-hangup').parentElement.querySelector('.ctrl-label');
  if (micLabel)    micLabel.textContent    = t('btnMute');
  if (camLabel)    camLabel.textContent    = t('btnCamera');
  if (hangupLabel) hangupLabel.textContent = t('btnHangUp');
}

// ─── State ───────────────────────────────────────────────────────────────────
const state = {
  userId: '',
  chat: null,          // TencentCloudChat instance
  trtc: null,
  sdkAppId: SDK_APP_ID,
  pendingInvite: null, // { roomId, fromUserId }
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

const loginScreen    = $('login-screen');
const incomingScreen = $('incoming-screen');
const callScreen     = $('call-screen');
const loginStatus    = $('login-status');
const callerNameEl   = $('caller-name');
const callStatusText = $('call-status-text');
const callTimer      = $('call-timer');
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

// ─── Server API helpers ───────────────────────────────────────────────────────

/**
 * Fetch a UserSig for the given userId from the backend server.
 * The server holds the SECRET_KEY — the browser never sees it.
 */
async function genTestUserSig(userId) {
  const resp = await fetch(`${SERVER_URL}/api/usersig`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId }),
  });
  if (!resp.ok) throw new Error(`UserSig request failed: HTTP ${resp.status}`);
  const data = await resp.json();
  if (data.error) throw new Error(`UserSig error: ${data.error}`);
  return data.userSig;
}

// ─── AI Transcription ─────────────────────────────────────────────────────────

let transcriptionTaskId = null;

async function startTranscription(roomId, userId) {
  // Note: AI transcription is started by Android (the caller) via the backend server.
  // Web does NOT call this to avoid creating a duplicate task with the same bot userId.
  console.log('[Transcription] skipped on web side — Android caller handles this');
}

async function stopTranscription() {
  if (!transcriptionTaskId) return;
  const taskId = transcriptionTaskId;
  transcriptionTaskId = null;
  try {
    const resp = await fetch(`${SERVER_URL}/api/ai/stop`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ taskId }),
    });
    const data = await resp.json();
    console.log('[Transcription] stopped', data);
  } catch (e) {
    console.warn('[Transcription] stopTranscription failed:', e);
  }
}

// ─── Subtitle display ─────────────────────────────────────────────────────────

// Correlate transcription + all translation messages by roundId.
// NOTE: Per TRTC docs, transcription messages do NOT include a roundid field,
// while translation messages DO. To handle this mismatch, we keep lastTranscriptionText
// as a fallback so translation messages can still display with the current text.
// roundId → { text: string, translations: Map<lang, text>, finalCount: number }
const subtitleRounds = new Map();
let lastTranscriptionText = '';

let subtitleHideTimer = null;

// translations: Map<lang, text>  e.g. Map { "en" => "Hello", "id" => "Halo" }
function showSubtitle(originalText, translations, isFinal) {
  if (!originalText) return;
  const overlay = $('subtitle-overlay');
  const origEl  = $('subtitle-original');
  const transEn = $('subtitle-translation');

  // Line 1: original Chinese transcription
  origEl.textContent = originalText;

  // Line 2: English translation
  const en = translations.get('en') || '';
  if (en) {
    transEn.textContent = en;
    transEn.classList.remove('hidden');
  } else {
    transEn.classList.add('hidden');
  }

  overlay.classList.remove('hidden', 'interim');
  if (!isFinal) overlay.classList.add('interim');

  clearTimeout(subtitleHideTimer);
  if (isFinal) {
    subtitleHideTimer = setTimeout(() => overlay.classList.add('hidden'), 5000);
  }
}

// ─── Chat SDK Signaling ──────────────────────────────────────────────────────
async function initChatSDK(userId) {
  const chat = TencentCloudChat.create({ SDKAppID: SDK_APP_ID });
  chat.setLogLevel(1); // Release level

  // Listen for incoming custom messages BEFORE login
  chat.on(TencentCloudChat.EVENT.MESSAGE_RECEIVED, (event) => {
    const messages = event.data;
    for (const msg of messages) {
      if (msg.type !== TencentCloudChat.TYPES.MSG_CUSTOM) continue;
      const fromUserId = msg.from;
      try {
        const payload = JSON.parse(msg.payload.data);
        console.log('[Chat] ←', fromUserId, payload);
        handleSignalingMessage(fromUserId, payload);
      } catch (e) {
        console.warn('[Chat] Failed to parse custom message', e);
      }
    }
  });

  // Login
  const userSig = await genTestUserSig(userId);
  await chat.login({ userID: userId, userSig });

  state.chat = chat;
  return chat;
}

function handleSignalingMessage(fromUserId, payload) {
  const { action, roomId, sdkAppId } = payload;

  switch (action) {
    case 'invite':
      handleIncomingInvite({ roomId, fromUserId, sdkAppId: sdkAppId || SDK_APP_ID });
      break;

    case 'hangup':
      if (state.inCall) endCall(t('callEndedRemote'));
      break;

    case 'accept':
    case 'decline':
      // Android side is the caller, web is the callee — these shouldn't arrive here
      // but handle gracefully
      console.log(`[Chat] Received ${action} from ${fromUserId}`);
      break;

    default:
      console.warn('[Chat] Unknown action:', action);
  }
}

async function sigSend(toUserId, payload) {
  if (!state.chat) return;
  const msg = state.chat.createCustomMessage({
    to: toUserId,
    conversationType: TencentCloudChat.TYPES.CONV_C2C,
    payload: { data: JSON.stringify(payload) },
  });
  try {
    await state.chat.sendMessage(msg);
    console.log('[Chat] →', toUserId, payload);
  } catch (e) {
    console.error('[Chat] Send failed:', e);
  }
}

// ─── Incoming call ────────────────────────────────────────────────────────────
function handleIncomingInvite(msg) {
  if (state.inCall) {
    sigSend(msg.fromUserId, { action: 'decline', roomId: msg.roomId });
    return;
  }
  state.pendingInvite = { roomId: msg.roomId, fromUserId: msg.fromUserId };
  state.sdkAppId = msg.sdkAppId || SDK_APP_ID;
  callerNameEl.textContent = msg.fromUserId || t('defaultCaller');
  showScreen('incoming');
}

// ─── TRTC ─────────────────────────────────────────────────────────────────────
async function joinRoom(roomId, userId, userSig, sdkAppId) {
  state.trtc = TRTC.create();

  const ST_MAIN = 'main';
  const ST_SUB  = 'sub';

  state.trtc.on(TRTC.EVENT.REMOTE_VIDEO_AVAILABLE, async ({ userId: remoteId, streamType }) => {
    const isSub = streamType === ST_SUB;
    console.log(`VIDEO_AVAIL: ${remoteId} type=${streamType} isSub=${isSub}`);
    setCallStatus(t('statusStream', streamType));
    state.remoteUserId = remoteId;

    if (isSub) {
      setCallStatus(t('statusScreenShare'));
      try { await state.trtc.stopRemoteVideo({ userId: remoteId, streamType: ST_MAIN }); } catch (_) {}
      remotePlaceholder.querySelector('span:last-child').textContent = t('waitingScreenShare');
      remotePlaceholder.style.display = 'flex';
      await state.trtc.startRemoteVideo({ userId: remoteId, streamType: ST_SUB, view: 'remote-video' })
        .then(() => {
          remotePlaceholder.style.display = 'none';
          setCallStatus(t('statusScreenShareActive'));
          console.log('SUB render OK');
        })
        .catch((e) => { console.log(`SUB render ERR: ${e.message}`); setCallStatus(t('statusScreenShareErr', e.message)); });
      state.screenShareActive = true;
    } else {
      if (!state.screenShareActive) {
        remotePlaceholder.style.display = 'none';
        await state.trtc.startRemoteVideo({ userId: remoteId, streamType: ST_MAIN, view: 'remote-video' })
          .then(() => setCallStatus(t('statusInCall')))
          .catch((e) => { console.log(`MAIN render ERR: ${e.message}`); setCallStatus(t('statusVideoErr', e.message)); });
      }
    }
  });

  state.trtc.on(TRTC.EVENT.REMOTE_VIDEO_UNAVAILABLE, async ({ userId: remoteId, streamType }) => {
    console.log(`VIDEO_UNAVAIL: ${remoteId} type=${streamType}`);

    if (streamType === ST_SUB && state.screenShareActive) {
      state.screenShareActive = false;
      try { await state.trtc.stopRemoteVideo({ userId: remoteId, streamType: ST_SUB }); } catch (_) {}
      remotePlaceholder.querySelector('span:last-child').textContent = t('waitingRemote');
      remotePlaceholder.style.display = 'none';
      await state.trtc.startRemoteVideo({ userId: remoteId, streamType: ST_MAIN, view: 'remote-video' }).catch(console.error);
    } else if (streamType === ST_MAIN && !state.screenShareActive) {
      try { await state.trtc.stopRemoteVideo({ userId: remoteId, streamType }); } catch (_) {}
      remotePlaceholder.style.display = 'flex';
    }
  });

  state.trtc.on(TRTC.EVENT.REMOTE_USER_EXIT, ({ userId: remoteId }) => {
    console.log(`USER_EXIT: ${remoteId}`);
    remotePlaceholder.style.display = 'flex';
    endCall(t('remoteLeft'));
  });

  state.trtc.on(TRTC.EVENT.ERROR, (error) => {
    console.error('[TRTC] Error:', error);
    setCallStatus(t('statusError', error.message));
  });

  // Receive AI transcription subtitles (type = 10000).
  // Transcription and translation arrive as SEPARATE messages:
  //   transcription msg: { payload: { text, end } }  — NO roundid
  //   translation  msg:  { payload: { translation_text, translation_language, roundid, end } }
  // NOTE: cmdId is NOT filtered — docs don't guarantee translations use cmdId=1.
const TRANSLATION_TARGET_COUNT = 2; // en + zh

  function handleCmdData(rawData) {
    try {
      const msg = JSON.parse(new TextDecoder().decode(rawData));
      // 10000 = ASR transcription, 10001 = translation
      if (msg.type !== 10000 && msg.type !== 10001) return;
      const pl = msg.payload;
      if (!pl) return;
      const roundId = pl.roundid ?? '';
      const isFinal = !!pl.end;

      // Log every type=10000 message for diagnostics
      console.log('[TRTC sub] keys=%s roundId=%s isFinal=%s',
        Object.keys(pl).join(','), roundId, isFinal);

      if (!subtitleRounds.has(roundId)) {
        subtitleRounds.set(roundId, { text: '', translations: new Map(), finalCount: 0 });
      }
      const round = subtitleRounds.get(roundId);

      if ('translation_text' in pl) {
        const lang = pl.translation_language ?? '';
        if (lang) round.translations.set(lang, pl.translation_text ?? '');
        if (isFinal) {
          round.finalCount++;
          if (round.finalCount >= TRANSLATION_TARGET_COUNT) subtitleRounds.delete(roundId);
        }
        // Transcription messages have no roundid, so round.text may be empty here.
        // Fall back to the most recently received transcription text.
        const textToShow = round.text || lastTranscriptionText;
        if (textToShow) {
          console.log('[Subtitle/trans] lang=%s isFinal=%s trans=%s', lang, isFinal, pl.translation_text);
          showSubtitle(textToShow, round.translations, isFinal);
        }
      } else if ('text' in pl) {
        round.text = pl.text ?? '';
        if (round.text) lastTranscriptionText = round.text;
        console.log('[Subtitle/orig] isFinal=%s text=%s', isFinal, round.text);
        if (round.text) showSubtitle(round.text, round.translations, isFinal);
      }
    } catch (e) {
      console.error('[TRTC] subtitle parse error:', e);
    }
  }

  // Listen to CUSTOM_MESSAGE for all cmdIds (not just 1)
  state.trtc.on(TRTC.EVENT.CUSTOM_MESSAGE, (event) => {
    console.log('[TRTC] CUSTOM_MESSAGE cmdId=%d bytes=%d', event.cmdId, event.data?.byteLength ?? 0);
    handleCmdData(event.data);
  });

  // Also listen to SEI_MESSAGE in case the bot uses that channel
  if (TRTC.EVENT.SEI_MESSAGE) {
    state.trtc.on(TRTC.EVENT.SEI_MESSAGE, (event) => {
      console.log('[TRTC] SEI_MESSAGE bytes=%d', event.data?.byteLength ?? 0);
      handleCmdData(event.data);
    });
  }

  await state.trtc.enterRoom({
    sdkAppId: Number(sdkAppId),
    userId,
    userSig,
    roomId: Number(roomId),
  });

  await state.trtc.startLocalAudio();
  await state.trtc.startLocalVideo({ view: 'local-video' });
  // Note: AI transcription bot is started by Android (the caller) via StartAITranscription REST API.
  // Web does NOT call StartAITranscription to avoid creating a duplicate task with the same
  // bot userId, which can interfere with the translation feature.
}

async function leaveRoom() {
  if (!state.trtc) return;
  subtitleRounds.clear();
  lastTranscriptionText = '';
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
  setCallStatus(t('statusConnecting'));

  try {
    const userSig = await genTestUserSig(state.userId);

    // Notify Android we accepted
    sigSend(fromUserId, { action: 'accept', roomId });

    await joinRoom(roomId, state.userId, userSig, state.sdkAppId);
    state.inCall = true;
    setCallStatus(t('statusInCall'));
    startTimer();
  } catch (err) {
    console.error('[acceptCall]', err);
    setCallStatus(t('statusConnFail', err.message));
    setTimeout(() => endCall(t('connFailed')), 2000);
  }
}

async function endCall(reason = '') {
  if (state.timerInterval) stopTimer();

  // Stop transcription bot and hide subtitles
  stopTranscription();
  clearTimeout(subtitleHideTimer);
  $('subtitle-overlay').classList.add('hidden');

  // Notify other side
  if (state.pendingInvite) {
    sigSend(state.pendingInvite.fromUserId, {
      action: 'hangup',
      roomId: state.pendingInvite.roomId,
    });
  }

  await leaveRoom();
  state.inCall = false;
  state.pendingInvite = null;
  state.micMuted = false;
  state.camOff = false;
  state.screenShareActive = false;
  state.remoteUserId = null;

  remotePlaceholder.style.display = 'flex';
  showScreen('login');
  if (reason) setLoginStatus(reason);
}

// ─── Controls ─────────────────────────────────────────────────────────────────
$('btn-lang').addEventListener('click', () => {
  lang = lang === 'en' ? 'zh' : 'en';
  applyLanguage();
});

$('btn-login').addEventListener('click', async () => {
  const userId = $('input-userid').value.trim();
  if (!userId) return setLoginStatus(t('errEnterUserId'), 'error');

  $('btn-login').disabled = true;
  setLoginStatus(t('statusLoggingIn'));

  try {
    state.userId = userId;
    await initChatSDK(userId);
    setLoginStatus(t('statusLoggedIn', userId), 'success');
  } catch (err) {
    console.error('[Login]', err);
    setLoginStatus(t('statusLoginFail', err.message), 'error');
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
  setLoginStatus(t('callDeclined'));
});

$('btn-hangup').addEventListener('click', () => endCall());

$('btn-toggle-mic').addEventListener('click', async () => {
  if (!state.trtc) return;
  state.micMuted = !state.micMuted;
  await state.trtc.updateLocalAudio({ mute: state.micMuted });
  $('btn-toggle-mic').classList.toggle('muted', state.micMuted);
  // label is now managed in HTML ctrl-label span, no JS update needed
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
  // label is now managed in HTML ctrl-label span, no JS update needed
});

// ─── Init language on load ────────────────────────────────────────────────────
applyLanguage();
