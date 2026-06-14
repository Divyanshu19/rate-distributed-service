package com.ratelimiter.distributed;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test — verifies the Spring context loads successfully,
 * which means Redis connectivity and all bean wiring are correct.
 *
 * <p>This is intentionally minimal on Day 1.
 * Algorithm-level unit tests arrive on Day 2.
 */
@SpringBootTest
class DistributedApplicationTests {

    @Test
    void contextLoads() {
        // If this passes, the full application context starts cleanly:
        // - Redis connection factory is healthy
        // - RedisTemplate is wired correctly
        // - All @Configuration classes are valid
    }
}
