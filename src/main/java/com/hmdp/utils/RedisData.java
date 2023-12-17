package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 逻辑过期对象
 */
@Data
public class RedisData {
    // 逻辑过期时间
    private LocalDateTime expireTime;
    private Object data;
}
