package com.dianping.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.dianping.dto.Result;
import com.dianping.entity.ShopType;
import com.dianping.mapper.ShopTypeMapper;
import com.dianping.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result querylist() {
        // 查询缓存
        String shopTypeJson = stringRedisTemplate.opsForValue().get("cache:shoptype");
        // 判断缓存是否存在
        if(StrUtil.isNotBlank(shopTypeJson)){
            // 存在，直接返回
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopTypeList);
        }
        // 不存在，查询数据库
        List<ShopType> shopTypeList =list();
        // 判断商铺是否存在
        if(shopTypeList == null || shopTypeList.size() == 0){
            return Result.fail("商铺类型不存在");
        }
        // 写入缓存
        stringRedisTemplate.opsForValue().set("cache:shoptype", JSONUtil.toJsonPrettyStr(shopTypeList));
        return Result.ok(shopTypeList);
    }
}
