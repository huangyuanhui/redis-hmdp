package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {

    /**
     * Redission配置
     *
     * @return
     */
    @Bean
    public RedissonClient redissonClient() {
        // 配置
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://120.77.168.189:6379")
                .setPassword("123456");
        // 创建RedissonClient对象
        return Redisson.create(config);
    }
}
