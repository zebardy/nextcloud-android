package com.nextcloud.client.mixins

import android.app.Activity
import android.content.ContentResolver
import com.nextcloud.client.account.UserAccountManager
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

class SessionMixinTest {

    @Mock
    private lateinit var activity: Activity

    @Mock
    private lateinit var delegate: SessionMixin.Delegate

    @Mock
    private lateinit var contentResolver: ContentResolver

    @Mock
    private lateinit var userAccountManager: UserAccountManager

    private lateinit var session: SessionMixin

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        session = SessionMixin(
            activity,
            delegate,
            contentResolver,
            userAccountManager
        )
    }

    @Test
    fun `foo bar`() {
        Assert.assertNotNull(session)
    }
}
