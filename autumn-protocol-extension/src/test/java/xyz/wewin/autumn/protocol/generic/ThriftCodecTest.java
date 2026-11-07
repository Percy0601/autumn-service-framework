package xyz.wewin.autumn.protocol.generic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TStruct;
import org.apache.thrift.protocol.TType;
import org.apache.thrift.transport.TMemoryBuffer;
import org.apache.thrift.transport.TMemoryInputTransport;
import org.junit.jupiter.api.Test;

class ThriftCodecTest {

    private static TypeSpec primitive(ThriftType type) {
        return TypeSpec.of(type);
    }

    private static Object roundTrip(TypeSpec spec, Object value) throws TException {
        TMemoryBuffer buffer = new TMemoryBuffer(512);
        ThriftCodec.writeValue(new TBinaryProtocol(buffer), spec, value);
        return ThriftCodec.readValue(new TBinaryProtocol(readOnly(buffer)), spec);
    }

    private static TMemoryInputTransport readOnly(TMemoryBuffer buffer) throws TException {
        return new TMemoryInputTransport(Arrays.copyOf(buffer.getArray(), buffer.length()));
    }

    @Test
    void allPrimitiveTypesRoundTrip() throws Exception {
        assertEquals(true, roundTrip(primitive(ThriftType.BOOL), true));
        assertEquals((byte) 7, roundTrip(primitive(ThriftType.BYTE), 7));
        assertEquals((short) 300, roundTrip(primitive(ThriftType.I16), 300));
        assertEquals(123456, roundTrip(primitive(ThriftType.I32), 123456));
        assertEquals(1234567890123L, roundTrip(primitive(ThriftType.I64), 1234567890123L));
        assertEquals(1.5d, roundTrip(primitive(ThriftType.DOUBLE), 1.5d));
        assertEquals("hello", roundTrip(primitive(ThriftType.STRING), "hello"));
        assertArrayEquals(new byte[]{1, 2, 3},
                (byte[]) roundTrip(primitive(ThriftType.BINARY), new byte[]{1, 2, 3}));
        assertEquals(9, roundTrip(TypeSpec.of(ThriftType.ENUM), 9));
    }

    @Test
    void containersAndNestedStructRoundTrip() throws Exception {
        assertEquals(List.of(1, 2, 3),
                roundTrip(TypeSpec.list(primitive(ThriftType.I32)), List.of(1, 2, 3)));
        assertEquals(List.of("a", "b"),
                roundTrip(TypeSpec.set(primitive(ThriftType.STRING)), List.of("a", "b")));

        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("a", 1);
        map.put("b", 2);
        assertEquals(map, roundTrip(
                TypeSpec.map(primitive(ThriftType.STRING), primitive(ThriftType.I32)), map));

        TypeSpec struct = TypeSpec.struct("Wrapper",
                TypeSpec.field((short) 1, "id", primitive(ThriftType.I32)),
                TypeSpec.optionalField((short) 2, "model", model()),
                TypeSpec.field((short) 3, "tags", TypeSpec.list(primitive(ThriftType.STRING))));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", 5);
        value.put("model", Map.of("id", 42, "name", "n"));
        value.put("tags", List.of("x", "y"));
        assertEquals(value, roundTrip(struct, value));
    }

    @Test
    void structIsReadByFieldIdAndUnknownFieldsAreSkipped() throws Exception {
        TMemoryBuffer buffer = new TMemoryBuffer(256);
        TBinaryProtocol out = new TBinaryProtocol(buffer);
        out.writeStructBegin(new TStruct("Model"));
        // deliberately out of order, plus a field added by a newer server
        out.writeFieldBegin(new TField("name", TType.STRING, (short) 2));
        out.writeString("n");
        out.writeFieldEnd();
        out.writeFieldBegin(new TField("future", TType.STRING, (short) 99));
        out.writeString("ignored");
        out.writeFieldEnd();
        out.writeFieldBegin(new TField("id", TType.I32, (short) 1));
        out.writeI32(7);
        out.writeFieldEnd();
        out.writeFieldStop();
        out.writeStructEnd();

        @SuppressWarnings("unchecked")
        Map<String, Object> decoded = (Map<String, Object>) ThriftCodec.readValue(
                new TBinaryProtocol(readOnly(buffer)), model());
        assertEquals(7, decoded.get("id"));
        assertEquals("n", decoded.get("name"));
        assertEquals(2, decoded.size(), "unknown field 99 must be skipped");
    }

