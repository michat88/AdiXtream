package com.lagradost.cloudstream3

import org.junit.Assert.*
import org.junit.Test

class TemporaryCaptureSessionTest {
    @Test fun defaultAndNewProcessSessionBlockCapture() {
        val first = TemporaryCaptureSession()
        assertFalse(first.allowed.value)
        assertTrue(first.requiresSecureWindow(true, true))
        first.setAllowed(true)
        assertFalse(TemporaryCaptureSession().allowed.value)
    }
    @Test fun onlySafeForegroundScreensCanBeCaptured() {
        val session = TemporaryCaptureSession()
        session.setAllowed(true)
        assertFalse(session.requiresSecureWindow(true, true))
        assertTrue(session.requiresSecureWindow(false, true))
        assertTrue(session.requiresSecureWindow(true, false))
        assertTrue(session.requiresSecureWindow(false, false))
    }
    @Test fun disablingImmediatelyRestoresProtectionEverywhere() {
        val session = TemporaryCaptureSession()
        session.setAllowed(true)
        session.setAllowed(false)
        assertTrue(session.requiresSecureWindow(true, true))
    }
    @Test fun finishingSessionDoesNotPersistTheOptIn() {
        val session = TemporaryCaptureSession()
        session.setAllowed(true)
        session.reset()
        assertFalse(session.allowed.value)
        assertTrue(session.requiresSecureWindow(true, true))
    }
}
