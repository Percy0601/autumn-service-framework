package xyz.wewin.autumn.sample.test;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.apache.thrift.TSerializer;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TJSONProtocol;
import org.apache.thrift.protocol.TMultiplexedProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TSimpleJSONProtocol;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.junit.jupiter.api.Test;

import xyz.wewin.autumn.api.config.ReferenceConfig;
import xyz.wewin.autumn.api.enums.RegistryTypeEnum;
import xyz.wewin.autumn.core.AutumnBootstrap;
import xyz.wewin.autumn.core.pool.AutumnPool;
import xyz.wewin.autumn.core.pool.impl.ConcurrentBagEntry;
import xyz.wewin.autumn.core.server.AutumnConsumer;
import xyz.wewin.autumn.sample.api.SomeService;
import xyz.wewin.autumn.sample.api.User;

import lombok.extern.slf4j.Slf4j;

/**
 * @author: baoxin.zhao
 * @date: 2024/10/8
 */
@Slf4j
public class TrainingConsumerTest {

    @Test
    @org.junit.jupiter.api.Disabled("interactive demo: needs a running provider and blocks on System.in")
    void testConsumer() {

        AutumnBootstrap.getInstance().serve();

        AutumnConsumer consumer = AutumnConsumer.provider();
        ReferenceConfig<SomeService.Client> referenceConfig = new ReferenceConfig<>();
        referenceConfig.setName("training-a");
        referenceConfig.setInterfaceClass(SomeService.Client.class);
        referenceConfig.setPoolTimeout(10000L);
        referenceConfig.setRegistryTypeEnum(RegistryTypeEnum.MULTICAST);
        referenceConfig.setSocketTimeout(3000L);
        consumer.reference(referenceConfig);
        Runnable r = () -> {
            ConcurrentBagEntry ce = null;
            try {
                ce = AutumnPool.getInstance().borrow(referenceConfig.getName());
                String msg = UUID.randomUUID().toString();
                TTransport transport = ce.getEntry();
                TProtocol protocol = new TBinaryProtocol(transport);
                TMultiplexedProtocol multiplexedProtocol = new TMultiplexedProtocol(protocol, SomeService.Iface.class.getName());
                SomeService.Iface client = new SomeService.Client(multiplexedProtocol);

                String echoResult = client.echo(msg);
                if(!echoResult.equals("Hello, ".concat(msg))) {
                    log.info("===========echoResult fail: {}", echoResult);
                } else {
                    log.info("===========echoResult success: {}, content:{}", ce.getId(), echoResult);
                }
            } catch (TException e) {
                log.warn("===========echoResult exception: {}, id:{}, state:{}", e.getMessage(), ce.getId(), ce.getState());
            } finally {
                AutumnPool.getInstance().requite(ce);
            }
        };

        ExecutorService es = Executors.newFixedThreadPool(10);
        int i = 0;
        for(; i < 10000; i++) {
             es.submit(r);
             if(i % 2000 == 0) {
                 try {
                     Thread.sleep(100L);
                 } catch (InterruptedException e) {
                     throw new RuntimeException(e);
                 }
             }

        }

        try {
            System.in.read();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        log.info("execute over~{}", i);
    }

    @Test
    void testTSerializer() {
        try {
            // libthrift 0.22 removed read support on TSimpleJSONProtocol; use binary protocol
            // to verify a real serialize -> deserialize round-trip.
            TSerializer serializer = new TSerializer(new TBinaryProtocol.Factory());

            User user = new User();
            user.setUserId(1);
            user.setUsername("Hello");
            byte[] bytes = serializer.serialize(user);

            log.info("==================, {}", new String(bytes));

            TDeserializer deserializer = new TDeserializer(new TBinaryProtocol.Factory());
            User user2 = new User();
            deserializer.deserialize(user2, bytes);
            log.info("==================, user2:{}", user2);
        } catch (TTransportException e) {
            throw new RuntimeException(e);
        } catch (TException e) {
            throw new RuntimeException(e);
        }
    }


}
