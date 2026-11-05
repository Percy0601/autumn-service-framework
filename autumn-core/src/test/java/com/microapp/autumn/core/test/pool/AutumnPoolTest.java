package com.microapp.autumn.core.test.pool;

import java.util.concurrent.ConcurrentLinkedQueue;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;


@Slf4j
public class AutumnPoolTest {

    @Test
    void test() {
        String path = this.getClass()
                .getClassLoader()
                .getResource("")
                .toString();
        log.info("==================, path:{}", path);
    }

    @Test
    void test2() {
        ConcurrentLinkedQueue<String> sharedList = new ConcurrentLinkedQueue<>();
        sharedList.add("1");
        sharedList.add("2");
        sharedList.add("3");
        sharedList.add("4");

        String a = sharedList.poll();
        log.info("============poll, value:{}", a);
        log.info("============poll, contains:{}", sharedList.contains(a));
    }

}
