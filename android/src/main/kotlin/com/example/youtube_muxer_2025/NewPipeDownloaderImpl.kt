package com.example.youtube_muxer_2025

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.util.concurrent.TimeUnit

class NewPipeDownloaderImpl private constructor() : Downloader() {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        val instance: NewPipeDownloaderImpl by lazy { NewPipeDownloaderImpl() }
    }

    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder = okhttp3.Request.Builder()
            .method(
                httpMethod,
                if (dataToSend != null) dataToSend.toRequestBody() else null
            )
            .url(url)

        for ((headerName, headerValues) in headers) {
            if (headerValues.size > 1) {
                requestBuilder.removeHeader(headerName)
                for (headerValue in headerValues) {
                    requestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValues.size == 1) {
                requestBuilder.header(headerName, headerValues[0])
            }
        }

        val response = client.newCall(requestBuilder.build()).execute()

        val responseBodyToReturn = response.body?.string()
        val latestUrl = response.request.url.toString()
        val responseCode = response.code
        val responseMessage = response.message
        val responseHeaders = response.headers.toMultimap()

        return Response(
            responseCode,
            responseMessage,
            responseHeaders,
            responseBodyToReturn,
            latestUrl
        )
    }
}
