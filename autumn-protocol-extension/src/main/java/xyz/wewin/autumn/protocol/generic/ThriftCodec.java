package xyz.wewin.autumn.protocol.generic;

import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
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

/**
 * Stub-free Thrift wire codec driven entirely by {@link TypeSpec} descriptors.
 *
 * <p>Wire layout handled here (binary/compact agnostic because it only uses TProtocol primitives):
 * <pre>
 *   CALL  : TMessage(method, CALL, seqid) + struct{ field(id,type) = value ... } + STOP
 *   REPLY : TMessage(method, REPLY, seqid) + struct{ 0 = success, 1..n = declared exceptions } + STOP
 *   EXCEPTION : TMessage + TApplicationException
 * </pre>
 * Unlike the original generic-thrift-client, reads are keyed by <b>field id</b> (not by
 * descriptor order), composite arguments are wrapped in a proper TField, and unknown fields
 * are skipped with {@link TProtocolUtil#skip} instead of corrupting the stream.</p>
 */
public final class ThriftCodec {

    private ThriftCodec() {
    }

    public static void writeMessageBegin(TProtocol protocol, MethodSpec method, int seqid) throws TException {
        byte type = method.oneway() ? TMessageType.ONEWAY : TMessageType.CALL;
        protocol.writeMessageBegin(new TMessage(method.name(), type, seqid));
    }

    /** Write the {@code method_args} struct. Arguments may be a Map (by name), a List/array (by order), or null. */
    public static void writeArgs(TProtocol protocol, MethodSpec method, Object arguments) throws TException {
        Map<String, Object> byName = null;
        List<Object> byIndex = null;
        if (arguments == null) {
            byName = Map.of();
        } else if (arguments instanceof Map<?, ?> map) {
            byName = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                byName.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        } else if (arguments instanceof List<?> list) {
            byIndex = new ArrayList<>(list);
        } else if (arguments instanceof Object[] array) {
            byIndex = Arrays.asList(array);
        } else {
            throw new IllegalArgumentException("arguments must be a Map, List or array, got "
                    + arguments.getClass().getName());
        }

        protocol.writeStructBegin(new TStruct(method.name() + "_args"));
        List<TypeSpec> fields = method.arguments();
        for (int i = 0; i < fields.size(); i++) {
            TypeSpec field = fields.get(i);
            Object value = byName != null
                    ? byName.get(field.name())
                    : (i < byIndex.size() ? byIndex.get(i) : null);
            if (value == null) {
                if (!field.optional()) {
                    throw new IllegalArgumentException("missing required argument '" + field.name()
                            + "' (id=" + field.fieldId() + ") of " + method.name());
                }
                continue;
            }
            protocol.writeFieldBegin(new TField(field.name(), field.wireType(), field.fieldId()));
            writeValue(protocol, field, value);
            protocol.writeFieldEnd();
        }
        protocol.writeFieldStop();
        protocol.writeStructEnd();
    }

    /** Read the {@code method_result} struct; throws TApplicationException / ThriftServiceException on failure. */
    public static Object readResult(TProtocol protocol, MethodSpec method, int seqid) throws TException {
        TMessage message = protocol.readMessageBegin();
        if (message.type == TMessageType.EXCEPTION) {
            TApplicationException failure = TApplicationException.readFrom(protocol);
            protocol.readMessageEnd();
            throw failure;
        }
        if (message.seqid != seqid) {
            throw new TApplicationException(TApplicationException.BAD_SEQUENCE_ID,
                    method.name() + " failed: out of sequence response");
        }

        Object value = null;
        boolean found = false;
        ThriftServiceException serviceException = null;
        protocol.readStructBegin();
        while (true) {
            TField field = protocol.readFieldBegin();
            if (field.type == TType.STOP) {
                break;
            }
            if (field.id == 0 && method.result().type() != ThriftType.VOID) {
                TypeSpec success = method.result();
                if (field.type != success.wireType()) {
                    TProtocolUtil.skip(protocol, field.type);
                } else {
                    value = readValue(protocol, success);
                    found = true;
                }
            } else {
                TypeSpec exception = method.exceptionById(field.id);
                if (exception == null || field.type != exception.wireType()) {
                    TProtocolUtil.skip(protocol, field.type);
                } else {
                    serviceException = new ThriftServiceException(field.id, exception.name(),
                            readValue(protocol, exception));
                }
            }
            protocol.readFieldEnd();
        }
        protocol.readStructEnd();
        protocol.readMessageEnd();
        if (serviceException != null) {
            throw serviceException;
        }
        return found ? value : null;
    }

