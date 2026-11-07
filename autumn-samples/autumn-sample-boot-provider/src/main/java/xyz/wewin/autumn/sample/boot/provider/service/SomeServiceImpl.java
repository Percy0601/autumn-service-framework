package xyz.wewin.autumn.sample.boot.provider.service;


import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.apache.thrift.TException;
import org.springframework.stereotype.Service;

import xyz.wewin.autumn.api.annotation.Export;
import xyz.wewin.autumn.api.annotation.Reference;
import xyz.wewin.autumn.api.config.ProviderConfig;
import xyz.wewin.autumn.api.extension.AttachmentContext;
import xyz.wewin.autumn.sample.api.SomeService;
import xyz.wewin.autumn.sample.api.User;

import lombok.extern.slf4j.Slf4j;

/**
 * provider service, exported by autumn starter automatically via @Export
 *
 * @author: baoxin.zhao
 * @date: 2024/11/10
 */
@Slf4j
@Export
@Service
public class SomeServiceImpl implements SomeService.Iface {

    /**
     * watch consumer app name, used by multicast registry mutual discovery topology
     */
    @Reference(refer = "consumer")
    SomeService.Iface someService;

    @Override
    public String echo(String msg) throws TException {
        // 带上实例端口与隐式参数，便于多实例 / 负载均衡 / 隐式传参回归验证
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

        String result = "Hello, " + msg + " [node:" + ProviderConfig.getInstance().getPort()
                + "] [tenant:" + AttachmentContext.get("tenant") + "] [time:" + sdf.format(new Date()) + "]";
        return result;
    }

    @Override
    public int addUser(User user) throws TException {
        return 1;
    }

    @Override
    public List<User> findUserByIds(List<Integer> idList) throws TException {
        return List.of();
    }
}
