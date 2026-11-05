package com.microapp.autumn.api.extension;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microapp.autumn.api.config.ProviderConfig;

/**
 * 服务端隐式传参处理器：负责请求级 {@link AttachmentContext} 的隔离与清理。
 *
 * <p>field-0 的实际解码由 {@link AttachableBinaryProtocol#readFieldBegin()} 在生成代码读取参数时完成，
 * 本处理器不再偷看或重置底层 transport，因此对普通 TBinaryProtocol 客户端同样兼容。</p>
 */
public class AttachableProcessor implements TProcessor {

    private final Logger log = LoggerFactory.getLogger(AttachableProcessor.class);
    private final TProcessor realProcessor;

    public AttachableProcessor(TProcessor realProcessor) {
        this.realProcessor = realProcessor;
    }

    @Override
    public void process(TProtocol in, TProtocol out) throws TException {
        // provider.timeout 超时守卫会中断 worker 线程，被中断的请求不再进入业务处理器
        if (Thread.currentThread().isInterrupted()) {
            throw new TApplicationException(TApplicationException.INTERNAL_ERROR,
                    "autumn request timeout or cancelled");
        }
        long start = System.nanoTime();
        // 线程池复用：先清掉上一个请求残留的隐式参数
        AttachmentContext.clear();
        try {
            realProcessor.process(in, out);
        } finally {
            Map<String, String> implicit = AttachmentContext.snapshot();
            if (!implicit.isEmpty()) {
                log.debug("read attachment content:{}", implicit);
            }
            // provider.timeout 同时作为慢调用告警阈值，给控制面提供最小可观测性
            long costMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            Integer timeout = ProviderConfig.getInstance().getTimeout();
            if (timeout != null && timeout > 0 && costMillis > timeout * 1000L) {
                log.warn("autumn thrift slow invocation, cost:{}ms, timeout:{}s", costMillis, timeout);
            } else {
                log.debug("autumn thrift invocation finished, cost:{}ms", costMillis);
            }
            AttachmentContext.clear();
        }
    }
}