    public static void writeValue(TProtocol protocol, TypeSpec spec, Object value) throws TException {
        if (value == null) {
            throw new IllegalArgumentException("null value for " + spec);
        }
        switch (spec.type()) {
            case BOOL -> protocol.writeBool(toBoolean(value));
            case BYTE -> protocol.writeByte(toByte(value));
            case I16 -> protocol.writeI16(toShort(value));
            case I32, ENUM -> protocol.writeI32(toInt(value));
            case I64 -> protocol.writeI64(toLong(value));
            case DOUBLE -> protocol.writeDouble(toDouble(value));
            case STRING -> protocol.writeString(toStringValue(value));
            case BINARY -> protocol.writeBinary(ByteBuffer.wrap(toBytes(value)));
            case STRUCT -> {
                protocol.writeStructBegin(new TStruct(spec.name().isEmpty() ? "struct" : spec.name()));
                Map<String, Object> values = toStructMap(value, spec);
                for (TypeSpec field : spec.fields()) {
                    Object fieldValue = values.get(field.name());
                    if (fieldValue == null) {
                        if (!field.optional()) {
                            throw new IllegalArgumentException("missing required field '" + field.name()
                                    + "' (id=" + field.fieldId() + ") in " + spec.name());
                        }
                        continue;
                    }
                    protocol.writeFieldBegin(new TField(field.name(), field.wireType(), field.fieldId()));
                    writeValue(protocol, field, fieldValue);
                    protocol.writeFieldEnd();
                }
                protocol.writeFieldStop();
                protocol.writeStructEnd();
            }
            case LIST -> {
                List<?> values = toList(value);
                TypeSpec element = spec.elementType();
                protocol.writeListBegin(new TList(element.wireType(), values.size()));
                for (Object item : values) {
                    writeValue(protocol, element, item);
                }
                protocol.writeListEnd();
            }
            case SET -> {
                List<?> values = toList(value);
                TypeSpec element = spec.elementType();
                protocol.writeSetBegin(new TSet(element.wireType(), values.size()));
                for (Object item : values) {
                    writeValue(protocol, element, item);
                }
                protocol.writeSetEnd();
            }
            case MAP -> {
                Map<?, ?> values = toRawMap(value);
                TypeSpec key = spec.keyType();
                TypeSpec mapValue = spec.valueType();
                protocol.writeMapBegin(new TMap(key.wireType(), mapValue.wireType(), values.size()));
                for (Map.Entry<?, ?> entry : values.entrySet()) {
                    writeValue(protocol, key, entry.getKey());
                    writeValue(protocol, mapValue, entry.getValue());
                }
                protocol.writeMapEnd();
            }
            case VOID -> throw new IllegalArgumentException("VOID has no wire value");
        }
    }

    public static Object readValue(TProtocol protocol, TypeSpec spec) throws TException {
        return switch (spec.type()) {
            case BOOL -> protocol.readBool();
            case BYTE -> protocol.readByte();
            case I16 -> protocol.readI16();
            case I32, ENUM -> protocol.readI32();
            case I64 -> protocol.readI64();
            case DOUBLE -> protocol.readDouble();
            case STRING -> protocol.readString();
            case BINARY -> toBytes(protocol.readBinary());
            case STRUCT -> readStruct(protocol, spec);
            case LIST -> readList(protocol, spec.elementType(), false);
            case SET -> readList(protocol, spec.elementType(), true);
            case MAP -> readMap(protocol, spec.keyType(), spec.valueType());
            case VOID -> null;
        };
    }

