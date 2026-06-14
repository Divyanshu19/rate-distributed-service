package com.ratelimiter.distributed.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Registers Redis Lua scripts as Spring beans.
 *
 * <p>Using {@link DefaultRedisScript} gives us two important benefits over
 * passing raw script strings:
 * <ol>
 *   <li><b>SHA caching:</b> Spring hashes the script on first load and uses
 *       {@code EVALSHA} on subsequent calls — Redis only compiles the script
 *       once per server lifetime, not on every request.</li>
 *   <li><b>Fail-safe retry:</b> If Redis is restarted and loses the cached
 *       SHA, Spring automatically falls back to {@code EVAL} and re-caches.</li>
 * </ol>
 */
@Configuration
public class ScriptConfig {

    /**
     * Sliding Window Lua script bean.
     *
     * <p>Loaded from {@code classpath:scripts/sliding_window.lua}.
     * The script returns a {@code Long}:
     * <ul>
     *   <li>{@code >= 0} → allowed; value is remaining requests in window</li>
     *   <li>{@code -1}   → rejected; window is full</li>
     * </ul>
     *
     * @return a {@link DefaultRedisScript} ready for {@code RedisTemplate.execute()}
     */
    @Bean
    public DefaultRedisScript<Long> slidingWindowScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/sliding_window.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
