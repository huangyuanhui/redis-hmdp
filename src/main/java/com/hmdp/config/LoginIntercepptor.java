package com.hmdp.config;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 登录拦截器
 */
public class LoginIntercepptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取Session保存的登录用户
        Object user = request.getSession().getAttribute("user");
        // 判断用户是否存在（存在即登录，不存在则没登录）
        if (user == null) {
            // 不存在，则拦截请求，返回401未授权状态码
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        // 存在，将登录用户保存到ThreadLocal
        UserHolder.saveUser((UserDTO) user);
        // 放行请求
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }
}
