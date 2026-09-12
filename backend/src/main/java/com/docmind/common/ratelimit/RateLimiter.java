package com.docmind.common.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 基于 Redis INCR 的固定窗口限流（03 号文档 §5.4） */
@Component
public class RateLimiter {

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return true = 放行；false = 已达上限 */
    public boolean tryAcquire(String key, int limit, Duration window) {
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            return true; // Redis 异常时放行，不让限流故障打断主流程
        }
        if (count == 1) {
            redis.expire(key, window);
        }
        return count <= limit;
    }
}
