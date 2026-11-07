package xyz.wewin.autumn.core.devops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.TServiceClient;
import org.apache.thrift.protocol.TMultiplexedProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.apache.thrift.transport.layered.TFramedTransport;

import xyz.wewin.autumn.api.ControlApi;
import xyz.wewin.autumn.api.config.ApplicationConfig;
import xyz.wewin.autumn.api.config.ProviderConfig;
import xyz.wewin.autumn.api.config.ReferenceConfig;
import xyz.wewin.autumn.api.extension.AttachableBinaryProtocol;
import xyz.wewin.autumn.core.pool.AutumnPool;
import xyz.wewin.autumn.core.server.AutumnProvider;

import lombok.extern.slf4j.Slf4j;

/**
 * 控制面实现：所有方法校验共享 token（control.token 为空时关闭认证），health 反映真实监听状态。
 */
@Slf4j
public class ControlApiImpl implements ControlApi.Iface {

    private static final int CHECK_CONNECT_TIMEOUT_MILLIS = 1000;
    private static final int CHECK_SOCKET_TIMEOUT_MILLIS = 2000;

    @Override
    public String health(String token) throws TException {
        requireToken(token);
        if (!AutumnProvider.provider().isStarted()) {
            return "DOWN: thrift server is not listening";
        }
        return "OK";
    }

    /** 只输出非敏感信息：ApplicationConfig.toString 已排除 token 类字段 */
    @Override
    public String applicationInfo(String token) throws TException {
        requireToken(token);
        return ApplicationConfig.getInstance().toString();
    }

    @Override
    public String providerInfo(String token) throws TException {
        requireToken(token);
        return ProviderConfig.getInstance().toString() + ", metrics=" + AutumnMetrics.summary();
    }

    @Override
    public String referenceConfig(String name, String token) throws TException {
        requireToken(token);
        ReferenceConfig<? extends TServiceClient> config = AutumnPool.getInstance().getReferenceConfig(name);
        if (Objects.isNull(config)) {
            return "reference config not found: " + name;
        }
        return config.toString();
    }

    @Override
    public String check(String ip, int port, String token) throws TException {
        requireToken(token);
        if (Objects.isNull(ip) || port <= 0) {
            return String.valueOf(Boolean.FALSE);
        }
        TSocket tsocket = null;
        TTransport transport = null;
        try {
            tsocket = new TSocket(ip, port);
            tsocket.setConnectTimeout(CHECK_CONNECT_TIMEOUT_MILLIS);
            tsocket.setTimeout(CHECK_SOCKET_TIMEOUT_MILLIS);
            transport = new TFramedTransport(tsocket);
            TProtocol protocol = new AttachableBinaryProtocol(transport);
            transport.open();
            TMultiplexedProtocol multiplexedProtocol = new TMultiplexedProtocol(protocol, ControlApi.Iface.class.getName());
            ControlApi.Iface client = new ControlApi.Client(multiplexedProtocol);
            String result = client.health(localToken());
            return String.valueOf("OK".equals(result));
        } catch (TTransportException e) {
            log.warn("autumn check health connect exception:{}", e.getMessage());
            return String.valueOf(Boolean.FALSE);
        } catch (TException e) {
            log.warn("autumn check health exception:{}", e.getMessage());
            return String.valueOf(Boolean.FALSE);
        } finally {
            if (Objects.nonNull(transport)) {
                try {
                    transport.close();
                } catch (Exception ignored) {
                    // 探测连接清理
                }
            }
            if (Objects.nonNull(tsocket)) {
                try {
                    tsocket.close();
                } catch (Exception ignored) {
                    // 探测连接清理
                }
            }
        }
    }

    /** 同集群共用一个 control.token，探测对端时带上本地凭证 */
    private static String localToken() {
        String configured = ApplicationConfig.getInstance().getControlToken();
        return Objects.isNull(configured) ? "" : configured;
    }

    private static void requireToken(String token) throws TException {
        String expected = ApplicationConfig.getInstance().getControlToken();
        if (Objects.isNull(expected) || expected.isBlank()) {
            return;
        }
        String actual = Objects.isNull(token) ? "" : token;
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
            throw new TApplicationException(TApplicationException.UNSUPPORTED_CLIENT_TYPE,
                    "control api unauthorized");
        }
    }
}
