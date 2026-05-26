package com.dianping.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.dianping.dto.UserDTO;
import com.dianping.entity.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpSession;
import java.util.Map;

public class LoginInterceptor implements HandlerInterceptor {


    @Override
    public boolean preHandle(javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response, Object handler) throws Exception {
        if(UserHolder.getUser() == null)
        {
            response.setStatus(401);
            return false;
        }
        return true;

    }


}
