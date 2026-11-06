package com.microapp.autumn.protocol.generic;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime registry of Thrift service descriptors.
 *
 * <p>Descriptors can be added/replaced at any time (from IDL text, a config center or a
 * registration center), so a gateway picks up new services without regenerating stubs or
 * restarting. Thread-safe and deliberately free of any framework container dependency.</p>
 */
public final class ServiceRegistry {

    private final ConcurrentHashMap<String, ServiceSpec> services = new ConcurrentHashMap<>();

    public void register(ServiceSpec spec) {
        services.put(spec.name(), spec);
    }

    /** Parse IDL text and register every service declared in it. */
    public List<ServiceSpec> registerIdl(String idl) {
        List<ServiceSpec> parsed = IdlParser.parseAll(idl);
        parsed.forEach(this::register);
        return parsed;
    }

    public ServiceSpec get(String name) {
        return services.get(name);
    }

    public ServiceSpec require(String name) {
        ServiceSpec spec = services.get(name);
        if (spec == null) {
            throw new IllegalArgumentException("no service descriptor registered for '" + name
                    + "', registered: " + services.keySet());
        }
        return spec;
    }

    public void remove(String name) {
        services.remove(name);
    }

    public Set<String> names() {
        return Set.copyOf(services.keySet());
    }
}
