package com.ratelimiter.distributed.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis infrastructure configuration.
 *
 * <p>Uses Lettuce as the underlying client (non-blocking, netty-based).
 * Connection details are fully externalised to {@code application.yaml}
 * via {@code spring.data.redis.*} — no hardcoded host/port here.
 *
 * <p>Two beans are registered:
 * <ul>
 *   <li>{@link RedisConnectionFactory} — managed by Spring Boot auto-config,
 *       but explicitly declared here for clarity and future Sentinel/Cluster
 *       override points.</li>
 *   <li>{@link RedisTemplate} — typed {@code <String, String>} for Day 1.
 *       Serializers are set explicitly to avoid the default JDK serializer,
 *       which produces unreadable binary keys in Redis.</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    /**
     * Provides the primary {@link RedisTemplate} used by all rate-limiter
     * components to read/write Redis.
     *
     * <p>Both key and value serializers are set to {@link StringRedisSerializer}
     * so keys remain human-readable in {@code redis-cli} — critical for
     * debugging production issues.
     *
     * @param connectionFactory auto-configured Lettuce factory from
     *                          {@code spring.data.redis.*} properties
     * @return configured {@link RedisTemplate}
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Human-readable keys & values in Redis — never use JdkSerializationRedisSerializer
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * Explicit Lettuce connection factory bean.
     *
     * <p>Spring Boot auto-configures this via {@code RedisAutoConfiguration},
     * but declaring it explicitly here gives us a clean override point for
     * Day 7+ when we add Redis Sentinel or Cluster topology.
     *
     * @return {@link LettuceConnectionFactory} wired from application config
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        // Host/port resolved from spring.data.redis.host / spring.data.redis.port
        return new LettuceConnectionFactory();
    }
}
