package captainblood

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RateLimiterTest {

    @Test
    fun `разрешает запросы в пределах лимита`() {
        val limiter = RateLimiter(maxRequests = 3, windowMs = 10_000L)
        assertTrue(limiter.allow("1.2.3.4"))
        assertTrue(limiter.allow("1.2.3.4"))
        assertTrue(limiter.allow("1.2.3.4"))
    }

    @Test
    fun `отказывает после превышения лимита`() {
        val limiter = RateLimiter(maxRequests = 2, windowMs = 10_000L)
        assertTrue(limiter.allow("1.2.3.4"))
        assertTrue(limiter.allow("1.2.3.4"))
        assertFalse(limiter.allow("1.2.3.4"))
    }

    @Test
    fun `лимит считается отдельно по каждому IP`() {
        val limiter = RateLimiter(maxRequests = 1, windowMs = 10_000L)
        assertTrue(limiter.allow("1.1.1.1"))
        assertFalse(limiter.allow("1.1.1.1"))
        assertTrue(limiter.allow("2.2.2.2"))
    }

    @Test
    fun `после истечения окна лимит сбрасывается`() {
        val limiter = RateLimiter(maxRequests = 1, windowMs = 150L)
        assertTrue(limiter.allow("1.2.3.4"))
        assertFalse(limiter.allow("1.2.3.4"))
        Thread.sleep(250)
        assertTrue(limiter.allow("1.2.3.4"))
    }
}
