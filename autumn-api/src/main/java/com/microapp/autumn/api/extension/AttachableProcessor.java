package com.microapp.autumn.api.extension;

import java.util.Map;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        if (in instanceof AttachableBinaryProtocol serverProtocol) {
            serverProtocol.readMessageBegin();
            serverProtocol.readFieldZero();
            Map<String, String> headInfo = serverProtocol.getAttachment();
            log.debug("read attachment content:{}", headInfo);
            serverProtocol.resetMultiTFramedTransport(in);
        }
        realProcessor.process(in, out);
    }
}
