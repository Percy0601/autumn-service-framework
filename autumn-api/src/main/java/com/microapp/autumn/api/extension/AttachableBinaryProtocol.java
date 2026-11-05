package com.microapp.autumn.api.extension;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TMap;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolUtil;
import org.apache.thrift.protocol.TType;
import org.apache.thrift.transport.TTransport;

/**
 * 隐式传参协议：在 thrift 请求消息头之后追加 field-0 (MAP&lt;string,string&gt;) 作为 attachment。
 *
 * <p>读取侧不偷看、不重置流：field-0 由 {@link #readFieldBegin()} 在生成代码读取参数时顺带解析，
 * 不再反射访问 TFramedTransport / TIOStreamTransport 的私有字段，GraalVM native 下无需额外 hint。
 * 只在请求方向解析，避免把响应中 id=0 的 success 字段误判为 attachment。</p>
 *
 * <p>兼容性：旧客户端（TBinaryProtocol，无 field-0）与新客户端（带 field-0）都能被本服务端处理；
 * 旧服务端会把 field-0 当作未知字段跳过，因此本客户端也能直连旧服务端。</p>
 */
public class AttachableBinaryProtocol extends TBinaryProtocol {

    private static final long NO_LENGTH_LIMIT = -1;
    private static final short ATTACHMENT_FIELD_ID = 0;
    private static final String ATTACHMENT_FIELD_NAME = "attachment";
    private static final String DEFAULT_APP_ID = "15";
    private static final String DEFAULT_APP_NAME = "training-thrift";

    private Map<String, String> attachment = new HashMap<>();
    private boolean readingRequest;

    public AttachableBinaryProtocol(TTransport trans) {
        super(trans);
    }

    public AttachableBinaryProtocol(TTransport trans, boolean strictRead, boolean strictWrite) {
        super(trans, strictRead, strictWrite);
    }

    public AttachableBinaryProtocol(TTransport trans,
                                    long stringLengthLimit,
                                    long containerLengthLimit,
                                    boolean strictRead,
                                    boolean strictWrite) {
        super(trans, stringLengthLimit, containerLengthLimit, strictRead, strictWrite);
    }

    public static class Factory extends TBinaryProtocol.Factory {
        public Factory() {
            this(false, true);
        }

        public Factory(boolean strictRead, boolean strictWrite) {
            this(strictRead, strictWrite, NO_LENGTH_LIMIT, NO_LENGTH_LIMIT);
        }

        public Factory(boolean strictRead, boolean strictWrite, long stringLengthLimit, long containerLengthLimit) {
            stringLengthLimit_ = stringLengthLimit;
            containerLengthLimit_ = containerLengthLimit;
            strictRead_ = strictRead;
            strictWrite_ = strictWrite;
        }

        @Override
        public TProtocol getProtocol(TTransport trans) {
            return new AttachableBinaryProtocol(trans, stringLengthLimit_, containerLengthLimit_, strictRead_, strictWrite_);
        }
    }

    @Override
    public void writeMessageBegin(TMessage message) throws TException {
        super.writeMessageBegin(message);
        // 响应方向不写 attachment：避免服务端回包覆盖调用方的隐式参数上下文
        if (message.type == TMessageType.CALL || message.type == TMessageType.ONEWAY) {
            writeAttachment(outboundAttachment());
        }
    }

    @Override
    public TMessage readMessageBegin() throws TException {
        TMessage message = super.readMessageBegin();
        readingRequest = message.type == TMessageType.CALL || message.type == TMessageType.ONEWAY;
        if (readingRequest) {
            // 连接复用时清掉上一次请求的解析结果，调用方上下文由 AttachableProcessor 管理
            attachment = new HashMap<>();
        }
        return message;
    }

    @Override
    public TField readFieldBegin() throws TException {
        TField field = super.readFieldBegin();
        while (readingRequest && field.id == ATTACHMENT_FIELD_ID && field.type == TType.MAP) {
            Map<String, String> values = readAttachmentMap();
            attachment = values;
            AttachmentContext.replace(values);
            field = super.readFieldBegin();
        }
        return field;
    }

    private Map<String, String> outboundAttachment() {
        Map<String, String> values = new LinkedHashMap<>(AttachmentContext.snapshot());
        String trace = UUID.randomUUID().toString().replace("-", "");
        values.putIfAbsent("appId", DEFAULT_APP_ID);
        values.putIfAbsent("appName", DEFAULT_APP_NAME);
        values.putIfAbsent("traceId", trace);
        values.putIfAbsent("spanId", trace.substring(0, 8));
        return values;
    }

    private void writeAttachment(Map<String, String> values) throws TException {
        if (values == null || values.isEmpty()) {
            return;
        }
        writeFieldBegin(new TField(ATTACHMENT_FIELD_NAME, TType.MAP, ATTACHMENT_FIELD_ID));
        writeMapBegin(new TMap(TType.STRING, TType.STRING, values.size()));
        for (Map.Entry<String, String> entry : values.entrySet()) {
            writeString(entry.getKey());
            writeString(entry.getValue());
        }
        writeMapEnd();
        writeFieldEnd();
    }

    private Map<String, String> readAttachmentMap() throws TException {
        TMap map = readMapBegin();
        Map<String, String> values = new HashMap<>(Math.max(0, map.size));
        for (int i = 0; i < map.size; ++i) {
            if (map.keyType == TType.STRING && map.valueType == TType.STRING) {
                values.put(readString(), readString());
            } else {
                // 非 string->string 的非法 attachment 只跳过，不打断业务参数读取
                TProtocolUtil.skip(this, map.keyType);
                TProtocolUtil.skip(this, map.valueType);
            }
        }
        readMapEnd();
        return values;
    }

    public Map<String, String> getAttachment() {
        return Collections.unmodifiableMap(new HashMap<>(attachment));
    }

    /** 显式写出当前隐式参数；通常由 writeMessageBegin 自动完成。 */
    public void writeFieldZero() throws TException {
        writeAttachment(outboundAttachment());
    }

    /** @deprecated field-0 已由 readFieldBegin 自动解析，这里只返回解析结果，不再消费流。 */
    @Deprecated
    public boolean readFieldZero() {
        return !attachment.isEmpty();
    }

    /** @deprecated 新版无需重置 framed transport 缓冲，保留空实现兼容旧调用。 */
    @Deprecated
    public void markTFramedTransport(TProtocol in) {
    }

    /** @deprecated 新版无需重置 framed transport 缓冲，保留空实现兼容旧调用。 */
    @Deprecated
    public void resetMultiTFramedTransport(TProtocol in) {
    }

    /** @deprecated 新版无需重置 framed transport 缓冲，保留空实现兼容旧调用。 */
    @Deprecated
    public void resetTFramedTransport(TProtocol in) {
    }
}
