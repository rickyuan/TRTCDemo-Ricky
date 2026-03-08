'use strict';

require('dotenv').config();

const express = require('express');
const cors    = require('cors');
const crypto  = require('crypto');
const https   = require('https');
const zlib    = require('zlib');

// ─── Config (from environment variables only) ─────────────────────────────────
const SDK_APP_ID      = parseInt(process.env.SDK_APP_ID, 10);
const SECRET_KEY      = process.env.SECRET_KEY;
const CLOUD_SECRET_ID = process.env.CLOUD_SECRET_ID;
const CLOUD_SECRET_KEY= process.env.CLOUD_SECRET_KEY;
const PORT            = parseInt(process.env.PORT || '3000', 10);
const ALLOWED_ORIGINS = (process.env.ALLOWED_ORIGINS || '').split(',').map(s => s.trim()).filter(Boolean);

// Validate required env vars on startup
['SDK_APP_ID', 'SECRET_KEY', 'CLOUD_SECRET_ID', 'CLOUD_SECRET_KEY'].forEach(key => {
  if (!process.env[key]) {
    console.error(`[Config] Missing required environment variable: ${key}`);
    process.exit(1);
  }
});

// ─── Express setup ────────────────────────────────────────────────────────────
const app = express();
app.use(express.json());

// CORS — allow configured origins (or all origins if list is empty)
app.use(cors({
  origin: ALLOWED_ORIGINS.length > 0
    ? (origin, cb) => {
        // Allow requests with no origin (e.g. Android/mobile clients, curl)
        if (!origin || ALLOWED_ORIGINS.includes(origin)) return cb(null, true);
        cb(new Error(`CORS: origin ${origin} not allowed`));
      }
    : '*',
  methods: ['GET', 'POST'],
}));

// ─── UserSig generation ───────────────────────────────────────────────────────

/**
 * Generate a TRTC/Chat UserSig for the given userId.
 * Algorithm: HMAC-SHA256 → deflate → base64url
 */
