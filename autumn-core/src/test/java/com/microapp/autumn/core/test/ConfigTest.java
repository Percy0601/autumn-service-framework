package com.microapp.autumn.core.test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import com.microapp.autumn.api.util.CommonUtil;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ConfigTest {
    private String CONFIG_PATH = "/opt/autumn/autumn-config.yml";


    @Test
    void test2() {
        String ipAddress = CommonUtil.getHostIpAddress();
        log.info("============ip:{}", ipAddress);
    }

    @Test
    void test5() {
        String a = "[\"<DataInfo bostype=\"BF0C040E\" op=\"5\"><DataHead><creator>wxuser</creator><createTime>2025-11-27 20:21:35</createTime><CU>00</CU><number>010.SHDLBJKHX</number><name>北京凯宏鑫医药有限责任公司1</name><browseGroup>010</browseGroup><usedStatus>0</usedStatus><version>0</version><customerKind>-1</customerKind><city></city><province>P020</province><region></region><bizRegisterNo>9110229755286342C</bizRegisterNo><invoiceType>1</invoiceType><taxRegisterNo>9110229755286342C</taxRegisterNo><busiLicence>9110229755286342C</busiLicence><address>北京市延庆区康庄镇康顺路9号</address><simpleName></simpleName><receivePhone></receivePhone><receiveEmail></receiveEmail><fzr>耿义明</fzr><khzh>0200011809000026304</khzh><khh>中国工商银行股份有限公司延庆支行</khh><shr></shr><shdh>010-51053429</shdh><kpdh>010-51053429</kpdh><kpdz>北京市延庆区康庄镇康顺路9号</kpdz><effectedStatus>2</effectedStatus></DataHead><DataEntries><entry><customerGroupStandard>customerGroupStandard</customerGroupStandard><customerGroup>010</customerGroup></entry></DataEntries></DataInfo>\"]";

    }

    @Test
    void test3() {
        Path path = null;
        URI uri = null;
        URL url = this.getClass().getClassLoader()
                .getResource("application.properties");
        if(Objects.isNull(url)) {
            uri = new File("/application2.properties").toURI();
        } else {
            try {
                uri = url.toURI();
            } catch (URISyntaxException e) {
                log.info("url to uri exception: ", e);
            }
        }
        path = Path.of(uri);

        try {
            String content = Files.readString(path);
            log.info("================:{}", content);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void test4() {
        Properties properties = CommonUtil.readClasspath("application.properties");
        String value = properties.getProperty("spring.application.name");
        log.info("==============: {}", value);
    }
}
