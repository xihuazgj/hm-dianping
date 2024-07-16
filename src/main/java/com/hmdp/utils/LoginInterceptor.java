package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        //1.判断是否需要拦截（Threadlocal中是否有用户）
        if (UserHolder.getUser() == null){
            //拦截，设置状态码
            response.setStatus(401);
            //拦截
            return false;
        }
        //有用户，放行
        return true;
    }
}
