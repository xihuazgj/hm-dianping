package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RefreshTokenInterceptor implements HandlerInterceptor {
    private final StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        //1.获取session
//        HttpSession session = request.getSession();
        //1.获取请求头中的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)){
            return true;
        }
        //2.基于token获取redis中的用户
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> usermap = stringRedisTemplate.opsForHash()
                .entries(key);
        //2.获取session中的用户
//        Object user = session.getAttribute("user");
//        System.out.println("******************************"+ user);
        //3.判断用户是否存在
        if (usermap.isEmpty()) {
            return true;
        }
        //5.将查询到的Hash数据转为userDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(usermap, new UserDTO(), false);
        //6.存在，保存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);
        //7.刷新token有效期
        stringRedisTemplate.expire(key,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        log.debug("用户token刷新：重置为：{}",RedisConstants.LOGIN_USER_TTL);
        //8.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        //移除用户
        UserHolder.removeUser();
    }
}
