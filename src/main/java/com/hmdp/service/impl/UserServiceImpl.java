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
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

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
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 发送验证码
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号格式
        if(!RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式无效");
        }
        //使用hutool工具包生成验证码
        String code = RandomUtil.randomString(6);
        //保存验证码到Redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code);
        //设置验证码有效期
        stringRedisTemplate.expire(LOGIN_CODE_KEY + phone, LOGIN_CODE_TTL, TimeUnit.MINUTES);
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
        //从Redis获取验证码
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
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
        BeanUtil.copyProperties(user, userDTO);
        //保存到Redis当中
        //随机生成token，做为登陆令牌
        String token = UUID.randomUUID().toString(true);
        //将User对象转换为Hash存储,并且将id转为字符串类型
        // StringRedisTemplate只能存String，所以用CopyOptions把字段值统一转换为String（包括Long类型的id）
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? null : fieldValue.toString()));
        //以Hash结构保存到Redis
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        //设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.SECONDS);
        //返回结果
        return Result.ok(token);
    }

    // 创建用户
    private User createUser(String phone) {
        User user = new User();
        user.setPhone(phone);
        //设置昵称
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
