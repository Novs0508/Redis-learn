package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheClient cacheClient;

    private static final String NULL_MARKER = "null";
    /*private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);*/

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, Shop.class, id, CACHE_NULL_TTL, TimeUnit.MINUTES, this::getById);
        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);
        //逻辑过期解决缓存击穿(需先调用saveShop2Redis预热，且不能与Hash结构缓存共用同一key)
        //Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, Shop.class, id, CACHE_SHOP_TTL, TimeUnit.MINUTES, this::getById);
        //Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }
    /*//逻辑过期解决缓存击穿
    //注意：该方案用String结构存JSON，与queryWithMutex的Hash结构共用cache:shop:{id}
    //切换方案测试前必须先删除该key，并调用saveShop2Redis预热，否则会报WRONGTYPE或缓存未命中
    public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.查询缓存(JSON字符串)，查不到说明没有预热，直接返回空
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        //2.反序列化，data字段会被解析为JSONObject
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        //3.判断缓存是否过期，未过期直接返回
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return shop;
        }
        //4.已过期，尝试获取互斥锁重建缓存
        String lockKey = LOCK_SHOP_KEY + id;
        if (!tryLock(lockKey)) {
            //获取锁失败，说明其他线程正在重建，直接返回旧数据，不等待
            return shop;
        }
        //5.获取锁成功，再次检查缓存(可能已被其他线程重建)
        try {
            String latest = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(latest)) {
                RedisData latestData = JSONUtil.toBean(latest, RedisData.class);
                if (latestData.getExpireTime().isAfter(LocalDateTime.now())) {
                    //已被其他线程重建，先释放锁再返回新数据
                    unlock(lockKey);
                    return JSONUtil.toBean((JSONObject) latestData.getData(), Shop.class);
                }
            }
        } catch (Exception e) {
            //检查过程出错，先释放锁再抛出，避免锁泄漏
            unlock(lockKey);
            throw e;
        }
        //6.缓存仍过期，开启一个新线程(使用线程池)异步重建，由异步任务负责释放锁
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                saveShop2Redis(id, CACHE_SHOP_TTL);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                //释放锁
                unlock(lockKey);
            }
        });
        return shop;
    }*/

    //预热：写入带逻辑过期时间的缓存
    public void saveShop2Redis(Long id, Long expireSeconds) {
        Shop shop = getById(id);
        RedisData redisData = RedisData.builder()
                .expireTime(LocalDateTime.now().plusSeconds(expireSeconds))
                .data(shop)
                .build();
        //逻辑过期：不设置物理TTL，保证过期后key仍在、能返回旧数据
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    //互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String lockKey = LOCK_SHOP_KEY + id;
        //从Redis查询商铺缓存
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
        //如果存在就直接返回
        if (!entries.isEmpty()) {
            //判断是否为空值
            return entries.containsKey(NULL_MARKER)
                    ? null
                    : BeanUtil.mapToBean(entries, Shop.class, false);
        }
        //如果不存在，开始缓存重建
        //尝试获取互斥锁
        try {
            if (!tryLock(lockKey)) {
                //获取锁失败，说明其他线程正在重建缓存，休眠后重试
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return queryWithMutex(id);
            }
            //获取锁成功，再次检查缓存(可能已被其他线程重建)，然后查库重建
            entries = stringRedisTemplate.opsForHash().entries(key);
            if (!entries.isEmpty()) {
                return entries.containsKey(NULL_MARKER)
                        ? null
                        : BeanUtil.mapToBean(entries, Shop.class, false);
            }
            //查询数据库
            Shop shop = getById(id);
            if (shop == null) {
                //不存在就写入空值缓存,设置短过期时间
                stringRedisTemplate.opsForHash().put(key, NULL_MARKER, "");
                stringRedisTemplate.expire(key, CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //存在就写入缓存,设置缓存时间
            stringRedisTemplate.opsForHash().putAll(key, BeanUtil.beanToMap(shop, new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            .setFieldValueEditor((fieldName, fieldValue) ->
                                    fieldValue == null ? null : fieldValue.toString())));
            stringRedisTemplate.expire(key, CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return shop;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        finally {
            //释放互斥锁
            unlock(lockKey);
        }
    }

    /*//缓存穿透
    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //从Redis查询商铺缓存
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
        //如果存在就直接返回
        if (!entries.isEmpty()) {
            //判断是否为空值
            if (entries.containsKey(NULL_MARKER)) {
                return null;
            }
            Shop shop = BeanUtil.mapToBean(entries, Shop.class, false);
            return shop;
        }
        //如果不存在查询数据库
        Shop shop = getById(id);
        //判断商铺是否存在
        if (shop == null) {
            //不存在就返回错误,并且把空值写入Redis缓存,设置缓存时间
            stringRedisTemplate.opsForHash().put(key, NULL_MARKER, "");
            stringRedisTemplate.expire(key, CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //存在就直接返回，并且把数据写入Redis缓存,设置缓存时间
        stringRedisTemplate.opsForHash().putAll(key,
                BeanUtil.beanToMap(
                        shop,
                        new HashMap<>(),
                        CopyOptions.create()
                                .setIgnoreNullValue(true)
                                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? null : fieldValue.toString())));
        stringRedisTemplate.expire(key, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }*/

    //获取互斥锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放互斥锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    //更新商铺信息
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result update(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺id不能为空");
        }
        //更新数据库
        updateById(shop);
        //更新Redis缓存,直接删除商铺对应的缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
