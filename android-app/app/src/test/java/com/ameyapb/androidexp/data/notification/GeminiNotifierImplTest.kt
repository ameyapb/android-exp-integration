package com.ameyapb.androidexp.data.notification

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.ameyapb.androidexp.ROBOLECTRIC_SDK_LEVEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK_LEVEL], application = Application::class)
class GeminiNotifierImplTest {

    private lateinit var notificationManager: NotificationManager
    private lateinit var notifier: GeminiNotifierImpl

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        notificationManager = context.getSystemService(NotificationManager::class.java)
        notifier = GeminiNotifierImpl(context)
    }

    @Test
    fun `notify posts a notification with the reply text when notifications are enabled`() {
        notifier.notify("the sky is blue")

        val postedNotifications = shadowOf(notificationManager).activeNotifications
        assertEquals(1, postedNotifications.size)
        val extras = postedNotifications.first().notification.extras
        assertEquals("the sky is blue", extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `notify does nothing when notifications are disabled`() {
        shadowOf(notificationManager).setNotificationsEnabled(false)

        notifier.notify("the sky is blue")

        assertTrue(shadowOf(notificationManager).activeNotifications.isEmpty())
    }
}
