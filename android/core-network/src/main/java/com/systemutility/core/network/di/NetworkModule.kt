package com.systemutility.core.network.di

import com.systemutility.core.logging.SafeLogger
import com.systemutility.core.network.BuildConfig
import com.systemutility.core.network.api.SystemUtilityApi
import com.systemutility.core.network.interceptor.AuthHeaderInterceptor
import com.systemutility.core.network.interceptor.DynamicBaseUrlInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        dynamicBaseUrlInterceptor: DynamicBaseUrlInterceptor,
        authHeaderInterceptor: AuthHeaderInterceptor
    ): OkHttpClient {
        val logger = HttpLoggingInterceptor { message ->
            SafeLogger.i("HTTP", message)
        }.apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
            redactHeader("Authorization")
            redactHeader("X-Device-Id")
        }

        return OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .addInterceptor(dynamicBaseUrlInterceptor)
            .addInterceptor(authHeaderInterceptor)
            .addInterceptor(logger)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        json: Json
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.DEFAULT_BASE_URL.trimEnd('/') + "/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideSystemUtilityApi(retrofit: Retrofit): SystemUtilityApi =
        retrofit.create(SystemUtilityApi::class.java)

    /**
     * Isolated HTTPS-only OkHttpClient for public external requests (GitHub Releases API & APK assets).
     * Intentionally omits DynamicBaseUrlInterceptor and AuthHeaderInterceptor so internal tokens are never sent externally.
     */
    @Provides
    @Singleton
    @Named("external_downloads")
    fun provideExternalDownloadsOkHttpClient(): OkHttpClient {
        val logger = HttpLoggingInterceptor { message ->
            SafeLogger.i("UpdateHTTP", message)
        }.apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }

        return OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logger)
            .build()
    }
}
