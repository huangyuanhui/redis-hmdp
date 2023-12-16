package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 检验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            Result.fail("手机格式错误！");
        }
        // 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 保存验证码到Session
        session.setAttribute("code", code);
        // 发送验证码
        log.error("调用短信服务发送短信验证码，验证码：{}", code);
        /**
         * 这里返回成功：
         * 这里不需要返回登录凭证，因为我们是基于Session登录。
         * Session的原理就是Cookie，每一个Session都会有一个唯一的SessionID，
         * 在你访问Tomcat的时候，这个SessionID就已经自动地写到你的Cookie当中，
         * 那么你以后请求就都会这个这个SessionID，带着SessionID过来我们就能找
         * 到Session，找到Session就能找到登录用户
         */
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 校验手机号，为什么要校验，因为你发短信的时候填的是正确的手机，登录的时候可能修改了
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机格式错误！");
        }
        // 校验验证码
        String cacheCode = (String) session.getAttribute("code");
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误！");
        }
        // 根据手机查询用户
        User user = query().eq("phone", phone).one();
        // 校验用户是否存在
        if (user == null) {
            // 用户不存在则注册，创建用户并保存到数据库
            user = createUserByPhone(phone);
        }
        // 保存登录用户信息到Session中
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        // 返回成功
        return Result.ok();
    }

    @Override
    public Result sendCode(String phone) {
        // 检验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            Result.fail("手机格式错误！");
        }
        // 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 保存验证码到Redis，并设置验证码有效期：set key value ex times
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 发送验证码
        log.error("调用短信服务发送短信验证码，验证码：{}", code);
        return null;
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        // 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机格式错误！");
        }
        // 校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !cacheCode.equals(loginForm.getCode())) {
            return Result.fail("验证码错误！");
        }
        // 根据手机查询用户
        User user = query().eq("phone", phone).one();
        // 校验用户是否存在
        if (user == null) {
            // 用户不存在则注册，创建用户并保存到数据库
            user = createUserByPhone(phone);
        }
        // 生产随token
        String token = UUID.randomUUID().toString(true);
        String key = LOGIN_TOKEN_KEY + token;
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        /**
         * 我们使用的RedisTemplate是StringRedisTemplate，StringRedisTemplate要求
         * 所有的key和value都是String类型，如下：
         * public class StringRedisTemplate extends RedisTemplate<String, String>
         */
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 保存用户到Redis，并设置用户的登录有效期：模仿session的有效期30分钟
        stringRedisTemplate.opsForHash().putAll(key, userMap);
        stringRedisTemplate.expire(key, LOGIN_TOKEN_TTL, TimeUnit.MINUTES);
        // 返回token
        return Result.ok(token);
    }

    /**
     * 用户注册：创建用户并保存
     *
     * @param phone
     * @return
     */
    private User createUserByPhone(String phone) {
        // 创建用户
        User user = new User();
        user.setPhone(phone);
        String nickName = USER_NICK_NAME_PREFIX + RandomUtil.randomString(10);
        user.setNickName(nickName);
        // 保存用户
        save(user);
        return user;
    }
}
