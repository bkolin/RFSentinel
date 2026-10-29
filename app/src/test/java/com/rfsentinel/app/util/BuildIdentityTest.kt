package com.rfsentinel.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildIdentityTest {

    @Test
    fun versionThenCommit() {
        assertEquals("2.31.0 · 83a431d", BuildIdentity.label("2.31.0", "83a431d"))
        assertEquals("2.31.0 · 83a431d+", BuildIdentity.label("2.31.0", "83a431d+"))
    }

    @Test
    fun plainVersionWithoutGit() {
        assertEquals("2.31.0", BuildIdentity.label("2.31.0", ""))
    }
}
