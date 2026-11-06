package com.microapp.autumn.protocol.generic;

import java.util.Locale;

import org.apache.thrift.protocol.TType;

/**
 * Thrift IDL types supported by the stub-free generic codec.
 *
 * <p>{@link #BINARY} shares the STRING wire type and is read through {@code readBinary()};
 * {@link #ENUM} is encoded as I32; {@link #VOID} has no wire value (used for oneway / void return).</p>
 */
public enum ThriftType {

    VOID(TType.STOP),
    BOOL(TType.BOOL),
    BYTE(TType.BYTE),
    I16(TType.I16),
    I32(TType.I32),
    I64(TType.I64),
    DOUBLE(TType.DOUBLE),
    STRING(TType.STRING),
    BINARY(TType.STRING),
    ENUM(TType.I32),
    STRUCT(TType.STRUCT),
    MAP(TType.MAP),
    SET(TType.SET),
    LIST(TType.LIST);

    private final byte wireType;

    ThriftType(byte wireType) {
        this.wireType = wireType;
    }

    public byte wireType() {
        return wireType;
    }

    public boolean isPrimitive() {
        return switch (this) {
            case BOOL, BYTE, I16, I32, I64, DOUBLE, STRING, BINARY, ENUM -> true;
            default -> false;
        };
    }

    /** Resolve a built-in IDL primitive; returns null for user-defined names and containers. */
    public static ThriftType fromIdl(String token) {
        if (token == null) {
            return null;
        }
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "void" -> VOID;
            case "bool", "boolean" -> BOOL;
            case "byte", "i8" -> BYTE;
            case "i16" -> I16;
            case "i32", "int" -> I32;
            case "i64" -> I64;
            case "double" -> DOUBLE;
            case "string" -> STRING;
            case "binary" -> BINARY;
            default -> null;
        };
    }
}
