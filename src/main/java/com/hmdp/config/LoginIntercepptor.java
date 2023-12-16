package com.hmdp.config;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.LOGIN_TOKEN_KEY;
import static com.hmdp.utils.SystemConstants.LOGIN_TOKEN_TTL;

/**
 * 登录拦截器
 */
public class LoginIntercepptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1：判断是否需要拦截请求：ThreadLocal中是否有用户
        if (UserHolder.getUser() == null) {
            // 没有，说明未登录，则需要拦截请求，返回401未授权状态码
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        // 有用户，放行请求
        return true;
    }
}
