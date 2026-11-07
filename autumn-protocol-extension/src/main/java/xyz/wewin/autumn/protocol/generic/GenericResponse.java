package xyz.wewin.autumn.protocol.generic;

import java.util.LinkedHashMap;
import java.util.Map;

/** JSON-friendly outcome of a generic invocation; never throws transport/schema errors at the gateway edge. */
public final class GenericResponse {

    public static final String ERROR_BAD_REQUEST = "BAD_REQUEST";
    public static final String ERROR_APPLICATION = "APPLICATION";
    public static final String ERROR_SERVICE = "SERVICE";
    public static final String ERROR_TRANSPORT = "TRANSPORT";

    private final boolean success;
    private final Object value;
    private final String errorType;
    private final String errorMessage;
    private final Object errorValue;
    private final long costMillis;

    private GenericResponse(boolean success, Object value, String errorType,
                            String errorMessage, Object errorValue, long costMillis) {
        this.success = success;
        this.value = value;
        this.errorType = errorType;
        this.errorMessage = errorMessage;
        this.errorValue = errorValue;
        this.costMillis = costMillis;
    }

    public static GenericResponse success(Object value, long costMillis) {
        return new GenericResponse(true, value, null, null, null, costMillis);
    }

    public static GenericResponse failure(String errorType, String message, Object errorValue, long costMillis) {
        return new GenericResponse(false, null, errorType, message, errorValue, costMillis);
    }

    public boolean isSuccess() {
        return success;
    }

    public Object getValue() {
        return value;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Object getErrorValue() {
        return errorValue;
    }

    public long getCostMillis() {
        return costMillis;
    }

    /** Ready-to-serialize shape for an HTTP gateway response. */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", success);
        map.put("costMillis", costMillis);
        if (success) {
            map.put("data", value);
        } else {
            map.put("errorType", errorType);
            map.put("message", errorMessage);
            map.put("error", errorValue);
        }
        return map;
    }
}
