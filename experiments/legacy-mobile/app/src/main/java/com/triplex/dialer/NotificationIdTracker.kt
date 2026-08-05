package com.triplex.dialer

/**
 * Tracks notification IDs for active calls.
 * Mirrors AndroidX reference app's NotificationIdTracker.
 */
object NotificationIdTracker {

    private val activeNotifications = mutableSetOf<Int>()

    fun registerNotificationId(notificationId: Int) {
        activeNotifications.add(notificationId)
    }

    fun removeNotificationId(notificationId: Int) {
        activeNotifications.remove(notificationId)
    }

    fun hasNotification(notificationId: Int): Boolean =
        notificationId in activeNotifications
}
