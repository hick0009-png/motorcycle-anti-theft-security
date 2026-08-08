package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.security.TotpAuthenticator.VerificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCommandTest {
    @Test
    fun disarmWithoutCodeIsParsedAsAnAuthorizationFailure() {
        assertEquals(RemoteCommand.Disarm(null), RemoteCommand.parse("/disarm"))
    }

    @Test
    fun onlySuccessfulTotpPermitsDisarm() {
        assertFalse(RemoteCommand.isDisarmAuthorized(VerificationResult.NO_SEED_CONFIGURED))
        assertFalse(RemoteCommand.isDisarmAuthorized(VerificationResult.INVALID_CODE))
        assertTrue(RemoteCommand.isDisarmAuthorized(VerificationResult.SUCCESS))
    }
}
