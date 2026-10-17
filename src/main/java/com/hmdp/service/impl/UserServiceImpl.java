package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    // 发送验证码
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号格式
        if(!RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式无效");
        }
        //使用hutool工具包生成验证码
        String code = RandomUtil.randomString(6);
        //保存验证码到会话
        session.setAttribute("code", code);
        //发送验证码
        log.debug("发送验证码：{}", code);
        return Result.ok();
    }

    // 登录
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        //校验手机号和验证码是否正确
        if(!RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式无效");
        }
        String code = (String) session.getAttribute("code");
        if(code == null || !code.equals(loginForm.getCode())){
            return Result.fail("验证码无效");
        }
        //判断手机号是否已存在
        User user = query().eq("phone", loginForm.getPhone()).one();
        if(user == null){
            //如果不存在则创建新用户
            user = createUser(phone);
        }
        //把user转换为UserDTO
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        //保存到会话
        session.setAttribute("userDTO", userDTO);
        //返回结果
        return Result.ok();
    }

    private User createUser(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