    @Test
    void compositeArgumentIsWrappedInAProperField() throws Exception {
        // Regression: the original generic-thrift-client wrote a struct argument WITHOUT
        // writeFieldBegin/End, which corrupts the args struct on the wire.
        MethodSpec method = MethodSpec.builder("modelProcess")
                .arg((short) 1, "model", model())
                .returns(primitive(ThriftType.STRING))
                .build();

        TMemoryBuffer buffer = new TMemoryBuffer(256);
        TBinaryProtocol out = new TBinaryProtocol(buffer);
        ThriftCodec.writeMessageBegin(out, method, 7);
        ThriftCodec.writeArgs(out, method, Map.of("model", Map.of("id", 1, "name", "n")));
        out.writeMessageEnd();

        TBinaryProtocol in = new TBinaryProtocol(readOnly(buffer));
        TMessage message = in.readMessageBegin();
        assertEquals("modelProcess", message.name);
        assertEquals(TMessageType.CALL, message.type);
        assertEquals(7, message.seqid);
        in.readStructBegin();
        TField outer = in.readFieldBegin();
        assertEquals(1, outer.id);
        assertEquals(TType.STRUCT, outer.type);
        in.readStructBegin();
        TField id = in.readFieldBegin();
        assertEquals(1, id.id);
        assertEquals(TType.I32, id.type);
        assertEquals(1, in.readI32());
        in.readFieldEnd();
        TField name = in.readFieldBegin();
        assertEquals(2, name.id);
        assertEquals(TType.STRING, name.type);
        assertEquals("n", in.readString());
        in.readFieldEnd();
        assertEquals(TType.STOP, in.readFieldBegin().type);
        in.readStructEnd();
        in.readFieldEnd();
        assertEquals(TType.STOP, in.readFieldBegin().type);
        in.readStructEnd();
        in.readMessageEnd();
    }

    @Test
    void resultDecodingCoversSuccessServiceExceptionAndApplicationException() throws Exception {
        MethodSpec sayHello = MethodSpec.builder("sayHello")
                .arg((short) 1, "username", primitive(ThriftType.STRING))
                .returns(primitive(ThriftType.STRING))
                .build();

        TMemoryBuffer buffer = new TMemoryBuffer(256);
        TBinaryProtocol out = new TBinaryProtocol(buffer);
        out.writeMessageBegin(new TMessage("sayHello", TMessageType.REPLY, 9));
        out.writeStructBegin(new TStruct("sayHello_result"));
        out.writeFieldBegin(new TField("success", TType.STRING, (short) 0));
        out.writeString("hi");
        out.writeFieldEnd();
        out.writeFieldStop();
        out.writeStructEnd();
        out.writeMessageEnd();
        assertEquals("hi", ThriftCodec.readResult(new TBinaryProtocol(readOnly(buffer)), sayHello, 9));
        assertThrows(TApplicationException.class,
                () -> ThriftCodec.readResult(new TBinaryProtocol(readOnly(buffer)), sayHello, 8));

        TypeSpec errorStruct = TypeSpec.struct("ErrorEx",
                TypeSpec.field((short) 1, "message", primitive(ThriftType.STRING)));
        MethodSpec fail = MethodSpec.builder("fail")
                .throwsEx((short) 1, "e", errorStruct)
                .build();
        TMemoryBuffer failure = new TMemoryBuffer(256);
        TBinaryProtocol failureOut = new TBinaryProtocol(failure);
        failureOut.writeMessageBegin(new TMessage("fail", TMessageType.REPLY, 3));
        failureOut.writeStructBegin(new TStruct("fail_result"));
        failureOut.writeFieldBegin(new TField("e", TType.STRUCT, (short) 1));
        failureOut.writeStructBegin(new TStruct("ErrorEx"));
        failureOut.writeFieldBegin(new TField("message", TType.STRING, (short) 1));
        failureOut.writeString("boom");
        failureOut.writeFieldEnd();
        failureOut.writeFieldStop();
        failureOut.writeStructEnd();
        failureOut.writeFieldEnd();
        failureOut.writeFieldStop();
        failureOut.writeStructEnd();
        failureOut.writeMessageEnd();
        ThriftServiceException serviceException = assertThrows(ThriftServiceException.class,
                () -> ThriftCodec.readResult(new TBinaryProtocol(readOnly(failure)), fail, 3));
        assertEquals(Map.of("message", "boom"), serviceException.value());

        TMemoryBuffer appError = new TMemoryBuffer(256);
        TBinaryProtocol appOut = new TBinaryProtocol(appError);
        appOut.writeMessageBegin(new TMessage("sayHello", TMessageType.EXCEPTION, 4));
        new TApplicationException(TApplicationException.INTERNAL_ERROR, "oops").write(appOut);
        appOut.writeMessageEnd();
        TApplicationException applicationException = assertThrows(TApplicationException.class,
                () -> ThriftCodec.readResult(new TBinaryProtocol(readOnly(appError)), sayHello, 4));
        assertTrue(applicationException.getMessage().contains("oops"));
    }

    @Test
    void jsonLikeValuesAreCoerced() throws Exception {
        assertEquals(42, roundTrip(primitive(ThriftType.I32), "42"));
        assertEquals("42", roundTrip(primitive(ThriftType.STRING), 42));
        assertEquals(1.5d, roundTrip(primitive(ThriftType.DOUBLE), "1.5"));
        assertEquals(true, roundTrip(primitive(ThriftType.BOOL), 1));
        assertThrows(IllegalArgumentException.class,
                () -> ThriftCodec.writeValue(new TBinaryProtocol(new TMemoryBuffer(16)),
                        primitive(ThriftType.I32), "not-a-number"));
    }

    private static TypeSpec model() {
        return TypeSpec.struct("Model",
                TypeSpec.field((short) 1, "id", primitive(ThriftType.I32)),
                TypeSpec.optionalField((short) 2, "name", primitive(ThriftType.STRING)));
    }
}
