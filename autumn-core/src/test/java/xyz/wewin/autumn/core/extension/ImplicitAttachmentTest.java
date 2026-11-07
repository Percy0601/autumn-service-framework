package xyz.wewin.autumn.core.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Map;

import org.apache.thrift.TException;
import org.apache.thrift.TMultiplexedProcessor;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TMultiplexedProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolUtil;
import org.apache.thrift.protocol.TType;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TSimpleServer;
import org.apache.thrift.transport.TMemoryBuffer;
import org.apache.thrift.transport.TMemoryInputTransport;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.layered.TFramedTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import xyz.wewin.autumn.api.ControlApi;
import xyz.wewin.autumn.api.extension.AttachableBinaryProtocol;
import xyz.wewin.autumn.api.extension.AttachableProcessor;
import xyz.wewin.autumn.api.extension.AttachmentContext;
import xyz.wewin.autumn.api.extension.TrafficRouter;

/**
 * 隐式传参（field-0 attachment）兼容性与端到端透传测试。
 */
class ImplicitAttachmentTest {

    private static final short FIRST_ARG_FIELD_ID = 1;

    @AfterEach
    void tearDown() {
        AttachmentContext.clear();
    }

    @Test
    void implicitParametersRoundTripAndBusinessFieldsArePreserved() throws Exception {
        AttachmentContext.put("traceId", "t-42");
        AttachmentContext.put("tenant", "acme");

        TMemoryBuffer buffer = new TMemoryBuffer(256);
        AttachableBinaryProtocol out = new AttachableBinaryProtocol(buffer);
        writeCall(out, "hello");

        AttachableBinaryProtocol in = new AttachableBinaryProtocol(readOnly(buffer));
        try {
            TMessage message = in.readMessageBegin();
            assertEquals("echo", message.name);
            assertEquals(TMessageType.CALL, message.type);

            TField field = in.readFieldBegin();
            assertEquals(FIRST_ARG_FIELD_ID, field.id, "field-0 attachment 必须被透明消费");
            assertEquals(TType.STRING, field.type);
            assertEquals("hello", in.readString());
            assertEquals(TType.STOP, in.readFieldBegin().type);

            Map<String, String> attachment = in.getAttachment();
            assertEquals("t-42", attachment.get("traceId"));
            assertEquals("acme", attachment.get("tenant"));
            assertEquals("15", attachment.get("appId"));
            assertEquals("training-thrift", attachment.get("appName"));
            assertTrue(attachment.containsKey("spanId"));
            assertEquals("acme", AttachmentContext.get("tenant"), "服务端线程应能读到隐式参数");
        } finally {
            AttachmentContext.clear();
        }
    }

    @Test
    void trafficLaneTravelsWithAttachmentAlongTheCallChain() throws Exception {
        AttachmentContext.put("traffic", "gray");
        TMemoryBuffer buffer = new TMemoryBuffer(256);
        AttachableBinaryProtocol out = new AttachableBinaryProtocol(buffer);
        writeCall(out, "lane");

        AttachableBinaryProtocol in = new AttachableBinaryProtocol(readOnly(buffer));
        try {
            in.readMessageBegin();
            assertEquals(FIRST_ARG_FIELD_ID, in.readFieldBegin().id);
            assertEquals("gray", in.getAttachment().get("traffic"));
            // 服务端解码后上下文已被替换，继续向下游调用会沿用同一泳道
            assertEquals("gray", TrafficRouter.current().lane());
        } finally {
            AttachmentContext.clear();
        }
    }

    @Test
    void legacyClientWithoutAttachmentIsCompatible() throws Exception {
        TMemoryBuffer buffer = new TMemoryBuffer(128);
        TBinaryProtocol out = new TBinaryProtocol(buffer);
        writeCall(out, "legacy");

        AttachableBinaryProtocol in = new AttachableBinaryProtocol(readOnly(buffer));
        in.readMessageBegin();
        TField field = in.readFieldBegin();
        assertEquals(FIRST_ARG_FIELD_ID, field.id);
        assertEquals("legacy", in.readString());
        assertTrue(in.getAttachment().isEmpty());
        assertTrue(AttachmentContext.snapshot().isEmpty());
    }

    @Test
    void legacyServerSkipsAttachmentAsUnknownField() throws Exception {
        AttachmentContext.put("tenant", "acme");
        TMemoryBuffer buffer = new TMemoryBuffer(256);
        AttachableBinaryProtocol out = new AttachableBinaryProtocol(buffer);
        writeCall(out, "hello");

        TBinaryProtocol in = new TBinaryProtocol(readOnly(buffer));
        in.readMessageBegin();
        TField field = in.readFieldBegin();
        assertEquals(0, field.id);
        assertEquals(TType.MAP, field.type);
        TProtocolUtil.skip(in, field.type);
        assertEquals(FIRST_ARG_FIELD_ID, in.readFieldBegin().id);
        assertEquals("hello", in.readString());
    }

