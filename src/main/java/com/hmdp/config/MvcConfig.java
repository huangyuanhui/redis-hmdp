package com.hmdp.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 添加刷新Token有效期拦截器，放在前面的拦截器先执行
        registry.addInterceptor(new RefreshTokenIntercepptor(stringRedisTemplate));
        // 添加登录校验拦截器
        registry.addInterceptor(new LoginIntercepptor())
                // 排除不需要拦截的请求路径，即不登陆也可以访问的资源
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/blog/hot",
                        "/shop/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/voucher/**"
                );
        // 复杂写法：添加刷新Token有效期拦截器，设置拦截器先执行
        /*
        registry
                .addInterceptor(new RefreshTokenIntercepptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0);
        */
    }
}
