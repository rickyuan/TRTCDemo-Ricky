# OKTEST-Gemini — TRTC 1v1 视频通话 Demo

> **Android ↔ Web 实时视频通话 Demo**，基于腾讯云 TRTC SDK 传输音视频，双端均通过 **腾讯云 Chat SDK C2C 消息** 完成呼叫信令，无需自建信令服务器。

---

## 目录

- [项目介绍](#项目介绍)
- [架构总览](#架构总览)
- [信令协议](#信令协议)
- [快速开始](#快速开始)
  - [前置条件](#前置条件)
  - [1. 配置 Android 端](#1-配置-android-端)
  - [2. 配置 Web 端](#2-配置-web-端)
- [功能特性](#功能特性)
- [目录结构](#目录结构)
- [常见问题](#常见问题)
- [注意事项](#注意事项)
- [相关链接](#相关链接)

---

## 项目介绍

本项目演示如何利用腾讯云 TRTC 和 Chat SDK 构建跨平台 1v1 视频通话能力：

| 端 | 技术栈 | 角色 |
|----|--------|------|
| **Android** | Kotlin + TRTC SDK + Chat IM SDK (`imsdk-plus`) | 主叫方（发起通话）|
| **Web** | Vanilla JS + TRTC Web SDK v5 + Chat SDK (`@tencentcloud/chat`) | 被叫方（接受通话）|

- **音视频流**：经由 TRTC 云端传输
- **呼叫信令**（invite / accept / decline / hangup）：双端均通过 Chat SDK C2C 自定义消息实现
- **UserSig**：双端均在客户端本地生成（适用于开发 / 测试；生产环境应移至服务端）

---

## 架构总览

```
┌────────────────────────────────────────────────────────────────┐
│                   腾讯云 Chat IM 服务（信令层）                  │
│                                                                │
│  Android  ──── C2C invite  ────────────────────►  Web         │
│  Android  ◄─── C2C accept / decline / hangup ───  Web         │
└────────────────────────────────────────────────────────────────┘

┌────────────────┐    TRTC 云端（音视频流）    ┌────────────────┐
│  Android App   │ ◄──────────────────────► │   Web 浏览器   │
│  （主叫方）     │                           │  （被叫方）     │
└────────────────┘                           └────────────────┘
```

### 通话建立流程

```
1. Android 本地生成 UserSig → 登录 Chat SDK
2. Web     本地生成 UserSig → 登录 Chat SDK → 等待来电
3. Android 进入 TRTC 房间（随机 roomId）
4. Android 通过 Chat C2C 发送 invite（含 roomId）给指定 toUserId
5. Web 收到 invite → 显示来电界面 → 用户点击接听
6. Web 通过 Chat C2C 回复 accept → 进入相同 roomId 的 TRTC 房间
7. TRTC 云端完成音视频连接，双端通话开始
8. 任意一方挂断 → Chat C2C 发送 hangup → 双端退出 TRTC 房间
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

**Android 实现**：`V2TIMManager.getInstance().sendC2CCustomMessage()`（[SignalingClient.kt](android/app/src/main/java/com/trtcdemo/SignalingClient.kt)）

**Web 实现**：`chat.createCustomMessage()` + `chat.sendMessage()`（[web/app.js](web/app.js)）

---

## 快速开始

### 前置条件

- **腾讯云账号**：在 [TRTC 控制台](https://console.trtc.io) 创建应用，获取 `SDKAppID` 和 `SecretKey`
  - TRTC 与 Chat IM 共用同一个 SDKAppID（控制台开通后自动关联）
- **Android Studio**（构建 Android 端）
- Android 设备或模拟器（API 21+，建议真机测试摄像头 / 麦克风）
- 支持摄像头的现代浏览器（Chrome / Edge / Firefox / Safari）

---

### 1. 配置 Android 端

编辑 [android/app/src/main/java/com/trtcdemo/Config.kt](android/app/src/main/java/com/trtcdemo/Config.kt)：

```kotlin
object Config {
    const val SDK_APP_ID: Int    = 0   // TODO: 替换为你的 SDKAppID
    const val SECRET_KEY: String = ""  // TODO: 替换为你的 SecretKey（仅测试用）
    const val DEFAULT_USER_ID: String = "android_user_001"
}
```

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

App 将自动：本地生成 UserSig → 登录 Chat SDK → 进入 TRTC 房间 → 发送 C2C invite 邀请

---

### 2. 配置 Web 端

编辑 [web/app.js](web/app.js) 顶部的 `CONFIG`，填入与 Android 端相同的凭证：

```js
const CONFIG = {
  SDK_APP_ID: 0,   // TODO: 替换为你的 SDKAppID
  SECRET_KEY: '',  // TODO: 替换为你的 SecretKey（仅测试用）
};
```

**打开页面（无需服务器）：**

```bash
# 方式一：直接双击 web/index.html（部分浏览器可能限制本地文件访问摄像头，推荐方式二）
# 方式二：用任意本地 HTTP 服务器托管
cd web
npx serve .
# 或
python3 -m http.server 8080
```

**使用方式：**
1. 输入**用户 ID**（需与 Android 填写的「对方用户 ID」一致，例如 `web_001`）
2. 点击**登录 & 等待来电**（Web 端本地生成 UserSig 并登录 Chat SDK）
3. Android 发起通话后，Web 端显示来电界面
4. 点击**接听**开始视频通话

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

## 目录结构

```
OKTEST-Gemini/
├── android/                              # Android 原生应用
│   └── app/src/main/java/com/trtcdemo/
│       ├── Config.kt                     # SDKAppID / SecretKey 配置入口
│       ├── MainActivity.kt               # 登录界面（输入 userId / toUserId）
│       ├── CallActivity.kt               # 通话界面主控制器
│       ├── TRTCManager.kt                # TRTC SDK 封装
│       ├── SignalingClient.kt            # Chat IM SDK 信令（C2C 自定义消息）
│       ├── UserSigHelper.kt              # UserSig 本地生成工具
│       └── ScreenShareService.kt         # 屏幕共享前台服务
├── server/                               # 可选：Node.js 静态文件服务器
│   ├── index.js                          # 仅用于托管 web/ 静态文件
│   └── package.json
└── web/                                  # Web 客户端
    ├── index.html                        # 页面结构
    ├── app.js                            # 核心逻辑（Chat SDK 信令 + TRTC）
    └── style.css                         # 暗色主题样式
```

---

## 常见问题

**Q：Web 端没有收到来电**
- 确认 Web 端登录的用户 ID 与 Android 填写的「对方用户 ID」完全一致（区分大小写）
- 查看浏览器控制台，确认 Chat SDK 登录成功（无报错）
- 查看 Android Logcat，确认 C2C 消息发送成功（`→ sent to xxx: invite`）

**Q：Chat SDK 登录失败**
- 确认 `SDK_APP_ID` 和 `SECRET_KEY` 填写正确且匹配
- 错误码 `-101001` 通常表示同一账号在其他设备已登录被踢下线，重启即可

**Q：TRTC 进入房间失败（错误码为负数）**
- 确认 `SDK_APP_ID` 填写正确
- 确认 TRTC 控制台该应用套餐仍在有效期内

**Q：Web 端 UserSig 生成报错**
- 确认 `CONFIG.SECRET_KEY` 已填写（非空字符串）
- 部分浏览器在 `file://` 协议下限制 Web Crypto API，请改用本地 HTTP 服务器打开页面

**Q：屏幕共享后 Web 端显示黑屏**
- 等待 1–2 秒，SUB 流需要短暂初始化时间
- 查看浏览器控制台是否有 `SUB render ERR` 日志

**Q：Android 屏幕共享权限弹窗未出现**
- 进入系统设置，手动授予该 App「显示在其他应用上方」（悬浮窗）权限

---

## 注意事项

### 安全（重要）

`SECRET_KEY` 当前嵌入客户端代码，**仅适用于开发 / 测试阶段**。生产环境中密钥暴露会导致严重安全风险，请务必改为服务端生成 UserSig：

```
客户端  ---(userId)--->  你的服务器  ---(签名)--->  返回 UserSig
```

Android 端改为 HTTP 请求获取；Web 端同理，不再需要 `SECRET_KEY`。

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
- [UserSig 生成说明](https://cloud.tencent.com/document/product/647/17275)
