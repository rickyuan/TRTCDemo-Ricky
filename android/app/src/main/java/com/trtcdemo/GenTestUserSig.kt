package com.trtcdemo

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.zip.Deflater
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Local UserSig generator for testing/demo use.
 *
 * WARNING: Do NOT ship SecretKey in a production app.
 * In production, UserSig should be generated on your server.
 */
object GenTestUserSig {

    fun genTestUserSig(
        sdkAppId: Int,
        secretKey: String,
        userId: String,
        expire: Long = 604800L, // 7 days
    ): String {
        val currTime = System.currentTimeMillis() / 1000
        val sigDoc = JSONObject().apply {
            put("TLS.ver", "2.0")
            put("TLS.identifier", userId)
            put("TLS.sdkappid", sdkAppId)
            put("TLS.expire", expire)
            put("TLS.time", currTime)
        }

        val sig = hmacsha256(secretKey, userId, sdkAppId.toLong(), currTime, expire)
        sigDoc.put("TLS.sig", sig)

        val jsonStr = sigDoc.toString()
        val compressed = deflate(jsonStr.toByteArray(Charset.forName("UTF-8")))
        return base64UrlEncode(compressed)
    }

    private fun hmacsha256(
        secretKey: String,
        userId: String,
        sdkAppId: Long,
        currTime: Long,
        expire: Long,
    ): String {
        val contentToBeSigned =
            "TLS.identifier:$userId\n" +
            "TLS.sdkappid:$sdkAppId\n" +
            "TLS.time:$currTime\n" +
            "TLS.expire:$expire\n"

        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(secretKey.toByteArray(Charset.forName("UTF-8")), "HmacSHA256")
        mac.init(keySpec)
        val hash = mac.doFinal(contentToBeSigned.toByteArray(Charset.forName("UTF-8")))
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val buf = ByteArray(2048)
        var totalLen = 0
        val output = mutableListOf<ByteArray>()
        while (!deflater.finished()) {
            val len = deflater.deflate(buf)
            val segment = buf.copyOf(len)
            output.add(segment)
            totalLen += len
        }
        deflater.end()
        val result = ByteArray(totalLen)
        var offset = 0
        for (seg in output) {
            System.arraycopy(seg, 0, result, offset, seg.size)
            offset += seg.size
        }
        return result
    }

    private fun base64UrlEncode(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.NO_WRAP)
            .replace('+', '*')
            .replace('/', '-')
            .replace('=', '_')
    }
}
