package tw.nekomimi.nekogram.transtale.source

import cn.hutool.http.HttpUtil
import org.json.JSONObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import tw.nekomimi.nekogram.transtale.Translator
import tw.nekomimi.nekogram.utils.applyUserAgent

object LingoTranslator : Translator {

    override suspend fun doTranslate(from: String, to: String, query: String): String {

        if (to !in listOf("zh", "en", "es", "fr", "ja", "ru")) {

            error(LocaleController.getString("TranslateApiUnsupported", R.string.TranslateApiUnsupported))

        }

        val response = HttpUtil.createPost("https://api.interpreter.caiyunai.com/v1/translator")
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Authorization", "token 9sdftiq37bnv410eon2l") // 白嫖
                .applyUserAgent()
                .form("source", query)
                .form("trans_type", "${from}2$to")
                .form("request_id", System.currentTimeMillis().toString())
                .form("detect", true)
                .execute()

        if (response.status != 200) {

            error("HTTP ${response.status} : ${response.body()}")

        }

        return JSONObject(response.body()).getString("target")

    }

}