    @Test
    void replyFieldZeroIsNotMistakenForAttachment() throws Exception {
        AttachmentContext.put("tenant", "acme");
        TMemoryBuffer buffer = new TMemoryBuffer(128);
        AttachableBinaryProtocol out = new AttachableBinaryProtocol(buffer);
        out.writeMessageBegin(new TMessage("echo", TMessageType.REPLY, 1));
        out.writeFieldBegin(new TField("success", TType.STRING, (short) 0));
        out.writeString("OK");
        out.writeFieldEnd();
        out.writeFieldStop();
        out.writeMessageEnd();

        AttachableBinaryProtocol in = new AttachableBinaryProtocol(readOnly(buffer));
        in.readMessageBegin();
        TField field = in.readFieldBegin();
        assertEquals(0, field.id);
        assertEquals(TType.STRING, field.type);
        assertEquals("OK", in.readString());
        assertTrue(in.getAttachment().isEmpty());
        assertEquals("acme", AttachmentContext.get("tenant"), "响应不应覆盖调用方上下文");
    }

    @Test
    void implicitParametersFlowOverTcpWithMultiplexedProcessor() throws Exception {
        ControlApi.Iface handler = (ControlApi.Iface) Proxy.newProxyInstance(
                ControlApi.Iface.class.getClassLoader(),
                new Class<?>[]{ControlApi.Iface.class},
                (proxy, method, args) -> "trace=" + AttachmentContext.get("traceId")
                        + ",tenant=" + AttachmentContext.get("tenant"));

        TServerSocket serverTransport = new TServerSocket(new InetSocketAddress("127.0.0.1", 0));
        int port = serverTransport.getServerSocket().getLocalPort();
        TMultiplexedProcessor multiplexed = new TMultiplexedProcessor();
        multiplexed.registerProcessor(ControlApi.Iface.class.getName(),
                new AttachableProcessor(new ControlApi.Processor<ControlApi.Iface>(handler)));
        TServer server = new TSimpleServer(new TServer.Args(serverTransport)
                .processor(multiplexed)
                .transportFactory(new TFramedTransport.Factory())
                .protocolFactory(new AttachableBinaryProtocol.Factory()));
        Thread serverThread = new Thread(server::serve, "implicit-attachment-test-server");
        serverThread.setDaemon(true);
        serverThread.start();
        for (int i = 0; i < 50 && !server.isServing(); i++) {
            Thread.sleep(20);
        }
        assertTrue(server.isServing(), "test thrift server did not start");

        TSocket socket = new TSocket("127.0.0.1", port);
        socket.setConnectTimeout(2000);
        socket.setTimeout(3000);
        TTransport transport = new TFramedTransport(socket);
        try {
            transport.open();
            ControlApi.Client client = new ControlApi.Client(new TMultiplexedProtocol(
                    new AttachableBinaryProtocol(transport), ControlApi.Iface.class.getName()));

            AttachmentContext.put("traceId", "t-7");
            AttachmentContext.put("tenant", "acme");
            assertEquals("trace=t-7,tenant=acme", client.health(""));

            // 线程池复用：下一次请求不带自定义隐式参数时不能残留上一次的值
            AttachmentContext.clear();
            String second = client.health("");
            assertTrue(second.startsWith("trace="));
            assertNotEquals("trace=t-7", second.split(",")[0]);
            assertTrue(second.endsWith("tenant=null"), second);

            // 旧客户端（TBinaryProtocol，无 field-0）仍可调用新服务端
            ControlApi.Client legacyClient = new ControlApi.Client(new TMultiplexedProtocol(
                    new TBinaryProtocol(transport), ControlApi.Iface.class.getName()));
            String legacy = legacyClient.health("");
            assertTrue(legacy.startsWith("trace="));
            assertTrue(legacy.endsWith("tenant=null"), legacy);
            assertFalse(legacy.contains("acme"));
        } finally {
            transport.close();
            server.stop();
            serverTransport.close();
            AttachmentContext.clear();
        }
    }

    private static void writeCall(TProtocol protocol, String value) throws TException {
        protocol.writeMessageBegin(new TMessage("echo", TMessageType.CALL, 1));
        protocol.writeFieldBegin(new TField("msg", TType.STRING, FIRST_ARG_FIELD_ID));
        protocol.writeString(value);
        protocol.writeFieldEnd();
        protocol.writeFieldStop();
        protocol.writeMessageEnd();
    }

    private static TMemoryInputTransport readOnly(TMemoryBuffer buffer) throws TException {
        return new TMemoryInputTransport(Arrays.copyOf(buffer.getArray(), buffer.length()));
    }
}
