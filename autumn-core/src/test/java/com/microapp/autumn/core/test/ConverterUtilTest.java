package com.microapp.autumn.core.test;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.microapp.autumn.api.util.ConverterUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * @author: baoxin.zhao
 * @date: 2024/10/7
 */

@Slf4j
public class ConverterUtilTest {

    @Test
    void test() {
        String url = "registry://request?a=1&b=2";
        Map<String, String> map =  ConverterUtil.getUrlParams(url);
        log.info("==========={}", map);
    }

}
