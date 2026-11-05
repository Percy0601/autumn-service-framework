package com.microapp.autumn.core.test.util;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import lombok.extern.slf4j.Slf4j;

/**
 * @author: baoxin.zhao
 * @date: 2024/10/25
 */

@Slf4j
public class ThreadUtilTest {


    /**
     * <pre>
     * 23:09:14.428 [pool-1-thread-1] INFO autumn.core.test.util.ThreadUtilTest -- begin print: 0
     * 23:09:14.945 [pool-1-thread-1] INFO autumn.core.test.util.ThreadUtilTest -- begin print: 1
     * 23:09:15.430 [pool-1-thread-1] INFO autumn.core.test.util.ThreadUtilTest -- other print: 0
     * 23:09:15.537 [pool-1-thread-1] INFO autumn.core.test.util.ThreadUtilTest -- other print: 1
     * 23:09:15.645 [pool-1-thread-1] INFO autumn.core.test.util.ThreadUtilTest -- other print: 2
     * 23:09:15.748 [pool-1-thread-1] INFO autumn.core.test.util.ThreadUtilTest -- other print: 3
     * 23:09:15.859 [pool-1-thread-1] INFO autumn.core.test.util.ThreadUtilTest -- other print: 4
     * 23:09:15.966 [pool-1-thread-1] INFO autumn.core.test.util.ThreadUtilTest -- other print: 5
     * 23:09:16.077 [pool-1-thread-1] INFO autumn.core.test.util.ThreadUtilTest -- other print: 6
     * 23:09:16.185 [pool-1-thread-1] INFO autumn.core.test.util.ThreadUtilTest -- other print: 7
     * 23:09:16.295 [pool-1-thread-1] INFO autumn.core.test.util.ThreadUtilTest -- other print: 8
     * 23:09:16.406 [pool-1-thread-1] INFO autumn.core.test.util.ThreadUtilTest -- other print: 9
     * 23:09:17.434 [pool-1-thread-1] INFO autumn.core.test.util.ThreadUtilTest -- other print: 0
     * 23:09:17.545 [pool-1-thread-1] INFO autumn.core.test.util.ThreadUtilTest -- other print: 1
     * 23:09:17.653 [pool-1-thread-1] INFO autumn.core.test.util.ThreadUtilTest -- other print: 2
     * 23:09:17.761 [pool-1-thread-1] INFO autumn.core.test.util.ThreadUtilTest -- other print: 3
     * 23:09:17.868 [pool-1-thread-1] INFO autumn.core.test.util.ThreadUtilTest -- other print: 4
     * 23:09:17.977 [pool-1-thread-1] INFO autumn.core.test.util.ThreadUtilTest -- other print: 5
     * 23:09:18.085 [pool-1-thread-1] INFO autumn.core.test.util.ThreadUtilTest -- other print: 6
     * 23:09:18.196 [pool-1-thread-1] INFO autumn.core.test.util.ThreadUtilTest -- other print: 7
     * 23:09:18.305 [pool-1-thread-1] INFO autumn.core.test.util.ThreadUtilTest -- other print: 8
     * </pre>
     */
    @Test
    void test() {
        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

        Runnable callable = () -> {
            for(int i = 0; i < 10; i++) {
                log.info("begin print: {}", i);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        Runnable other = () -> {
            for(int i = 0; i < 10; i++) {
                log.info("other print: {}", i);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        ScheduledFuture<?> future1 = scheduledExecutorService.scheduleAtFixedRate(callable, 0, 3, TimeUnit.SECONDS);
        //scheduledExecutorService.scheduleAtFixedRate(other, 0, 3, TimeUnit.SECONDS);
//        try {
//            future1.get(1, TimeUnit.SECONDS);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } catch (ExecutionException e) {
//            throw new RuntimeException(e);
//        } catch (TimeoutException e) {
//            future1.cancel(true);
//        }
        try {
            System.in.read();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }



    }


}
