package com.microapp.autumn.api.extension;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microapp.autumn.api.config.ProviderConfig;

public class AttachableProcessor implements TProcessor {
    private Logger log = LoggerFactory.getLogger(this.getClass());
    private TProcessor realProcessor;
    public AttachableProcessor(TProcessor realProcessor) {
        this.realProcessor = realProcessor;
    }

    @Override
    public void process(TProtocol in, TProtocol out) throws TException {
        long start = System.nanoTime();
        try {
            if (in instanceof AttachableBinaryProtocol) {
                AttachableBinaryProtocol serverProtocol = (AttachableBinaryProtocol) in;
                TMessage message = serverProtocol.readMessageBegin();
                serverProtocol.readFieldZero();
                Map<String, String> headInfo = serverProtocol.getAttachment();
                // 每次调用的附件内容属于热路径，降为 DEBUG，避免高并发日志风暴
                log.debug("read attachment content:{}", headInfo);
//            String traceId = headInfo.get(TRACE_ID.getValue());
//            String parentSpanId = headInfo.get(PARENT_SPAN_ID.getValue());
//            String isSampled = headInfo.get(IS_SAMPLED.getValue());
//            Boolean sampled = isSampled == null || Boolean.parseBoolean(isSampled);
//
//            if (traceId != null && parentSpanId != null) {
//                TraceUtils.startLocalTracer("rpc.thrift receive", traceId, parentSpanId, sampled);
//                String methodName = tMessage.name;
//                TraceUtils.submitAdditionalAnnotation(Constants.TRACE_THRIFT_METHOD, methodName);
//                TTransport transport = in.getTransport();
//                String hostAddress = ((TSocket) transport).getSocket().getRemoteSocketAddress().toString();
//                TraceUtils.submitAdditionalAnnotation(Constants.TRACE_THRIFT_SERVER, hostAddress);
//            }
            serverProtocol.resetMultiTFramedTransport(in);
            }
            realProcessor.process(in, out);
        } finally {
            // provider.timeout 同时作为慢调用告警阈值，给控制面提供最小的可观测性
            long costMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            Integer timeout = ProviderConfig.getInstance().getTimeout();
            if (timeout != null && timeout > 0 && costMillis > timeout * 1000L) {
                log.warn("autumn thrift slow invocation, cost:{}ms, timeout:{}s", costMillis, timeout);
            } else {
                log.debug("autumn thrift invocation finished, cost:{}ms", costMillis);
            }
        }
    }

}
