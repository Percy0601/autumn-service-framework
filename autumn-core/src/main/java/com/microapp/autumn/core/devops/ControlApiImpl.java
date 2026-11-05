package com.microapp.autumn.core.devops;

import java.util.Objects;

import org.apache.thrift.TException;
import org.apache.thrift.TServiceClient;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TMultiplexedProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.apache.thrift.transport.layered.TFramedTransport;

import com.microapp.autumn.api.ControlApi;
import com.microapp.autumn.api.config.ApplicationConfig;
import com.microapp.autumn.api.config.ProviderConfig;
import com.microapp.autumn.api.config.ReferenceConfig;
import com.microapp.autumn.core.pool.AutumnPool;

import lombok.extern.slf4j.Slf4j;

/**
 * @author: baoxin.zhao
 * @date: 2024/10/28
 */
@Slf4j
public class ControlApiImpl implements ControlApi.Iface {

    private static final int CHECK_CONNECT_TIMEOUT_MILLIS = 1000;
    private static final int CHECK_SOCKET_TIMEOUT_MILLIS = 2000;

    @Override
    public String health() throws TException {
        return "OK";
    }

    /**
     * 只输出非敏感信息：ApplicationConfig.toString 已排除 consulToken / nacosAccessToken
     */
    @Override
    public String applicationInfo() throws TException {
        return ApplicationConfig.getInstance().toString();
    }

    @Override
    public String providerInfo() throws TException {
        return ProviderConfig.getInstance().toString();
    }

    @Override
    public String referenceConfig(String name) throws TException {
        ReferenceConfig<? extends TServiceClient> config = AutumnPool.getInstance().getReferenceConfig(name);
        if (Objects.isNull(config)) {
            return "reference config not found: " + name;
        }
        return config.toString();
    }


    @Override
    public String check(String ip, int port) throws TException{
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
            TProtocol protocol = new TBinaryProtocol(transport);
            transport.open();
            TMultiplexedProtocol multiplexedProtocol = new TMultiplexedProtocol(protocol, ControlApi.Iface.class.getName());
            ControlApi.Iface client = new ControlApi.Client(multiplexedProtocol);
            String result = client.health();
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

}