    private static Object readStruct(TProtocol protocol, TypeSpec spec) throws TException {
        protocol.readStructBegin();
        Map<String, Object> result = new LinkedHashMap<>();
        while (true) {
            TField field = protocol.readFieldBegin();
            if (field.type == TType.STOP) {
                break;
            }
            TypeSpec declared = spec.fieldById(field.id);
            if (declared == null || field.type != declared.wireType()) {
                // forward-compatible: ignore fields added by a newer server / mismatched descriptors
                TProtocolUtil.skip(protocol, field.type);
            } else {
                result.put(declared.name(), readValue(protocol, declared));
            }
            protocol.readFieldEnd();
        }
        protocol.readStructEnd();
        return result;
    }

    private static Object readList(TProtocol protocol, TypeSpec element, boolean isSet) throws TException {
        List<Object> result;
        if (isSet) {
            TSet set = protocol.readSetBegin();
            result = readElements(protocol, element, set.elemType, set.size);
            protocol.readSetEnd();
        } else {
            TList list = protocol.readListBegin();
            result = readElements(protocol, element, list.elemType, list.size);
            protocol.readListEnd();
        }
        return result;
    }

    private static List<Object> readElements(TProtocol protocol, TypeSpec element, byte actual, int size)
            throws TException {
        checkContainerType(element, actual, "element");
        List<Object> result = new ArrayList<>(Math.max(0, size));
        for (int i = 0; i < size; i++) {
            result.add(readValue(protocol, element));
        }
        return result;
    }

    private static Object readMap(TProtocol protocol, TypeSpec key, TypeSpec value) throws TException {
        TMap map = protocol.readMapBegin();
        checkContainerType(key, map.keyType, "key");
        checkContainerType(value, map.valueType, "value");
        Map<Object, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < map.size; i++) {
            result.put(readValue(protocol, key), readValue(protocol, value));
        }
        protocol.readMapEnd();
        return result;
    }

    private static void checkContainerType(TypeSpec expected, byte actual, String role) throws TApplicationException {
        if (expected.wireType() != actual) {
            throw new TApplicationException(TApplicationException.PROTOCOL_ERROR,
                    "descriptor mismatch: " + role + " wire type " + actual + " != " + expected.wireType());
        }
    }

    // ---------- value coercion (JSON-ish values coming from a gateway) ----------

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static byte toByte(Object value) {
        return value instanceof Number n ? n.byteValue() : Byte.parseByte(String.valueOf(value));
    }

    private static short toShort(Object value) {
        return value instanceof Number n ? n.shortValue() : Short.parseShort(String.valueOf(value));
    }

    private static int toInt(Object value) {
        return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
    }

    private static String toStringValue(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (value instanceof ByteBuffer buffer) {
            return new String(toBytes(buffer), StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    private static byte[] toBytes(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        if (value instanceof ByteBuffer buffer) {
            ByteBuffer duplicate = buffer.duplicate();
            byte[] bytes = new byte[duplicate.remaining()];
            duplicate.get(bytes);
            return bytes;
        }
        return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> toStructMap(Object value, TypeSpec spec) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        throw new IllegalArgumentException("struct " + spec.name() + " expects a Map, got "
                + value.getClass().getName());
    }

    private static Map<?, ?> toRawMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalArgumentException("map expects a Map, got " + value.getClass().getName());
    }

    private static List<?> toList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (value instanceof Object[] array) {
            return Arrays.asList(array);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> result = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                result.add(Array.get(value, i));
            }
            return result;
        }
        throw new IllegalArgumentException("list/set expects a Collection or array, got "
                + value.getClass().getName());
    }
}
