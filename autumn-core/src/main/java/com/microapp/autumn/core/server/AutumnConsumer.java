package com.microapp.autumn.core.server;

import java.util.Objects;

import org.apache.thrift.TServiceClient;

import com.microapp.autumn.api.Discovery;
import com.microapp.autumn.api.config.ReferenceConfig;
import com.microapp.autumn.api.util.SpiUtil;
import com.microapp.autumn.core.pool.AutumnPool;
import com.microapp.autumn.core.pool.impl.ConcurrentBagEntry;

/**
 * @author: baoxin.zhao
 * @date: 2024/10/31
 */
public class AutumnConsumer {
    private volatile static AutumnConsumer singleton = null;

    public static AutumnConsumer provider() {
        if (singleton == null) {
            synchronized (AutumnConsumer.class) {
                if (singleton == null) {
                    singleton = new AutumnConsumer();
                    return singleton;
                }
            }
        }
        return singleton;
    }

    public <T extends TServiceClient> void reference(ReferenceConfig<T> referenceConfig) {
        AutumnPool.getInstance().referenceConfig(referenceConfig);
        Discovery discovery = SpiUtil.load(Discovery.class);
        if (Objects.nonNull(discovery)) {
            discovery.watch(referenceConfig.getName());
        }
    }

    public static void requite(ConcurrentBagEntry bagEntry) {
        AutumnPool.getInstance().requite(bagEntry);
    }


}
