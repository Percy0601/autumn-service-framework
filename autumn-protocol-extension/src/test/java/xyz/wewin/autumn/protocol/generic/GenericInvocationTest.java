package xyz.wewin.autumn.protocol.generic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.TMultiplexedProcessor;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TList;
import org.apache.thrift.protocol.TMap;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolUtil;
import org.apache.thrift.protocol.TSet;
import org.apache.thrift.protocol.TStruct;
import org.apache.thrift.protocol.TType;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TSimpleServer;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.layered.TFramedTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xyz.wewin.autumn.api.extension.AttachableBinaryProtocol;
import xyz.wewin.autumn.api.extension.AttachmentContext;

/**
 * End-to-end tests against a real Thrift TCP server written with raw TProtocol primitives
 * (no generated stubs on either side), including field-0 attachment, multiplexing and errors.
 */
class GenericInvocationTest {

    private static final String SERVICE = "HelloWorldService";

    private TServer server;
    private TServerSocket serverTransport;
    private int port;
    private ServiceRegistry registry;
    private GenericThriftInvoker invoker;
    private final AtomicReference<String> onewayPayload = new AtomicReference<>();
    private final CountDownLatch onewayLatch = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws Exception {
        AttachmentContext.clear();
        registry = new ServiceRegistry();
        registry.registerIdl(IdlParserTest.idl());
        invoker = new GenericThriftInvoker(registry);

        serverTransport = new TServerSocket(new InetSocketAddress("127.0.0.1", 0));
        port = serverTransport.getServerSocket().getLocalPort();
        StubProcessor processor = new StubProcessor(onewayPayload, onewayLatch);
        TMultiplexedProcessor multiplexed = new TMultiplexedProcessor();
        multiplexed.registerProcessor(SERVICE, processor);
        multiplexed.registerDefault(processor);
        server = new TSimpleServer(new TServer.Args(serverTransport)
                .processor(multiplexed)
                .transportFactory(new TFramedTransport.Factory())
                .protocolFactory(new AttachableBinaryProtocol.Factory()));
        Thread thread = new Thread(server::serve, "generic-invocation-test-server");
        thread.setDaemon(true);
        thread.start();
        for (int i = 0; i < 100 && !server.isServing(); i++) {
            Thread.sleep(10);
        }
        assertTrue(server.isServing(), "thrift test server did not start");
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
        if (serverTransport != null) {
            serverTransport.close();
        }
        AttachmentContext.clear();
    }

    private GenericRequest request(String method) {
        return GenericRequest.of("127.0.0.1", port, SERVICE, method);
    }

    @Test
    void primitiveResultAndExplicitAttachmentPassThrough() {
        GenericResponse response = invoker.invoke(request("sayHello")
                .setArguments(Map.of("username", "tom"))
                .attachment("tenant", "acme"));

        assertTrue(response.isSuccess(), String.valueOf(response.getErrorMessage()));
        assertEquals("hello tom/tenant=acme", response.getValue());
        assertTrue(response.asMap().containsKey("data"));
    }

    @Test
    void structReturnAndStructArgumentRoundTrip() {
        GenericResponse model = invoker.invoke(request("returnModel")
                .setArguments(Map.of("name", "n1")));
        assertTrue(model.isSuccess(), String.valueOf(model.getErrorMessage()));
        assertEquals(Map.of("id", 42, "name", "n1"), model.getValue());

        Map<String, Object> argument = new LinkedHashMap<>();
        argument.put("id", 7);
        argument.put("name", "n2");
        GenericResponse processed = invoker.invoke(request("modelProcess")
                .setArguments(Map.of("model", argument)));
        assertTrue(processed.isSuccess(), String.valueOf(processed.getErrorMessage()));
        assertEquals("n2 processed", processed.getValue());
    }

    @Test
    void listAndMapArgumentsAndResults() {
        GenericResponse sum = invoker.invoke(request("sum")
                .setArguments(Map.of("nums", List.of(1, 2, 3, 4))));
        assertTrue(sum.isSuccess(), String.valueOf(sum.getErrorMessage()));
        assertEquals(10, sum.getValue());

        GenericResponse echoed = invoker.invoke(request("echoMap")
                .setArguments(Map.of("data", Map.of("a", 1, "b", 2))));
        assertTrue(echoed.isSuccess(), String.valueOf(echoed.getErrorMessage()));
        assertEquals(Map.of("a", 1, "b", 2), echoed.getValue());
    }

    @Test
    void declaredBusinessExceptionIsDecodedAsJsonFriendlyValue() {
        GenericResponse response = invoker.invoke(request("fail")
                .setArguments(Map.of("reason", "x")));

        assertFalse(response.isSuccess());
        assertEquals(GenericResponse.ERROR_SERVICE, response.getErrorType());
        assertEquals(Map.of("message", "boom:x"), response.getErrorValue());
    }

