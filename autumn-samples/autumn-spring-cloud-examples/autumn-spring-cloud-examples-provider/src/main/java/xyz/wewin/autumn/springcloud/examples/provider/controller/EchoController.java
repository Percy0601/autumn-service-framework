package xyz.wewin.autumn.springcloud.examples.provider.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 纯 echo 端点，供 benchmarker 做 thrift vs http 协议对比压测使用
 * （与 autumn-sample 的 thrift SomeService.echo 业务逻辑对等）。
 */
@RestController
public class EchoController {

    @GetMapping("/echo")
    public String echo(@RequestParam(value = "msg", defaultValue = "hello") String msg) {
        return msg;
    }
}
