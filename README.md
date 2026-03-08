# OKTEST-Gemini — TRTC 1v1 视频通话 Demo

> **Android ↔ Web 实时视频通话 Demo**，基于腾讯云 TRTC SDK 传输音视频，双端均通过 **腾讯云 Chat SDK C2C 消息** 完成呼叫信令，并集成 **AI 实时转录 + 翻译字幕**功能。

---

## 目录

- [项目介绍](#项目介绍)
- [架构总览](#架构总览)
- [信令协议](#信令协议)
- [快速开始](#快速开始)
  - [前置条件](#前置条件)
  - [1. 启动后端服务器](#1-启动后端服务器)
  - [2. 配置 Android 端](#2-配置-android-端)
  - [3. 配置 Web 端](#3-配置-web-端)
- [功能特性](#功能特性)
- [AI 转录与翻译字幕](#ai-转录与翻译字幕)
- [目录结构](#目录结构)
- [部署](#部署)
- [常见问题](#常见问题)
- [注意事项](#注意事项)
- [相关链接](#相关链接)

---

## 项目介绍

本项目演示如何利用腾讯云 TRTC 和 Chat SDK 构建跨平台 1v1 视频通话能力，并通过腾讯云 AI 转录服务实现实时字幕与翻译：

| 端 | 技术栈 | 角色 |
|----|--------|------|
| **Android** | Kotlin + TRTC SDK + Chat IM SDK | 主叫方（发起通话）|
| **Web** | Vanilla JS + TRTC Web SDK v5 + Chat SDK | 被叫方（接受通话）|
| **Server** | Node.js + Express | 后端服务（UserSig 生成 + AI Bot 管理）|

- **音视频流**：经由 TRTC 云端传输
- **呼叫信令**（invite / accept / decline / hangup）：双端均通过 Chat SDK C2C 自定义消息实现
- **AI 字幕**：通话开始后自动为每位用户启动 AI Bot，实时转录语音并翻译为英文
- **UserSig**：由后端服务器统一生成，客户端不再持有 SecretKey

---

## 架构总览

```
┌────────────────────────────────────────────────────────────────┐
│                   腾讯云 Chat IM 服务（信令层）                  │
│  Android  ──── C2C invite  ────────────────────►  Web         │
│  Android  ◄─── C2C accept / decline / hangup ───  Web         │
└────────────────────────────────────────────────────────────────┘

┌────────────────┐    TRTC 云端（音视频流）    ┌────────────────┐
│  Android App   │ ◄──────────────────────► │   Web 浏览器   │
│  （主叫方）     │                           │  （被叫方）     │
└────────────────┘                           └────────────────┘
         │                                           │
         │  HTTP /api/ai/start                       │
         ▼                                           ▼
┌────────────────────────────────────────────────────────────────┐
│              Node.js 后端服务器 (server/)                       │
│  • POST /api/usersig   — 生成 UserSig                          │
│  • POST /api/ai/start  — 启动 AI 转录 Bot                      │
│  • POST /api/ai/stop   — 停止 AI 转录 Bot                      │
└────────────────────────────────────────────────────────────────┘
         │
         ▼  腾讯云 StartAITranscription API
┌────────────────────────────────────────────────────────────────┐
│         腾讯云 AI 转录服务（语音识别 + 翻译）                    │
│  识别语言: zh-en (16k_zh_en)                                   │
│  翻译目标: English (en)                                        │
│  结果通过 TRTC 自定义消息实时推送给房间内用户                     │
└────────────────────────────────────────────────────────────────┘
```

### 通话建立流程

```
1. Android / Web 向 Server 请求 UserSig → 登录 Chat SDK
2. Android 进入 TRTC 房间（随机 roomId）
3. Android 通过 Chat C2C 发送 invite（含 roomId）给指定 toUserId
4. Web 收到 invite → 显示来电界面 → 用户点击接听
5. Web 通过 Chat C2C 回复 accept → 进入相同 roomId 的 TRTC 房间
6. TRTC 云端完成音视频连接，双端通话开始
7. Android 向 Server 请求为每位用户启动 AI Bot（/api/ai/start）
8. AI Bot 加入房间，实时转录语音并通过自定义消息推送字幕
9. 任意一方挂断 → Chat C2C 发送 hangup → 双端退出 TRTC 房间 → 停止 AI Bot
```

---

## 信令协议

双端使用 Chat SDK C2C 自定义消息传递 JSON 信令，消息格式如下：

```json
{
  "action": "invite | accept | decline | hangup",
  "roomId": 123456
}
```

> `invite` 消息额外携带 `"sdkAppId"` 字段，供接收方确认 App 信息。

| action | 方向 | 说明 |
|--------|------|------|
| `invite` | Android → Web | 携带 roomId，邀请对方加入通话 |
| `accept` | Web → Android | 已接受呼叫，即将进入房间 |
| `decline` | Web → Android | 已拒绝呼叫 |
| `hangup` | 任意一方 → 另一方 | 通知对方结束通话 |

**Android 实现**：`SignalingClient.kt`
**Web 实现**：`chat.createCustomMessage()` + `chat.sendMessage()`（`web/app.js`）

---

## 快速开始

### 前置条件

- **腾讯云账号**：在 [TRTC 控制台](https://console.trtc.io) 创建应用，获取 `SDKAppID` 和 `SecretKey`
  - TRTC 与 Chat IM 共用同一个 SDKAppID（控制台开通后自动关联）
  - 需开通 **AI 转录**功能（控制台 → 实时音视频 → AI 转录）
- **腾讯云 API 密钥**：在 [CAM 控制台](https://console.cloud.tencent.com/cam/capi) 获取 `SecretId` 和 `SecretKey`
- **Node.js 18+**（运行后端服务器）
- **Android Studio**（构建 Android 端）
- Android 设备或模拟器（API 21+，建议真机测试摄像头 / 麦克风）
- 支持摄像头的现代浏览器（Chrome / Edge / Firefox / Safari）

---

### 1. 启动后端服务器

后端服务器负责生成 UserSig 和管理 AI Bot，**客户端不再需要持有任何密钥**。

```bash
cd server
cp .env.example .env
# 编辑 .env，填入你的密钥
nano .env
```

`.env` 配置项：

```env
SDK_APP_ID=你的SDKAppID
SECRET_KEY=你的TRTC_SecretKey
CLOUD_SECRET_ID=你的腾讯云API_SecretId
CLOUD_SECRET_KEY=你的腾讯云API_SecretKey
PORT=3000
ALLOWED_ORIGINS=http://localhost:8080,http://127.0.0.1:8080
```

```bash
npm install
npm start
# 服务器启动在 http://localhost:3000
```

---

### 2. 配置 Android 端

编辑 [android/app/src/main/java/com/trtcdemo/Config.kt](android/app/src/main/java/com/trtcdemo/Config.kt)：

```kotlin
object Config {
    const val SDK_APP_ID: Int         = 0      // TODO: 替换为你的 SDKAppID
    const val DEFAULT_USER_ID: String = "android_user_001"
    const val SERVER_URL: String      = "http://10.0.2.2:3000"  // 模拟器用 10.0.2.2，真机用服务器 IP
}
```

> ⚠️ `Config.kt` 中不再需要 `SECRET_KEY`，密钥已移至服务器端。

**构建并安装：**

```bash
cd android
./gradlew installDebug
# 或在 Android Studio 中点击 Run
```

**使用方式：**
1. 打开 App，进入登录界面
2. 输入**我的用户 ID**（例如 `android_001`）
3. 输入**对方用户 ID**（需与 Web 端登录的 ID 一致，例如 `web_001`）
4. 点击**发起视频通话**

---

### 3. 配置 Web 端

编辑 [web/app.js](web/app.js) 顶部的 `CONFIG`：

```js
const CONFIG = {
  SDK_APP_ID: 0,          // TODO: 替换为你的 SDKAppID
  SERVER_URL: 'http://localhost:3000',  // 后端服务器地址
};
```

> ⚠️ Web 端不再需要 `SECRET_KEY`，UserSig 由服务器生成。

**打开页面：**

```bash
cd web
npx serve .
# 或
python3 -m http.server 8080
```

**使用方式：**
1. 输入**用户 ID**（需与 Android 填写的「对方用户 ID」一致）
2. 点击**登录 & 等待来电**
3. Android 发起通话后，Web 端显示来电界面
4. 点击**接听**开始视频通话，字幕自动显示

---

## 功能特性

### 视频通话
| 功能 | Android | Web |
|------|:-------:|:---:|
| 实时视频 | ✅ | ✅ |
| 实时音频 | ✅ | ✅ |
| 静音麦克风 | ✅ | ✅ |
| 关闭摄像头 | ✅ | ✅ |
| 前后摄像头切换 | ✅ | — |
| 视频分辨率调节 | ✅ (360p–1080p) | — |
| 通话计时器 | ✅ | ✅ |

### 屏幕共享
- **Android**：点击"共享屏幕"后，设备屏幕以 SUB 流发布至 TRTC 房间
- **Web**：自动检测 SUB 流，将屏幕内容显示在主视图，本地摄像头保持画中画

### 呼叫管理
- 主叫方通过 Chat C2C `invite` 消息定向邀请指定用户（非广播）
- 被叫方离线时，Chat SDK 消息在其上线后自动送达
- 任意一方挂断，另一方收到 `hangup` 消息后同步退出房间

---

## AI 转录与翻译字幕

通话建立后，后端服务器会为房间内每位用户各启动一个 AI Bot，实现实时语音转录和翻译字幕。

### 工作原理

```
用户说话
  → TRTC 采集音频
  → AI Bot 调用腾讯云语音识别（16k_zh_en，支持中英混说）
  → 识别结果翻译为英文
  → 通过 TRTC 自定义消息（cmdID=1）实时推送给房间内所有用户
  → Android / Web 客户端解析消息，渲染字幕
```

### 字幕消息格式

AI Bot 通过 TRTC 自定义消息推送两类消息：

**转录消息（type=10000）：**
```json
{
  "type": 10000,
  "sender": "android_user_001",
  "payload": {
    "text": "你好，这是一段测试",
    "end": true,
    "roundid": "xxx"
  }
}
```

**翻译消息（type=10000，含 translation_text）：**
```json
{
  "type": 10000,
  "sender": "android_user_001",
  "payload": {
    "text": "你好，这是一段测试",
    "translation_text": "Hello, this is a test",
    "translation_language": "en",
    "end": true,
    "roundid": "xxx"
  }
}
```

### 字幕显示逻辑

- **非 final 消息**：实时更新当前字幕（流式显示）
- **final 消息**：字幕固定，3 秒后自动淡出
- **双语显示**：原文（中/英混合）+ 英文翻译同时显示
- **多用户**：每位用户的字幕独立显示，标注发言人

### 后端 API

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/usersig` | POST | 生成 UserSig |
| `/api/ai/start` | POST | 启动 AI 转录 Bot（`{ roomId, botUserId }`）|
| `/api/ai/stop` | POST | 停止 AI 转录 Bot（`{ taskId }`）|

---

## 目录结构

```
OKTEST-Gemini/
├── android/                              # Android 原生应用
│   └── app/src/main/java/com/trtcdemo/
│       ├── Config.kt                     # SDKAppID / SERVER_URL 配置入口
│       ├── MainActivity.kt               # 登录界面
│       ├── CallActivity.kt               # 通话界面主控制器
│       ├── TRTCManager.kt                # TRTC SDK 封装 + AI 字幕消息解析
│       ├── SignalingClient.kt            # Chat IM SDK 信令（C2C 自定义消息）
│       ├── ServerApi.kt                  # 后端 HTTP 客户端（UserSig + AI Bot）
│       ├── UserSigHelper.kt              # UserSig 本地生成工具（备用）
│       └── ScreenShareService.kt         # 屏幕共享前台服务
├── server/                               # Node.js 后端服务器
│   ├── index.js                          # API 路由（UserSig + AI Bot 管理）
│   ├── .env.example                      # 环境变量模板（不含真实密钥）
│   ├── Dockerfile                        # Docker 镜像构建
│   └── package.json
├── web/                                  # Web 客户端
│   ├── index.html                        # 页面结构
│   ├── app.js                            # 核心逻辑（Chat SDK 信令 + TRTC + 字幕）
│   ├── style.css                         # 暗色主题样式
│   ├── Dockerfile                        # Docker 镜像构建（nginx）
│   └── nginx.conf                        # nginx 配置
├── k8s/                                  # Kubernetes 部署配置
│   ├── deployment.yaml                   # Deployment + Service 定义
│   └── secret.yaml.example              # Secret 模板（不含真实密钥）
├── docker-compose.yml                    # 本地 Docker Compose 一键启动
├── deploy.sh                             # TKE 部署脚本
└── .gitignore                            # 排除 .env、k8s/secret.yaml 等敏感文件
```

---

## 部署

### 本地 Docker Compose

```bash
# 复制并填写密钥
cp server/.env.example server/.env
nano server/.env

# 一键启动 server + web
docker-compose up --build
# server: http://localhost:3000
# web:    http://localhost:8080
```

### 腾讯云 TKE（Kubernetes）

```bash
# 1. 复制并填写 K8s Secret
cp k8s/secret.yaml.example k8s/secret.yaml
# 编辑 k8s/secret.yaml，填入 base64 编码的密钥值
# echo -n "your_value" | base64

# 2. 执行部署脚本
chmod +x deploy.sh
./deploy.sh
```

> ⚠️ `k8s/secret.yaml` 已加入 `.gitignore`，**永远不要提交真实密钥文件**。

---

## 常见问题

**Q：Android 无法连接到服务器**
- 模拟器使用 `10.0.2.2` 访问宿主机，真机需填写宿主机的局域网 IP
- 确认服务器已启动（`npm start`），端口 3000 未被防火墙拦截

**Q：AI 字幕没有显示**
- 确认腾讯云账号已开通 AI 转录功能
- 查看服务器日志，确认 `/api/ai/start` 返回了 `taskId`
- 查看 Android Logcat 中 `TRTCManager` 的日志，确认收到了 `type=10000` 的自定义消息

**Q：Web 端没有收到来电**
- 确认 Web 端登录的用户 ID 与 Android 填写的「对方用户 ID」完全一致（区分大小写）
- 查看浏览器控制台，确认 Chat SDK 登录成功（无报错）

**Q：Chat SDK 登录失败**
- 确认服务器 `.env` 中 `SDK_APP_ID` 和 `SECRET_KEY` 填写正确
- 错误码 `-101001` 通常表示同一账号在其他设备已登录被踢下线，重启即可

**Q：TRTC 进入房间失败（错误码为负数）**
- 确认 `SDK_APP_ID` 填写正确
- 确认 TRTC 控制台该应用套餐仍在有效期内

**Q：屏幕共享后 Web 端显示黑屏**
- 等待 1–2 秒，SUB 流需要短暂初始化时间
- 查看浏览器控制台是否有 `SUB render ERR` 日志

---

## 注意事项

### 安全

- `server/.env` 和 `k8s/secret.yaml` 均已加入 `.gitignore`，**不会提交到 Git**
- 客户端（Android / Web）不持有任何密钥，所有敏感操作均在服务器端完成
- 生产环境建议为服务器配置 HTTPS 和严格的 CORS 白名单

### Android 权限

| 权限 | 用途 |
|------|------|
| `CAMERA` | 视频通话摄像头 |
| `RECORD_AUDIO` | 视频通话麦克风 |
| `INTERNET` | 网络访问 |
| `FOREGROUND_SERVICE` | 屏幕共享前台服务（Android 9+）|
| `SYSTEM_ALERT_WINDOW` | 屏幕共享悬浮指示（可选）|

### 浏览器兼容性

Web 端依赖 TRTC Web SDK v5 和 Web Crypto API：
Chrome 56+ / Edge 80+ / Firefox 56+ / Safari 11+

---

## 相关链接

- [腾讯云 TRTC 控制台](https://console.trtc.io)
- [TRTC Android SDK 文档](https://cloud.tencent.com/document/product/647/32236)
- [TRTC Web SDK v5 文档](https://cloud.tencent.com/document/product/647/74928)
- [腾讯云 Chat IM SDK（Android）](https://cloud.tencent.com/document/product/269/75283)
- [腾讯云 Chat Web SDK](https://cloud.tencent.com/document/product/269/75290)
- [AI 转录 API 文档](https://cloud.tencent.com/document/product/647/107952)
- [UserSig 生成说明](https://cloud.tencent.com/document/product/647/17275)