    @Test
    void unknownMethodYieldsApplicationError() {
        ServiceSpec extra = ServiceSpec.builder(SERVICE)
                .method(MethodSpec.builder("notThere").returns(TypeSpec.of(ThriftType.STRING)).build())
                .build();
        GenericResponse response = invoker.invoke(request("notThere").setDescriptor(extra));

        assertFalse(response.isSuccess());
        assertEquals(GenericResponse.ERROR_APPLICATION, response.getErrorType());
        assertTrue(response.getErrorMessage().contains("unknown method"));
    }

    @Test
    void missingRequiredArgumentIsRejectedBeforeOpeningASocket() {
        GenericResponse response = invoker.invoke(request("sayHello"));
        assertFalse(response.isSuccess());
        assertEquals(GenericResponse.ERROR_BAD_REQUEST, response.getErrorType());
        assertTrue(response.getErrorMessage().contains("username"));
    }

    @Test
    void multiplexedServiceRoutingWorks() {
        GenericResponse response = invoker.invoke(request("sayHello")
                .setArguments(Map.of("username", "m"))
                .setMultiplexed(true)
                .attachment("tenant", "mp"));
        assertTrue(response.isSuccess(), String.valueOf(response.getErrorMessage()));
        assertEquals("hello m/tenant=mp", response.getValue());
    }

    @Test
    void legacyModeDoesNotInjectAttachments() {
        GenericResponse response = invoker.invoke(request("sayHello")
                .setArguments(Map.of("username", "legacy"))
                .attachment("tenant", "ignored")
                .setAttachmentsEnabled(false));
        assertTrue(response.isSuccess(), String.valueOf(response.getErrorMessage()));
        assertEquals("hello legacy/tenant=null", response.getValue());
    }

    @Test
    void descriptorCanBeRegisteredAtRuntimeWithoutRestart() {
        ServiceRegistry fresh = new ServiceRegistry();
        GenericThriftInvoker dynamic = new GenericThriftInvoker(fresh);

        GenericResponse before = dynamic.invoke(request("sayHello")
                .setArguments(Map.of("username", "u")));
        assertFalse(before.isSuccess());
        assertEquals(GenericResponse.ERROR_BAD_REQUEST, before.getErrorType());

        fresh.registerIdl(IdlParserTest.idl());
        GenericResponse after = dynamic.invoke(request("sayHello")
                .setArguments(Map.of("username", "u")));
        assertTrue(after.isSuccess(), String.valueOf(after.getErrorMessage()));
        assertEquals("hello u/tenant=null", after.getValue());
    }

    @Test
    void onewayCallReturnsWithoutWaitingForAReply() throws Exception {
        GenericResponse response = invoker.invoke(request("ping")
                .setArguments(Map.of("msg", "hi")));
        assertTrue(response.isSuccess(), String.valueOf(response.getErrorMessage()));
        assertNull(response.getValue());
        assertTrue(onewayLatch.await(3, TimeUnit.SECONDS), "server did not receive oneway call");
        assertEquals("hi", onewayPayload.get());
    }

    @Test
    void asyncFacadeWorksOffTheCallingThread() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            GenericResponse response = invoker.invokeAsync(request("sayHello")
                    .setArguments(Map.of("username", "async")), executor).get(5, TimeUnit.SECONDS);
            assertTrue(response.isSuccess(), String.valueOf(response.getErrorMessage()));
            assertEquals("hello async/tenant=null", response.getValue());
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ReplyBody {
        void write(TProtocol protocol) throws TException;
    }

    /** A tiny hand-written Thrift service implementation, independent of the generic codec. */
    private static final class StubProcessor implements TProcessor {

        private final AtomicReference<String> onewayPayload;
        private final CountDownLatch onewayLatch;

        private StubProcessor(AtomicReference<String> onewayPayload, CountDownLatch onewayLatch) {
            this.onewayPayload = onewayPayload;
            this.onewayLatch = onewayLatch;
        }

