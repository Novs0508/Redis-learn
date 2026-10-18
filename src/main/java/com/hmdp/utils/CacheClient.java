package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

@Slf4j
@Component
public class CacheClient {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //将任意Java对象序列化为JSON并存储在Redis中，对应key的过期时间为time
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }
    //将任意Java对象序列化为JSON并存储在Redis中，对应逻辑过期时间为time
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = RedisData.builder()
                .data(value)
                .expireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)))
                .build();
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    
    //根据对应的Key获取Redis中的缓存，并且反序列化为指定的类型，利用缓存空值的方式解决缓存穿透问题
    public <R,ID> R queryWithPassThrough(String keyPrefix, Class<R> type, ID id, Long time, TimeUnit unit, Function<ID,R> dbFallBack){
        String key = keyPrefix + id;
        //从Redis查询缓存(JSON字符串)
        String json = stringRedisTemplate.opsForValue().get(key);
        //命中且不是空值哨兵，反序列化后直接返回
        if (json != null && !json.isEmpty()) {
            return JSONUtil.toBean(json, type);
        }
        //命中的是空值哨兵(空字符串)，说明数据库中也不存在，直接返回null
        if (json != null) {
            return null;
        }
        //如果不存在查询数据库
        R r = dbFallBack.apply(id);
        //判断商铺是否存在
        if (r == null) {
            //不存在就返回错误,并且把空字符串写入Redis缓存,设置缓存时间,防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //存在就直接返回，并且把数据写入Redis缓存,设置缓存时间
        set(key, r, time, unit);
        return r;
    }
    
    //根据对应的Key获取Redis中的缓存，并且反序列化为指定的类型，利用逻辑过期解决缓存击穿问题
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, Class<R> type, ID id, Long time, TimeUnit unit, Function<ID,R> dbFallBack){
        String key = keyPrefix + id;
        //从Redis查询缓存(JSON字符串)
        String json = stringRedisTemplate.opsForValue().get(key);
        //未命中说明没有预热，直接返回null
        if (StrUtil.isBlank(json)) {
            return null;
        }
        //命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        //判断是否过期
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            //未过期，返回r
            return r;
        }
        //过期需要缓存重建
        //获取互斥锁
        String lockKey = "lock:" + key;
        boolean isLock = tryLock(lockKey);
        //判断是否获取锁成功
        if (!isLock) {
            //失败，则返回过期的商铺信息
            return r;
        }
        //成功，需要再次检查缓存是否过期，避免缓存击穿
        try {
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                redisData = JSONUtil.toBean(json, RedisData.class);
                if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                    //未过期，说明已被其他线程重建，先释放锁再返回
                    unlock(lockKey);
                    return JSONUtil.toBean((JSONObject) redisData.getData(), type);
                }
            }
        } catch (Exception e) {
            //检查过程出错，先释放锁再抛出，避免锁泄漏
            unlock(lockKey);
            throw e;
        }
        //仍过期，提交给线程池异步重建，不阻塞当前请求，由异步任务负责释放锁
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                //查询数据库并重建缓存
                R fresh = dbFallBack.apply(id);
                //数据库中不存在则不写缓存，避免产生永不过期的空数据
                if (fresh != null) {
                    //设置逻辑过期时间
                    setWithLogicalExpire(key, fresh, time, unit);
                }
            } catch (Exception e) {
                //线程池任务异常会被Future吞掉，必须记录日志
                log.error("缓存重建失败, key={}", key, e);
            } finally {
                //释放锁
                unlock(lockKey);
            }
        });
        //当前请求直接返回过期的旧数据
        return r;
    }
    
    //获取互斥锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放互斥锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


}
