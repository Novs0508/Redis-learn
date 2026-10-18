package com.hmdp.utils;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final long BEGIN_TIMESTAMP = 1577836800L;
    private static final long COUNT_BITS = 32;


    public long nextId(String keyPrefix, long id) {
        //计算32位时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp= nowSecond - BEGIN_TIMESTAMP;
        //生成序列号
        //获取当前日期，格式为yyyy:MM:dd
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //生成序列号
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        return timestamp << COUNT_BITS | count;

    }
}