        @Override
        public void process(TProtocol in, TProtocol out) throws TException {
            try {
                TMessage message = in.readMessageBegin();
                Map<Short, Object> args = readArgs(in);
                in.readMessageEnd();
                switch (message.name) {
                    case "sayHello" -> reply(out, message, p -> {
                        p.writeFieldBegin(new TField("success", TType.STRING, (short) 0));
                        p.writeString("hello " + args.get((short) 1)
                                + "/tenant=" + AttachmentContext.get("tenant"));
                        p.writeFieldEnd();
                    });
                    case "returnModel" -> reply(out, message, p -> {
                        p.writeFieldBegin(new TField("success", TType.STRUCT, (short) 0));
                        p.writeStructBegin(new TStruct("Model"));
                        p.writeFieldBegin(new TField("id", TType.I32, (short) 1));
                        p.writeI32(42);
                        p.writeFieldEnd();
                        p.writeFieldBegin(new TField("name", TType.STRING, (short) 2));
                        p.writeString(String.valueOf(args.get((short) 1)));
                        p.writeFieldEnd();
                        p.writeFieldStop();
                        p.writeStructEnd();
                        p.writeFieldEnd();
                    });
                    case "modelProcess" -> reply(out, message, p -> {
                        @SuppressWarnings("unchecked")
                        Map<Short, Object> model = (Map<Short, Object>) args.get((short) 1);
                        p.writeFieldBegin(new TField("success", TType.STRING, (short) 0));
                        p.writeString(model.get((short) 2) + " processed");
                        p.writeFieldEnd();
                    });
                    case "sum" -> reply(out, message, p -> {
                        List<?> numbers = (List<?>) args.get((short) 1);
                        int total = numbers.stream().mapToInt(v -> ((Number) v).intValue()).sum();
                        p.writeFieldBegin(new TField("success", TType.I32, (short) 0));
                        p.writeI32(total);
                        p.writeFieldEnd();
                    });
                    case "echoMap" -> reply(out, message, p -> {
                        Map<?, ?> data = (Map<?, ?>) args.get((short) 1);
                        p.writeFieldBegin(new TField("success", TType.MAP, (short) 0));
                        p.writeMapBegin(new TMap(TType.STRING, TType.I32, data.size()));
                        for (Map.Entry<?, ?> entry : data.entrySet()) {
                            p.writeString(String.valueOf(entry.getKey()));
                            p.writeI32(((Number) entry.getValue()).intValue());
                        }
                        p.writeMapEnd();
                        p.writeFieldEnd();
                    });
                    case "fail" -> reply(out, message, p -> {
                        p.writeFieldBegin(new TField("e", TType.STRUCT, (short) 1));
                        p.writeStructBegin(new TStruct("ErrorEx"));
                        p.writeFieldBegin(new TField("message", TType.STRING, (short) 1));
                        p.writeString("boom:" + args.get((short) 1));
                        p.writeFieldEnd();
                        p.writeFieldStop();
                        p.writeStructEnd();
                        p.writeFieldEnd();
                    });
                    case "ping" -> {
                        onewayPayload.set(String.valueOf(args.get((short) 1)));
                        onewayLatch.countDown();
                    }
                    default -> {
                        out.writeMessageBegin(new TMessage(message.name, TMessageType.EXCEPTION, message.seqid));
                        new TApplicationException(TApplicationException.UNKNOWN_METHOD,
                                "unknown method " + message.name).write(out);
                        out.writeMessageEnd();
                        out.getTransport().flush();
                    }
                }
            } finally {
                AttachmentContext.clear();
            }
        }

        private static void reply(TProtocol out, TMessage message, ReplyBody body) throws TException {
            out.writeMessageBegin(new TMessage(message.name, TMessageType.REPLY, message.seqid));
            out.writeStructBegin(new TStruct(message.name + "_result"));
            body.write(out);
            out.writeFieldStop();
            out.writeStructEnd();
            out.writeMessageEnd();
            out.getTransport().flush();
        }

        private static Map<Short, Object> readArgs(TProtocol in) throws TException {
            Map<Short, Object> args = new LinkedHashMap<>();
            in.readStructBegin();
            while (true) {
                TField field = in.readFieldBegin();
                if (field.type == TType.STOP) {
                    break;
                }
                args.put(field.id, readAny(in, field.type));
                in.readFieldEnd();
            }
            in.readStructEnd();
            return args;
        }

        private static Map<Short, Object> readStruct(TProtocol in) throws TException {
            in.readStructBegin();
            Map<Short, Object> fields = new LinkedHashMap<>();
            while (true) {
                TField field = in.readFieldBegin();
                if (field.type == TType.STOP) {
                    break;
                }
                fields.put(field.id, readAny(in, field.type));
                in.readFieldEnd();
            }
            in.readStructEnd();
            return fields;
        }

        private static Object readAny(TProtocol in, byte type) throws TException {
            return switch (type) {
                case TType.BOOL -> in.readBool();
                case TType.BYTE -> in.readByte();
                case TType.I16 -> in.readI16();
                case TType.I32 -> in.readI32();
                case TType.I64 -> in.readI64();
                case TType.DOUBLE -> in.readDouble();
                case TType.STRING -> in.readString();
                case TType.STRUCT -> readStruct(in);
                case TType.LIST -> {
                    TList list = in.readListBegin();
                    List<Object> values = new java.util.ArrayList<>(Math.max(0, list.size));
                    for (int i = 0; i < list.size; i++) {
                        values.add(readAny(in, list.elemType));
                    }
                    in.readListEnd();
                    yield values;
                }
                case TType.SET -> {
                    TSet set = in.readSetBegin();
                    List<Object> values = new java.util.ArrayList<>(Math.max(0, set.size));
                    for (int i = 0; i < set.size; i++) {
                        values.add(readAny(in, set.elemType));
                    }
                    in.readSetEnd();
                    yield values;
                }
                case TType.MAP -> {
                    TMap map = in.readMapBegin();
                    Map<Object, Object> values = new LinkedHashMap<>();
                    for (int i = 0; i < map.size; i++) {
                        values.put(readAny(in, map.keyType), readAny(in, map.valueType));
                    }
                    in.readMapEnd();
                    yield values;
                }
                default -> {
                    TProtocolUtil.skip(in, type);
                    yield null;
                }
            };
        }
    }
}
