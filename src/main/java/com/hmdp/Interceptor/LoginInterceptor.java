package com.hmdp.Interceptor;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

@Component
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取session
        HttpSession session = request.getSession();

        //获取用户信息
        UserDTO userDTO = (UserDTO) session.getAttribute("userDTO");
        //判断用户是否存在
        if (userDTO == null) {
            //如果用户不存在则返回未登录结果
            response.setStatus(401);
            return false;
        }
        //存在，保存用户信息
        UserHolder.saveUser(userDTO);
        //放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户信息
        UserHolder.removeUser();
    }
}
