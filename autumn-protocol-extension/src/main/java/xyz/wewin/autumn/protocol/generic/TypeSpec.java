package xyz.wewin.autumn.protocol.generic;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, runtime description of a Thrift type (the "schema tree" a generic call is driven by).
 *
 * <p>A {@code TypeSpec} describes a value; the same object can additionally carry field metadata
 * ({@link #fieldId()}, {@link #name()}, {@link #optional()}) when it is used as a struct field,
 * a method argument or a declared exception. No generated stub / IDL compilation is involved:
 * the tree can be built in code, parsed from IDL at runtime, or shipped from a config center.</p>
 */
public final class TypeSpec {

    private final ThriftType type;
    private final String name;
    private final short fieldId;
    private final boolean optional;
    private final List<TypeSpec> fields;
    private final TypeSpec keyType;
    private final TypeSpec valueType;
    private final TypeSpec elementType;

    private TypeSpec(ThriftType type, String name, short fieldId, boolean optional,
                     List<TypeSpec> fields, TypeSpec keyType, TypeSpec valueType, TypeSpec elementType) {
        this.type = Objects.requireNonNull(type, "type");
        this.name = name == null ? "" : name;
        this.fieldId = fieldId;
        this.optional = optional;
        this.fields = fields == null ? List.of() : List.copyOf(fields);
        this.keyType = keyType;
        this.valueType = valueType;
        this.elementType = elementType;
    }

    public static TypeSpec of(ThriftType type) {
        if (!type.isPrimitive() && type != ThriftType.VOID) {
            throw new IllegalArgumentException("composite type needs children: " + type);
        }
        return new TypeSpec(type, null, (short) 0, false, null, null, null, null);
    }

    public static TypeSpec struct(String name, TypeSpec... fields) {
        return new TypeSpec(ThriftType.STRUCT, name, (short) 0, false, Arrays.asList(fields), null, null, null);
    }

    public static TypeSpec list(TypeSpec element) {
        return new TypeSpec(ThriftType.LIST, null, (short) 0, false, null, null, null,
                Objects.requireNonNull(element, "element"));
    }

    public static TypeSpec set(TypeSpec element) {
        return new TypeSpec(ThriftType.SET, null, (short) 0, false, null, null, null,
                Objects.requireNonNull(element, "element"));
    }

    public static TypeSpec map(TypeSpec key, TypeSpec value) {
        return new TypeSpec(ThriftType.MAP, null, (short) 0, false, null,
                Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"), null);
    }

    public static TypeSpec field(short id, String name, TypeSpec base) {
        return base.asField(id, name, false);
    }

    public static TypeSpec optionalField(short id, String name, TypeSpec base) {
        return base.asField(id, name, true);
    }

    /** Return a copy carrying struct/method field metadata, leaving the value shape untouched. */
    public TypeSpec asField(short id, String fieldName) {
        return asField(id, fieldName, false);
    }

    public TypeSpec asField(short id, String fieldName, boolean isOptional) {
        return new TypeSpec(type, fieldName, id, isOptional, fields, keyType, valueType, elementType);
    }

    public TypeSpec fieldById(short id) {
        for (TypeSpec field : fields) {
            if (field.fieldId == id) {
                return field;
            }
        }
        return null;
    }

    public TypeSpec fieldByName(String fieldName) {
        if (fieldName == null) {
            return null;
        }
        for (TypeSpec field : fields) {
            if (fieldName.equals(field.name)) {
                return field;
            }
        }
        return null;
    }

    public ThriftType type() {
        return type;
    }

    public byte wireType() {
        return type.wireType();
    }

    public String name() {
        return name;
    }

    public short fieldId() {
        return fieldId;
    }

    /**
     * Whether this field may be omitted when writing. Covers both IDL {@code optional}
     * fields and unmodified fields (default requiredness); {@code false} means required.
     */
    public boolean optional() {
        return optional;
    }

    public List<TypeSpec> fields() {
        return fields;
    }

    public TypeSpec keyType() {
        return keyType;
    }

    public TypeSpec valueType() {
        return valueType;
    }

    public TypeSpec elementType() {
        return elementType;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(type.name().toLowerCase());
        if (!name.isEmpty()) {
            sb.append('(').append(name).append('#').append(fieldId).append(')');
        }
        switch (type) {
            case STRUCT -> sb.append(fields);
            case LIST, SET -> sb.append('<').append(elementType).append('>');
            case MAP -> sb.append('<').append(keyType).append(',').append(valueType).append('>');
            default -> {
            }
        }
        return sb.toString();
    }
}
