package xyz.wewin.autumn.protocol.generic;

import java.util.LinkedHashMap;
import java.util.Map;

/** Gateway-facing request: an HTTP call translated into one dynamic Thrift invocation. */
public final class GenericRequest {

    private String host = "127.0.0.1";
    private int port;
    /** Registry key of the {@link ServiceSpec}; also the default multiplexed service name. */
    private String service;
    /** Explicit descriptor (skips the registry lookup when set). */
    private ServiceSpec descriptor;
    private String method;
    /** Map (by field name), List/array (by declaration order) or null for no-arg methods. */
    private Object arguments;
    /** Transparently sent as thrift field-0 attachment; no ThreadLocal is touched. */
    private Map<String, String> attachments = new LinkedHashMap<>();
    private int connectTimeout = 2000;
    private int socketTimeout = 5000;
    private boolean framed = true;
    private boolean multiplexed;
    private String multiplexedName;
    private boolean attachmentsEnabled = true;

    public static GenericRequest of(String host, int port, String service, String method) {
        GenericRequest request = new GenericRequest();
        request.setHost(host).setPort(port).setService(service).setMethod(method);
        return request;
    }

    public String getHost() {
        return host;
    }

    public GenericRequest setHost(String host) {
        this.host = host;
        return this;
    }

    public int getPort() {
        return port;
    }

    public GenericRequest setPort(int port) {
        this.port = port;
        return this;
    }

    public String getService() {
        return service;
    }

    public GenericRequest setService(String service) {
        this.service = service;
        return this;
    }

    public ServiceSpec getDescriptor() {
        return descriptor;
    }

    public GenericRequest setDescriptor(ServiceSpec descriptor) {
        this.descriptor = descriptor;
        return this;
    }

    public String getMethod() {
        return method;
    }

    public GenericRequest setMethod(String method) {
        this.method = method;
        return this;
    }

    public Object getArguments() {
        return arguments;
    }

    public GenericRequest setArguments(Object arguments) {
        this.arguments = arguments;
        return this;
    }

    public Map<String, String> getAttachments() {
        return attachments;
    }

    public GenericRequest setAttachments(Map<String, String> attachments) {
        this.attachments = attachments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attachments);
        return this;
    }

    public GenericRequest attachment(String key, String value) {
        attachments.put(key, value);
        return this;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public GenericRequest setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    public int getSocketTimeout() {
        return socketTimeout;
    }

    public GenericRequest setSocketTimeout(int socketTimeout) {
        this.socketTimeout = socketTimeout;
        return this;
    }

    public boolean isFramed() {
        return framed;
    }

    public GenericRequest setFramed(boolean framed) {
        this.framed = framed;
        return this;
    }

    public boolean isMultiplexed() {
        return multiplexed;
    }

    public GenericRequest setMultiplexed(boolean multiplexed) {
        this.multiplexed = multiplexed;
        return this;
    }

    public String getMultiplexedName() {
        return multiplexedName;
    }

    public GenericRequest setMultiplexedName(String multiplexedName) {
        this.multiplexedName = multiplexedName;
        return this;
    }

    public boolean isAttachmentsEnabled() {
        return attachmentsEnabled;
    }

    /** Disable field-0 injection when talking to a legacy server that must see a pure TBinaryProtocol. */
    public GenericRequest setAttachmentsEnabled(boolean attachmentsEnabled) {
        this.attachmentsEnabled = attachmentsEnabled;
        return this;
    }
}
