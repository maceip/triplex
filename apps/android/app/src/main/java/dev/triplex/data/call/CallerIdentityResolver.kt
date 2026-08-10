package dev.triplex.data.call

import dev.triplex.dialer.DialerContact
import dev.triplex.dialer.DialerDirectoryRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a raw call handle into a name the user recognizes.
 *
 * Behind an interface so the repository stays a JVM-testable object: the real
 * implementation reads the contacts provider, the test provides a list.
 */
interface CallerIdentityResolver {
    val contacts: StateFlow<List<DialerContact>>

    fun refresh()
}

/**
 * Reads the one process-wide directory rather than building a second one.
 *
 * Before Phase 2 this constructed its own [DialerDirectoryRepository] from the
 * application context while `DialerActivity` built another, so the contacts
 * provider was queried twice and the two copies could disagree. Both now come
 * from the Hilt singleton in `DirectoryModule`.
 */
@Singleton
class DirectoryCallerIdentityResolver @Inject constructor(
    private val directory: DialerDirectoryRepository,
) : CallerIdentityResolver {

    override val contacts: StateFlow<List<DialerContact>> = directory.contacts

    override fun refresh() = directory.refresh()
}

/**
 * Formats a dial string for display.
 *
 * Injected because `PhoneNumberUtils` is an Android platform class and the
 * repository must remain constructible off-device.
 */
fun interface PhoneNumberFormatter {
    fun format(number: String): String
}
