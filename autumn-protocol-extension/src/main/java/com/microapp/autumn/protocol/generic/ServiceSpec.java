package com.microapp.autumn.protocol.generic;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime description of a Thrift service.
 *
 * <p>{@link #name()} is also the multiplexed service name used by {@code TMultiplexedProtocol}.
 * A gateway can keep one {@code ServiceSpec} per upstream service and refresh it without
 * recompiling / restarting, which is what makes generic routing "dynamic".</p>
 */
public final class ServiceSpec {

    private final String name;
    private final Map<String, MethodSpec> methods;
    private final Map<String, TypeSpec> types;

    private ServiceSpec(Builder builder) {
        this.name = builder.name;
        this.methods = Map.copyOf(builder.methods);
        this.types = Map.copyOf(builder.types);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public Map<String, MethodSpec> methods() {
        return methods;
    }

    public MethodSpec method(String methodName) {
        return methods.get(methodName);
    }

    public MethodSpec requireMethod(String methodName) {
        MethodSpec spec = methods.get(methodName);
        if (spec == null) {
            throw new IllegalArgumentException("unknown method '" + methodName + "' on service '" + name
                    + "', available: " + methods.keySet());
        }
        return spec;
    }

    /** Named structs / enums declared in the IDL, kept for descriptor introspection. */
    public Map<String, TypeSpec> types() {
        return types;
    }

    @Override
    public String toString() {
        return "ServiceSpec{" + name + ", methods=" + methods.keySet() + '}';
    }

    public static final class Builder {

        private final String name;
        private final Map<String, MethodSpec> methods = new LinkedHashMap<>();
        private final Map<String, TypeSpec> types = new LinkedHashMap<>();

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("service name must not be blank");
            }
        }

        public Builder method(MethodSpec spec) {
            methods.put(spec.name(), spec);
            return this;
        }

        public Builder type(String typeName, TypeSpec spec) {
            types.put(typeName, spec);
            return this;
        }

        public ServiceSpec build() {
            return new ServiceSpec(this);
        }
    }
}
