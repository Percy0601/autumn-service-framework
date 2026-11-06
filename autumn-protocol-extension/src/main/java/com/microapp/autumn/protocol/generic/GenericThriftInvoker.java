package com.microapp.autumn.protocol.generic;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TMultiplexedProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.layered.TFramedTransport;

import com.microapp.autumn.api.extension.AttachableBinaryProtocol;

/**
 * Gateway-facing facade: resolve a service descriptor, open a framed binary Thrift connection,
 * perform one generic invocation and return a JSON-friendly {@link GenericResponse}.
 *
 * <p>Attachments are injected explicitly into the protocol (no ThreadLocal), so this is safe
 * on WebFlux event loops as long as the blocking call is scheduled off the loop thread —
 * use {@link #invokeAsync(GenericRequest, Executor)} with a bounded elastic scheduler.</p>
 */
public final class GenericThriftInvoker {

    private final GenericThriftClient client = new GenericThriftClient();
    private final ServiceRegistry registry;

    public GenericThriftInvoker() {
        this(new ServiceRegistry());
    }

    public GenericThriftInvoker(ServiceRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public ServiceRegistry registry() {
        return registry;
    }

    public GenericResponse invoke(GenericRequest request) {
        long start = System.nanoTime();
        TTransport transport = null;
        try {
            Objects.requireNonNull(request, "request");
            ServiceSpec spec = request.getDescriptor() != null
                    ? request.getDescriptor()
                    : registry.require(request.getService());
            MethodSpec method = spec.requireMethod(request.getMethod());
            if (request.getPort() <= 0) {
                throw new IllegalArgumentException("port must be positive");
            }

            TSocket socket = new TSocket(request.getHost(), request.getPort());
            socket.setConnectTimeout(request.getConnectTimeout());
            socket.setTimeout(request.getSocketTimeout());
            transport = request.isFramed() ? new TFramedTransport(socket) : socket;
            transport.open();

            TProtocol base;
            if (request.isAttachmentsEnabled()) {
                AttachableBinaryProtocol attachable = new AttachableBinaryProtocol(transport);
                attachable.setExplicitAttachment(request.getAttachments());
                base = attachable;
            } else {
                base = new TBinaryProtocol(transport);
            }
            String multiplexedName = null;
            if (request.isMultiplexed()) {
                multiplexedName = request.getMultiplexedName() != null
                        ? request.getMultiplexedName() : spec.name();
            }
            TProtocol protocol = multiplexedName == null
                    ? base : new TMultiplexedProtocol(base, multiplexedName);

            Object value = client.invoke(protocol, method, request.getArguments());
            return GenericResponse.success(value, elapsed(start));
        } catch (TApplicationException e) {
            return GenericResponse.failure(GenericResponse.ERROR_APPLICATION, e.getMessage(), null, elapsed(start));
        } catch (ThriftServiceException e) {
            return GenericResponse.failure(GenericResponse.ERROR_SERVICE, e.getMessage(), e.value(), elapsed(start));
        } catch (IllegalArgumentException e) {
            return GenericResponse.failure(GenericResponse.ERROR_BAD_REQUEST, e.getMessage(), null, elapsed(start));
        } catch (Exception e) {
            return GenericResponse.failure(GenericResponse.ERROR_TRANSPORT, e.toString(), null, elapsed(start));
        } finally {
            if (transport != null) {
                try {
                    transport.close();
                } catch (Exception ignored) {
                    // best effort close, never mask the invocation outcome
                }
            }
        }
    }

    public CompletableFuture<GenericResponse> invokeAsync(GenericRequest request) {
        return CompletableFuture.supplyAsync(() -> invoke(request));
    }

    public CompletableFuture<GenericResponse> invokeAsync(GenericRequest request, Executor executor) {
        return CompletableFuture.supplyAsync(() -> invoke(request), Objects.requireNonNull(executor, "executor"));
    }

    private static long elapsed(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
