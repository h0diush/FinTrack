package app.fintrack.domain

import app.fintrack.domain.logic.LockPolicy
import app.fintrack.domain.logic.PinHasher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {

    @Test
    fun pinHashVerifiesOnlyTheRightPin() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash("2580", salt)
        assertTrue(PinHasher.verify("2580", salt, hash))
        assertFalse(PinHasher.verify("2581", salt, hash))
        assertFalse(hash.contains("2580"))
    }

    @Test
    fun samePinWithDifferentSaltGivesDifferentHash() {
        assertNotEquals(PinHasher.hash("1234", PinHasher.newSalt()), PinHasher.hash("1234", PinHasher.newSalt()))
    }

    @Test
    fun pinFormat() {
        assertTrue(PinHasher.isValidPin("0007"))
        assertFalse(PinHasher.isValidPin("123"))
        assertFalse(PinHasher.isValidPin("12a4"))
        assertFalse(PinHasher.isValidPin("12345"))
    }

    @Test
    fun lockAfterTimeout() {
        assertFalse(LockPolicy.shouldLock(null, 10_000, 0))
        assertTrue(LockPolicy.shouldLock(1_000, 1_000, 0)) // «сразу»
        assertFalse(LockPolicy.shouldLock(0, 59_999, 60_000))
        assertTrue(LockPolicy.shouldLock(0, 60_000, 60_000))
    }

    @Test
    fun lockoutGrowsAndIsCapped() {
        assertEquals(0L, LockPolicy.lockoutMs(4))
        assertEquals(30_000L, LockPolicy.lockoutMs(5))
        assertEquals(60_000L, LockPolicy.lockoutMs(6))
        assertEquals(120_000L, LockPolicy.lockoutMs(7))
        assertEquals(15 * 60_000L, LockPolicy.lockoutMs(30))
    }
}
