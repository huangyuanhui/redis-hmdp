package com.hmdp.config;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.LOGIN_TOKEN_KEY;
import static com.hmdp.utils.SystemConstants.LOGIN_TOKEN_TTL;

/**
 * 刷新Token拦截器
 */
public class RefreshTokenIntercepptor implements HandlerInterceptor {

    private StringRedisTemplate redisTemplate;

    public RefreshTokenIntercepptor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取请求头中的Token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            // token不存在，直接放行
            return true;
        }
        // 基于token从Redis中获取用户
        String key = LOGIN_TOKEN_KEY + token;
        Map<Object, Object> userMap = redisTemplate.opsForHash().entries(key);
        // 判断用户是否存在
        if (userMap.isEmpty()) {
            // 不存在，直接放行
            return true;
        }
        // 用户存在，将登录用户保存到ThreadLocal
        UserDTO user = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(user);
        // 登录续期，刷新token有效期
        redisTemplate.expire(key, LOGIN_TOKEN_TTL, TimeUnit.MINUTES);
        // 放行请求
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }
}
