package xyz.wewin.autumn.protocol.generic;

/**
 * A declared Thrift business exception (result struct field id &gt; 0), as opposed to a
 * framework-level {@code TApplicationException} (message type EXCEPTION).
 *
 * <p>The decoded exception struct is exposed as a plain Map/List tree so a gateway can
 * serialize it straight to JSON.</p>
 */
public class ThriftServiceException extends RuntimeException {

    private final short fieldId;
    private final String fieldName;
    private final Object value;

    public ThriftServiceException(short fieldId, String fieldName, Object value) {
        super("thrift service exception: " + fieldName + "(id=" + fieldId + ") " + value);
        this.fieldId = fieldId;
        this.fieldName = fieldName;
        this.value = value;
    }

    public short fieldId() {
        return fieldId;
    }

    public String fieldName() {
        return fieldName;
    }

    public Object value() {
        return value;
    }
}
