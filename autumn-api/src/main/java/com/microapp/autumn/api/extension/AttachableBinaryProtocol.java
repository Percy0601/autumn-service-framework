package com.microapp.autumn.api.extension;

import java.io.BufferedInputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TMap;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TType;
import org.apache.thrift.transport.TIOStreamTransport;
import org.apache.thrift.transport.TMemoryInputTransport;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.layered.TFastFramedTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AttachableBinaryProtocol extends TBinaryProtocol {
    private Logger log = LoggerFactory.getLogger(this.getClass());
    private static final long NO_LENGTH_LIMIT = -1;
    private Map<String, String> attachment;

    public AttachableBinaryProtocol(TTransport trans) {
        super(trans);
        attachment = new HashMap<>();
    }

    public AttachableBinaryProtocol(TTransport trans, boolean strictRead, boolean strictWrite) {
        super(trans, strictRead, strictWrite);
        attachment = new HashMap<>();
    }

    public AttachableBinaryProtocol(TTransport trans,
                                    long stringLengthLimit,
                                    long containerLengthLimit,
                                    boolean strictRead,
                                    boolean strictWrite) {
        super(trans, stringLengthLimit, containerLengthLimit, strictRead, strictWrite);
        attachment = new HashMap<>();
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

        public TProtocol getProtocol(TTransport trans) {
            return new AttachableBinaryProtocol(trans, stringLengthLimit_, containerLengthLimit_, strictRead_, strictWrite_);
        }
    }

    public void markTFramedTransport(TProtocol in) {
        try {
            Field tioInputStream = TIOStreamTransportFieldsCache.getInstance().getTIOInputStream();
            if (tioInputStream == null){
                return;
            }
            BufferedInputStream inputStream = (BufferedInputStream) tioInputStream.get(in.getTransport());
            inputStream.mark(0);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void writeMessageBegin(TMessage message) throws TException {
        super.writeMessageBegin(message);
        Random r = new Random();
        attachment.put("appId", "15");
        attachment.put("appName", "training-thrift");
        attachment.put("traceId", String.valueOf(r.nextInt(10000)));
        attachment.put("spanId", String.valueOf(r.nextInt(100)));
        if(attachment.size() > 0){
            writeFieldZero();
        }
    }

    public void writeFieldZero() throws TException{
        TField ATTACHMENT = new TField("attachment", TType.MAP, (short) 0);
        this.writeFieldBegin(ATTACHMENT);
        {
            this.writeMapBegin(new TMap(TType.STRING, TType.STRING, attachment.size()));
            for (Map.Entry<String, String> entry: attachment.entrySet()) {
                this.writeString(entry.getKey());
                this.writeString(entry.getValue());
            }
            this.writeMapEnd();
        }
        this.writeFieldEnd();
    }

    public boolean readFieldZero() throws TException {
        TField schemeField = this.readFieldBegin();
        if (schemeField.id == 0 && schemeField.type == TType.MAP) {
            TMap _map = this.readMapBegin();
            attachment = new HashMap<>(_map.size);
            for (int i = 0; i < _map.size; ++i) {
                String key = this.readString();
                String value = this.readString();
                attachment.put(key, value);
            }
            this.readMapEnd();
        }
        this.readFieldEnd();
        return attachment.size() > 0;
    }

    public void resetMultiTFramedTransport(TProtocol in) {
        try {
            Field readBuffer_ = TFramedTransportFieldsCache.getInstance()
                    .getTFramedTransportReadBuffer();
            Field buf_ = TFramedTransportFieldsCache.getInstance()
                    .getTMemoryInputTransportBuf();
            if (readBuffer_ == null || buf_ == null) {
                return;
            }
            TMemoryInputTransport stream = (TMemoryInputTransport) readBuffer_.get(in.getTransport());
            byte[] buf = (byte[]) (buf_.get(stream));
            stream.reset(buf, 0, buf.length);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static class TFramedTransportFieldsCache {
        private static TFramedTransportFieldsCache instance;
        private final Field readBuffer_;
        private final Field buf_;
        private final String TFramedTransport_readBuffer_ = "readBuffer_";
        private final String TMemoryInputTransport_buf_ = "buf_";

        private TFramedTransportFieldsCache() throws Exception {
            readBuffer_ = TFastFramedTransport.class
                    .getDeclaredField(TFramedTransport_readBuffer_);
            readBuffer_.setAccessible(true);
            buf_ = TMemoryInputTransport.class
                    .getDeclaredField(TMemoryInputTransport_buf_);
            buf_.setAccessible(true);
        }

        public static TFramedTransportFieldsCache getInstance()
                throws Exception {
            if (instance == null) {
                synchronized (TFramedTransportFieldsCache.class) {
                    if (instance == null) {
                        instance = new TFramedTransportFieldsCache();
                    }
                }
            }
            return instance;
        }

        public Field getTFramedTransportReadBuffer() {
            return readBuffer_;
        }

        public Field getTMemoryInputTransportBuf() {
            return buf_;
        }
    }

    /*
     * 重置TFramedTransport流，不影响Thrift原有流程
     */
    public void resetTFramedTransport(TProtocol in) {
        try {
            Field tioInputStream = TIOStreamTransportFieldsCache.getInstance().getTIOInputStream();
            if (tioInputStream == null){
                return;
            }
            BufferedInputStream inputStream = (BufferedInputStream) tioInputStream.get(in.getTransport());
            inputStream.reset();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static class TIOStreamTransportFieldsCache {
        private static TIOStreamTransportFieldsCache instance;
        private final Field inputStream_;
        private final String TIOStreamTransport_inputStream_ = "inputStream_";

        private TIOStreamTransportFieldsCache() throws Exception {
            inputStream_ = TIOStreamTransport.class
                    .getDeclaredField(TIOStreamTransport_inputStream_);
            inputStream_.setAccessible(true);
        }

        public static TIOStreamTransportFieldsCache getInstance()
                throws Exception {
            if (instance == null) {
                synchronized (TIOStreamTransportFieldsCache.class) {
                    if (instance == null) {
                        instance = new TIOStreamTransportFieldsCache();
                    }
                }
            }
            return instance;
        }

        public Field getTIOInputStream() {
            return inputStream_;
        }
    }

    @Override
    public TMessage readMessageBegin() throws TException {
        TMessage tMessage = super.readMessageBegin();
        readFieldZero();

        resetMultiTFramedTransport(this);
        tMessage = super.readMessageBegin();
        return tMessage;
    }

    public Map<String, String> getAttachment() {
        return attachment;
    }
}


