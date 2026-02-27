package com.tronprotocol.app.llm.store

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Singleton HTTP client for the HuggingFace Hub API.
 *
 * Provides a configured Retrofit instance with timeouts, logging,
 * and optional authentication via Bearer token.
 */
object HuggingFaceClient {

    private const val BASE_URL = "https://huggingface.co/"

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
            .build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val api: HuggingFaceApi by lazy {
        retrofit.create(HuggingFaceApi::class.java)
    }

    /** Format a token for the Authorization header. */
    fun bearerHeader(token: String?): String? =
        token?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
}