function genUserSig(userId, expireSeconds = 604800) {
  const currTime = Math.floor(Date.now() / 1000);

  const contentToBeSigned =
    `TLS.identifier:${userId}\n` +
    `TLS.sdkappid:${SDK_APP_ID}\n` +
    `TLS.time:${currTime}\n` +
    `TLS.expire:${expireSeconds}\n`;

  const sig = crypto
    .createHmac('sha256', SECRET_KEY)
    .update(contentToBeSigned)
    .digest('base64');

  const sigDoc = JSON.stringify({
    'TLS.ver':        '2.0',
    'TLS.identifier': userId,
    'TLS.sdkappid':   SDK_APP_ID,
    'TLS.expire':     expireSeconds,
    'TLS.time':       currTime,
    'TLS.sig':        sig,
  });

  const compressed = zlib.deflateSync(Buffer.from(sigDoc, 'utf8'));
  return compressed.toString('base64')
    .replace(/\+/g, '*')
    .replace(/\//g, '-')
    .replace(/=/g,  '_');
}

// ─── Tencent Cloud API v3 (TC3-HMAC-SHA256) ──────────────────────────────────

const TC_HOST    = 'trtc.intl.tencentcloudapi.com';
const TC_SERVICE = 'trtc';
const TC_VERSION = '2019-07-22';
const TC_REGION  = 'ap-singapore';

function utcDate(epochSeconds) {
  return new Date(epochSeconds * 1000).toISOString().slice(0, 10);
}

function sha256Hex(str) {
  return crypto.createHash('sha256').update(str, 'utf8').digest('hex');
}

function hmacSha256Bytes(key, data) {
  return crypto.createHmac('sha256', key).update(data, 'utf8').digest();
}

/**
 * Build TC3-HMAC-SHA256 Authorization header.
 * Spec: https://www.tencentcloud.com/document/product/1427/65993
 */
function buildAuthorization(action, payload) {
  const timestamp   = Math.floor(Date.now() / 1000);
  const date        = utcDate(timestamp);
  const contentType = 'application/json; charset=utf-8';

  // Step 1: canonical request
  const canonicalHeaders = `content-type:${contentType}\nhost:${TC_HOST}\n`;
  const signedHeaders    = 'content-type;host';
  const hashedPayload    = sha256Hex(payload);
  const canonicalRequest = `POST\n/\n\n${canonicalHeaders}\n${signedHeaders}\n${hashedPayload}`;

  // Step 2: string to sign
  const credentialScope = `${date}/${TC_SERVICE}/tc3_request`;
  const stringToSign    = `TC3-HMAC-SHA256\n${timestamp}\n${credentialScope}\n${sha256Hex(canonicalRequest)}`;

  // Step 3: derive signing key
  const keyDate    = hmacSha256Bytes(Buffer.from('TC3' + CLOUD_SECRET_KEY, 'utf8'), date);
  const keyService = hmacSha256Bytes(keyDate, TC_SERVICE);
  const keySigning = hmacSha256Bytes(keyService, 'tc3_request');
  const signature  = hmacSha256Bytes(keySigning, stringToSign).toString('hex');

  const authorization =
    `TC3-HMAC-SHA256 Credential=${CLOUD_SECRET_ID}/${credentialScope}, ` +
    `SignedHeaders=${signedHeaders}, Signature=${signature}`;

  return { authorization, timestamp, contentType };
}

/**
 * Call a Tencent Cloud TRTC REST API action.
 */
function callTencentCloudApi(action, payload) {
  return new Promise((resolve, reject) => {
    const { authorization, timestamp, contentType } = buildAuthorization(action, payload);
    const bodyBuf = Buffer.from(payload, 'utf8');

    const req = https.request({
      hostname: TC_HOST,
      path:     '/',
      method:   'POST',
      headers: {
        'Content-Type':  contentType,
        'Host':           TC_HOST,
        'X-TC-Action':    action,
        'X-TC-Version':   TC_VERSION,
        'X-TC-Timestamp': String(timestamp),
        'X-TC-Region':    TC_REGION,
        'Authorization':  authorization,
        'Content-Length': bodyBuf.length,
      },
    }, (res) => {
      let data = '';
      res.on('data', chunk => { data += chunk; });
      res.on('end', () => {
        try { resolve(JSON.parse(data)); }
        catch (e) { reject(new Error(`Invalid JSON from Tencent Cloud: ${data}`)); }
      });
    });

    req.on('error', reject);
    req.write(bodyBuf);
    req.end();
  });
}

// ─── Routes ───────────────────────────────────────────────────────────────────

/**
 * POST /api/usersig
 * Body: { userId: string, expire?: number }
 * Returns: { sdkAppId, userId, userSig, expire }
 */
app.post('/api/usersig', (req, res) => {
  const { userId, expire } = req.body || {};
  if (!userId || typeof userId !== 'string' || !userId.trim()) {
    return res.status(400).json({ error: 'userId is required' });
  }
  const expireSeconds = Number(expire) > 0 ? Number(expire) : 604800;
  try {
    const userSig = genUserSig(userId.trim(), expireSeconds);
    console.log(`[UserSig] generated for userId=${userId}`);
    res.json({ sdkAppId: SDK_APP_ID, userId: userId.trim(), userSig, expire: expireSeconds });
  } catch (e) {
    console.error('[UserSig] error:', e);
    res.status(500).json({ error: 'Failed to generate UserSig' });
  }
});

/**
 * POST /api/ai/start
 * Body: { roomId: number, botUserId: string }
 * Returns: { taskId: string }
 *
 * The server generates botUserSig internally — the client never needs the SECRET_KEY.
 */
app.post('/api/ai/start', async (req, res) => {
  const { roomId, botUserId } = req.body || {};
  if (!roomId || !botUserId) {
    return res.status(400).json({ error: 'roomId and botUserId are required' });
  }

  try {
    const botUserSig = genUserSig(String(botUserId));
    const payload = JSON.stringify({
      SdkAppId: SDK_APP_ID,
      RoomId:   String(roomId),
      RoomIdType: 0,
      TranscriptionParams: {
        UserId:  botUserId,
        UserSig: botUserSig,
      },
      RecognizeConfig:   { Language: '16k_zh_en' },
      TranslationConfig: { TargetLanguages: ['en', 'zh'] },
    });

    console.log(`[AI Start] payload=${payload}`);
    const data = await callTencentCloudApi('StartAITranscription', payload);
    const errInfo = data?.Response?.Error;
    if (errInfo) {
      console.error(`[AI Start] API error: ${errInfo.Code} - ${errInfo.Message}`);
      return res.status(502).json({ error: errInfo.Message, code: errInfo.Code });
    }

    const taskId = data?.Response?.TaskId;
    console.log(`[AI Start] roomId=${roomId} taskId=${taskId}`);
    res.json({ taskId });
  } catch (e) {
    console.error('[AI Start] error:', e);
    res.status(500).json({ error: 'Failed to start AI transcription' });
  }
});

/**
 * POST /api/ai/stop
 * Body: { taskId: string }
 * Returns: { ok: true }
 */
app.post('/api/ai/stop', async (req, res) => {
  const { taskId } = req.body || {};
  if (!taskId) {
    return res.status(400).json({ error: 'taskId is required' });
  }

  try {
    const payload = JSON.stringify({ SdkAppId: SDK_APP_ID, TaskId: taskId });
    await callTencentCloudApi('StopAITranscription', payload);
    console.log(`[AI Stop] taskId=${taskId}`);
    res.json({ ok: true });
  } catch (e) {
    console.error('[AI Stop] error:', e);
    res.status(500).json({ error: 'Failed to stop AI transcription' });
  }
});

// Health check
app.get('/health', (_, res) => res.json({ status: 'ok', sdkAppId: SDK_APP_ID }));

// ─── Start ────────────────────────────────────────────────────────────────────
app.listen(PORT, () => {
  console.log(`[Server] TRTC Demo backend running on http://localhost:${PORT}`);
  console.log(`[Server] SDK_APP_ID=${SDK_APP_ID}  CORS origins=${ALLOWED_ORIGINS.join(', ') || '*'}`);
});
