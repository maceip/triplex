package dev.triplex.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.triplex.nativebridge.runtime.NativeRuntime
import dev.triplex.telephony.sip.TelephonyController
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NativeModule {

    @Provides
    @Singleton
    fun provideNativeRuntime(): NativeRuntime {
        return NativeRuntime
    }
    
    @Provides
    @Singleton
    fun provideTelephonyController(
        @ApplicationContext context: Context,
        secureStorage: dev.triplex.data.local.SecureStorage,
        runtime: NativeRuntime
    ): TelephonyController {
        return TelephonyController(context, secureStorage, runtime)
    }
    
}
