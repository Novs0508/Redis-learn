package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        //从Redis查询商铺缓存
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(CACHE_SHOP_KEY + id);
        //如果存在就直接返回
        if (!entries.isEmpty()) {
            Shop shop = BeanUtil.mapToBean(entries, Shop.class, false);
            return Result.ok(shop);
        }
        //如果不存在查询数据库
        Shop shop = getById(id);
        //判断商铺是否存在
        if (shop != null) {
            //存在就直接返回，并且把数据写入Redis缓存,设置缓存时间
            stringRedisTemplate.opsForHash().putAll(CACHE_SHOP_KEY + id,
                    BeanUtil.beanToMap(
                            shop,
                            new HashMap<>(),
                            CopyOptions.create()
                                    .setIgnoreNullValue(true)
                                    .setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? null : fieldValue.toString())));
            stringRedisTemplate.expire(CACHE_SHOP_KEY + id,CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return Result.ok(shop);
        }
        //不存在就返回错误
        return Result.fail("店铺不存在");
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
        return null;
    }
}
