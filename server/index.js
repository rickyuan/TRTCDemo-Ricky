/**
 * TRTC Signaling Server
 *
 * Responsibilities:
 *  1. Generate UserSig for both Android and Web clients
 *  2. Relay invitation / answer / hangup signals via WebSocket
 *
 * Setup:
 *  1. Fill in SDK_APP_ID and SECRET_KEY below (from https://console.trtc.io)
 *  2. npm install
 *  3. node index.js
 *
 * Endpoints:
 *  GET  /usersig?userId=<id>&expire=<seconds>   → { userSig, sdkAppId }
 *  GET  /health                                  → { status: "ok" }
 *  WS   ws://host:3000                           → signaling channel
 *
 * WebSocket message protocol (JSON):
 *   Client → Server:
 *     { type: "register",  userId: string, platform: "android"|"web" }
 *     { type: "invite",    roomId: number, fromUserId: string, toUserId?: string }
 *     { type: "accept",    roomId: number, fromUserId: string, toUserId: string }
 *     { type: "decline",   roomId: number, fromUserId: string, toUserId: string }
 *     { type: "hangup",    roomId: number, fromUserId: string }
 *     { type: "candidate", to: string, candidate: object }  // ICE (passthrough)
 *
 *   Server → Client:
 *     { type: "invite",   roomId: number, fromUserId: string, sdkAppId: number }
 *     { type: "accept",   roomId: number, fromUserId: string }
 *     { type: "decline",  roomId: number, fromUserId: string }
 *     { type: "hangup",   roomId: number, fromUserId: string }
 *     { type: "error",    message: string }
 */

const express = require('express');
const http = require('http');
const path = require('path');
const WebSocket = require('ws');
const cors = require('cors');
const { Api: TLSSigAPIv2 } = require('tls-sig-api-v2');

// ─── CONFIG ────────────────────────────────────────────────────────────────────
const SDK_APP_ID = 20026228;
const SECRET_KEY = '070689a18f17b23fefddbcabacb9dd46d091d3cb8b1fdaf39f74055ea90f03c4';
const PORT = 3000;
// ───────────────────────────────────────────────────────────────────────────────

if (SDK_APP_ID === 0 || SECRET_KEY === 'YOUR_SECRET_KEY_HERE') {
  console.warn('⚠️  WARNING: SDK_APP_ID and SECRET_KEY are not configured in server/index.js');
}

// ─── UserSig Generation ─────────────────────────────────────────────────────
const tlsApi = new TLSSigAPIv2(SDK_APP_ID, SECRET_KEY);

function genUserSig(userId, expireSeconds = 604800 /* 7 days */) {
  return tlsApi.genSig(userId, expireSeconds);
}

// ─── Express App ─────────────────────────────────────────────────────────────
const app = express();
app.use(cors());
app.use(express.json());

// Serve web client static files
app.use(express.static(path.join(__dirname, '..', 'web')));

app.get('/health', (req, res) => {
  res.json({ status: 'ok', sdkAppId: SDK_APP_ID });
});

// Debug: list connected clients
app.get('/debug/clients', (req, res) => {
  const list = [];
  clients.forEach((ws, uid) => list.push({ userId: uid, readyState: ws.readyState }));
  res.json({ count: list.length, clients: list });
});

app.get('/usersig', (req, res) => {
  const { userId, expire } = req.query;
  if (!userId) {
    return res.status(400).json({ error: 'userId is required' });
  }
  try {
    const userSig = genUserSig(userId, Number(expire) || 604800);
    res.json({ userSig, sdkAppId: SDK_APP_ID });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ─── HTTP + WebSocket Server ──────────────────────────────────────────────────
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

// Map: userId → WebSocket
const clients = new Map();

function broadcast(message, excludeUserId = null) {
  const data = JSON.stringify(message);
  let sent = 0;
  clients.forEach((ws, uid) => {
    if (uid !== excludeUserId && ws.readyState === WebSocket.OPEN) {
      ws.send(data);
      sent++;
    }
  });
  console.log(`[WS] broadcast type=${message.type} to ${sent} client(s), total registered=${clients.size}`);
}

function sendTo(userId, message) {
  const ws = clients.get(userId);
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(message));
    return true;
  }
  return false;
}

wss.on('connection', (ws, req) => {
  let registeredUserId = null;
  console.log(`[WS] New connection from ${req.socket.remoteAddress}`);

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      ws.send(JSON.stringify({ type: 'error', message: 'Invalid JSON' }));
      return;
    }

    console.log(`[WS] ← ${JSON.stringify(msg)}`);

    switch (msg.type) {
      // Client identifies itself
      case 'register': {
        registeredUserId = msg.userId;
        clients.set(msg.userId, ws);
        console.log(`[WS] Registered: ${msg.userId} (${msg.platform || 'unknown'})`);
        ws.send(JSON.stringify({ type: 'registered', userId: msg.userId }));
        break;
      }

      // Android invites all web clients (or a specific user)
      case 'invite': {
        const payload = {
          type: 'invite',
          roomId: msg.roomId,
          fromUserId: msg.fromUserId,
          sdkAppId: SDK_APP_ID,
        };
        if (msg.toUserId) {
          // Directed invite
          if (!sendTo(msg.toUserId, payload)) {
            ws.send(JSON.stringify({ type: 'error', message: `User ${msg.toUserId} not online` }));
          }
        } else {
          // Broadcast to all other clients
          broadcast(payload, msg.fromUserId);
        }
        break;
      }

      // Web accepts the call
      case 'accept': {
        sendTo(msg.toUserId, {
          type: 'accept',
          roomId: msg.roomId,
          fromUserId: msg.fromUserId,
        });
        break;
      }

      // Web declines the call
      case 'decline': {
        sendTo(msg.toUserId, {
          type: 'decline',
          roomId: msg.roomId,
          fromUserId: msg.fromUserId,
        });
        break;
      }

      // Either side hangs up
      case 'hangup': {
        broadcast(
          { type: 'hangup', roomId: msg.roomId, fromUserId: msg.fromUserId },
          msg.fromUserId,
        );
        break;
      }

      default:
        ws.send(JSON.stringify({ type: 'error', message: `Unknown message type: ${msg.type}` }));
    }
  });

  ws.on('close', () => {
    if (registeredUserId) {
      clients.delete(registeredUserId);
      console.log(`[WS] Disconnected: ${registeredUserId}`);
      // Notify others that this user went offline
      broadcast({ type: 'user_offline', userId: registeredUserId }, registeredUserId);
    }
  });

  ws.on('error', (err) => {
    console.error(`[WS] Error for ${registeredUserId}: ${err.message}`);
  });
});

server.listen(PORT, () => {
  console.log(`\n🚀 TRTC Signaling Server running on http://localhost:${PORT}`);
  console.log(`   UserSig API: GET http://localhost:${PORT}/usersig?userId=xxx`);
  console.log(`   WebSocket:   ws://localhost:${PORT}`);
  if (SDK_APP_ID === 0) {
    console.log('\n⚠️  Configure SDK_APP_ID and SECRET_KEY in server/index.js to enable UserSig generation\n');
  }
});
