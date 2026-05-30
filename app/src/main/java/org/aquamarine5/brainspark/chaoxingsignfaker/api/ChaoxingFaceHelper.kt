/*
 * Copyright (c) 2026, @aquamarine5 (@海蓝色的咕咕鸽). All Rights Reserved.
 * Author: aquamarine5@163.com (Github: https://github.com/aquamarine5) and Brainspark (previously RenegadeCreation)
 * Repository: https://github.com/aquamarine5/ChaoxingSignFaker
 */

package org.aquamarine5.brainspark.chaoxingsignfaker.api

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.alibaba.fastjson2.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.aquamarine5.brainspark.chaoxingsignfaker.ChaoxingParseDataException
import org.aquamarine5.brainspark.chaoxingsignfaker.chaoxingDataStore
import org.aquamarine5.brainspark.chaoxingsignfaker.checkResponseThrowException
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.TreeMap

object ChaoxingFaceHelper {
    private val URL_CHECK_FACE_RESULT =
        "https://mobilelearn.chaoxing.com/pptSign/check-face-result?DB_STRATEGY=PRIMARY_KEY&STRATEGY_PARA=activeId".toHttpUrl()
    private const val CLIENT_ID_PUBLIC_KEY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC79d8Ot0hCbxxSISC6x8SCwTBspFSzlLKHJUYqoFNu1TSRaw4hEYkOnvEaL1VyoxV6HXcDrzwYvaFZaZaPQPFnfCHZy5dQwxcmifgSHqS+oKXw40Ys4cVIqnU5d90S7EWSRdBglX489jlqVaNcQSkDx2TYmC+DbAq9FV/BU09ISQIDAQAB"

    suspend fun checkFaceResultAndGetEnc(
        client: ChaoxingHttpClient,
        objectId: String,
        activeId: Long
    ): String =
        withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder().url(
                    URL_CHECK_FACE_RESULT.newBuilder()
                        .addQueryParameter("activeId", activeId.toString())
                        .addQueryParameter(
                            "faceResult",
                            buildFaceResult(client, objectId).toJSONString()
                        )
                        .build()
                ).get().build()
            ).execute().use { response ->
                response.checkResponseThrowException()
                val jsonObject = JSONObject.parseObject(response.body.string())
                return@withContext jsonObject.getString("enc") ?: throw ChaoxingParseDataException(
                    "获取faceEnc失败",
                    data = jsonObject.toJSONString()
                )
            }
        }

    private fun buildFaceResult(client: ChaoxingHttpClient, objectId: String): JSONObject {
        val fields = mapOf(
            "currentFaceId" to objectId,
            "LiveDetectionStatus" to "1",
            "collectStatus" to "1"
        )
        val cxtime = System.currentTimeMillis().toString()
        return JSONObject()
            .fluentPut("currentFaceId", objectId)
            .fluentPut("LiveDetectionStatus", 1)
            .fluentPut("collectStatus", 1)
            .apply {
                client.userEntity.clientId?.let { clientId ->
                    addSignToken(clientId, fields, cxtime)
                }
                fluentPut("cxtime", cxtime)
            }
    }

    private fun JSONObject.addSignToken(clientId: String, fields: Map<String, String>, cxtime: String) {
        val deviceInfo = decryptClientId(clientId) ?: return
        val cxcid = deviceInfo.getString("cid") ?: return
        val sc = deviceInfo.getString("sc") ?: return
        val signedFields = TreeMap<String, String>().apply {
            putAll(fields)
            put("cxtime", cxtime)
            put("cxcid", cxcid)
        }
        val raw = buildString {
            signedFields.forEach { (key, value) ->
                append(key)
                append(value)
            }
            append(sc)
        }
        fluentPut("signToken", md5(raw))
        fluentPut("cxcid", cxcid)
    }

    private fun decryptClientId(clientId: String): JSONObject? =
        runCatching {
            val encrypted = Base64.decode(clientId, Base64.DEFAULT)
            val publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(
                    X509EncodedKeySpec(
                        Base64.decode(CLIENT_ID_PUBLIC_KEY, Base64.DEFAULT)
                    )
                ) as RSAPublicKey
            val blockSize = (publicKey.modulus.bitLength() + 7) / 8
            val output = ByteArrayOutputStream()
            for (offset in encrypted.indices step blockSize) {
                val block = BigInteger(
                    1,
                    encrypted.copyOfRange(offset, offset + blockSize)
                ).modPow(publicKey.publicExponent, publicKey.modulus)
                    .toByteArray()
                    .toFixedBlock(blockSize)
                require(block.size > 2 && block[0] == 0.toByte() && block[1] == 1.toByte())
                val separator = block.indexOf(0.toByte(), 2)
                require(separator > 2)
                output.write(block, separator + 1, block.size - separator - 1)
            }
            JSONObject.parseObject(output.toString(Charsets.UTF_8.name()))
        }.getOrNull()

    private fun ByteArray.toFixedBlock(size: Int): ByteArray =
        when {
            this.size == size -> this
            this.size == size + 1 && this[0] == 0.toByte() -> copyOfRange(1, this.size)
            this.size < size -> ByteArray(size - this.size) + this
            else -> error("Invalid RSA block size")
        }

    private fun ByteArray.indexOf(value: Byte, startIndex: Int): Int {
        for (index in startIndex until size) {
            if (this[index] == value) return index
        }
        return -1
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }


    suspend fun saveFaceImage(
        context: Context,
        objectId: String,
        phoneNumber: String? = null
    ) {

    }

    suspend fun saveFaceImage(
        client: ChaoxingHttpClient,
        context: Context,
        bitmap: Bitmap,
        phoneNumber: String? = null
    ) =
        withContext(Dispatchers.IO) {
            ChaoxingCloudDriveHelper.uploadImage(
                client,
                bitmap
            ).let { objectId ->
                context.chaoxingDataStore.updateData {
                    it.toBuilder().apply {
                        if (phoneNumber == null) {
                            setLoginSession(
                                loginSession.toBuilder().setFaceImageObjectId(objectId).build()
                            )
                        } else {
                            val index =
                                otherUsersList.indexOfFirst { user -> user.phoneNumber == phoneNumber }
                            if (index != -1) {
                                setOtherUsers(
                                    index,
                                    getOtherUsers(index).toBuilder().setFaceImageObjectId(objectId)
                                        .build()
                                )
                            }
                        }
                    }.build()
                }
            }
        }
}
