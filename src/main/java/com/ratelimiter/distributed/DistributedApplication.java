package com.ratelimiter.distributed;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Distributed Rate Limiter service.
 *
 * <p>All Spring components are discovered via {@code @SpringBootApplication}'s
 * component scan starting from this package ({@code com.ratelimiter.distributed}).
 * Sub-packages ({@code core}, {@code config}, {@code api}, {@code metrics})
 * are automatically picked up.
 */
@SpringBootApplication
public class DistributedApplication {

    public static void main(String[] args) {
        SpringApplication.run(DistributedApplication.class, args);
    }
}
