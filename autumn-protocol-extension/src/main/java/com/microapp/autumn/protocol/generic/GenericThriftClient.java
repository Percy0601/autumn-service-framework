package com.microapp.autumn.protocol.generic;

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TProtocol;

/**
 * Transport-agnostic generic caller: send a {@code *_args} message and decode the
 * {@code *_result} message on any {@link TProtocol} (plain, framed, multiplexed, or
 * the attachment-aware binary protocol). Connections can be pooled by the caller.
 */
public final class GenericThriftClient {

    private final AtomicInteger sequence = new AtomicInteger();

    public Object invoke(TProtocol protocol, MethodSpec method, Object arguments) throws TException {
        int seqid = sequence.updateAndGet(current -> current == Integer.MAX_VALUE ? 1 : current + 1);
        ThriftCodec.writeMessageBegin(protocol, method, seqid);
        ThriftCodec.writeArgs(protocol, method, arguments);
        protocol.writeMessageEnd();
        protocol.getTransport().flush();
        if (method.oneway()) {
            return null;
        }
        return ThriftCodec.readResult(protocol, method, seqid);
    }
}
