package dev.triplex.di

import android.telephony.PhoneNumberUtils
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.triplex.data.call.CallSessionRepositoryImpl
import dev.triplex.data.call.CallerIdentityResolver
import dev.triplex.data.call.DirectoryCallerIdentityResolver
import dev.triplex.data.call.PhoneNumberFormatter
import dev.triplex.data.call.SipCallSource
import dev.triplex.data.call.TelecomCallSource
import dev.triplex.domain.call.CallSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.Locale
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CallModule {

    @Binds
    @Singleton
    abstract fun bindCallerIdentityResolver(
        resolver: DirectoryCallerIdentityResolver,
    ): CallerIdentityResolver

    companion object {
        @Provides
        @Singleton
        fun providePhoneNumberFormatter(): PhoneNumberFormatter = PhoneNumberFormatter { number ->
            val region = Locale.getDefault().country.ifBlank { "US" }
            PhoneNumberUtils.formatNumber(number, region) ?: number
        }

        /**
         * Built by hand so the repository keeps a constructor a JVM test can
         * call. The scope outlives every surface: call state must survive the
         * activity that happens to be showing it.
         */
        @Provides
        @Singleton
        fun provideCallSessionRepository(
            telecomSource: TelecomCallSource,
            sipSource: SipCallSource,
            identityResolver: CallerIdentityResolver,
            phoneNumberFormatter: PhoneNumberFormatter,
        ): CallSessionRepository = CallSessionRepositoryImpl(
            telecomSource = telecomSource,
            sipSource = sipSource,
            identityResolver = identityResolver,
            phoneNumberFormatter = phoneNumberFormatter,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
}
