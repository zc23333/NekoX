package tw.nekomimi.nekogram.parts

import cn.hutool.http.HttpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import tw.nekomimi.nekogram.utils.ProxyUtil.parseProxies
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

fun loadProxies(urls: List<String>, exceptions: MutableMap<String, Exception>): List<String> {

    return runBlocking {

        suspendCoroutine {

            val ret = AtomicBoolean()
            val cl = AtomicInteger(urls.size)
            var defer: List<String>? = null

            for (url in urls) {
                launch(Dispatchers.IO) {
                    try {
                        val proxies = parseProxies(HttpUtil.get(url))
                        if (url.contains("https://gitee.com/") && cl.decrementAndGet() > 0) {
                            defer = proxies
                        } else {
                            if (ret.getAndSet(true)) return@launch
                            it.resume(proxies)
                        }
                    } catch (e: Exception) {
                        exceptions[url] = e
                        if (cl.decrementAndGet() == 0) {
                            if (defer != null) {
                                it.resume(defer!!)
                            } else {
                                it.resumeWithException(e)
                            }
                        }
                    }

                }
            }

        }

    }

}