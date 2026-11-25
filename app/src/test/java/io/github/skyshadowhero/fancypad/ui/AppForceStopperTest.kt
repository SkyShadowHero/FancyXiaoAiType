package io.github.skyshadowhero.fancypad.ui

import io.github.skyshadowhero.fancypad.ui.utils.AppForceStopper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppForceStopperTest {

    @Test
    fun acceptsNormalAndroidPackageNames() {
        assertTrue(AppForceStopper.isForceStoppablePackage("com.xiaomi.type"))
        assertTrue(AppForceStopper.isForceStoppablePackage("com.android.systemui"))
    }

    @Test
    fun rejectsSystemServerAndShellInput() {
        assertFalse(AppForceStopper.isForceStoppablePackage("system"))
        assertFalse(AppForceStopper.isForceStoppablePackage("system_server"))
        assertFalse(AppForceStopper.isForceStoppablePackage("com.example.app;reboot"))
        assertFalse(AppForceStopper.isForceStoppablePackage("com.example.app --user 0"))
    }
}
