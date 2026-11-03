package com.microapp.autumn.test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

import com.microapp.autumn.api.config.ServiceConfig;
import com.microapp.autumn.api.extension.AutumnServiceAssembler;

import lombok.extern.slf4j.Slf4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * integration test for JSR-269 code generation chain:
 * compile sample sources with @Export / @Reference, assert generated
 * _XxxServiceRegister / _XxxProxy classes and SPI services file exist and work.
 *
 * @author: baoxin.zhao
 * @date: 2026/11/3
 */

@Slf4j
public class CompilerTest {

    private static final String THRIFT_IFACE_SRC = """
            package com.sample.api;

            public class SomeService {
                public interface Iface {
                    String echo(String msg) throws org.apache.thrift.TException;
                    int addUser(com.sample.api.User user) throws org.apache.thrift.TException;
                }
                public static class Processor<I extends Iface> implements org.apache.thrift.TProcessor {
                    private final I iface;
                    public Processor(I iface) { this.iface = iface; }
                    @Override
                    public void process(org.apache.thrift.protocol.TProtocol in, org.apache.thrift.protocol.TProtocol out) throws org.apache.thrift.TException { }
                    public I getIface() { return iface; }
                }
                public static class Client extends org.apache.thrift.TServiceClient implements Iface {
                    public Client(org.apache.thrift.protocol.TProtocol prot) { super(prot); }
                    @Override
                    public String echo(String msg) throws org.apache.thrift.TException { return null; }
                    @Override
                    public int addUser(com.sample.api.User user) throws org.apache.thrift.TException { return 0; }
                }
            }
            """;

    private static final String USER_SRC = """
            package com.sample.api;

            public class User {
                private int userId;
                public int getUserId() { return userId; }
                public void setUserId(int userId) { this.userId = userId; }
            }
            """;

    private static final String EXPORT_IMPL_SRC = """
            package com.sample.service;

            import com.microapp.autumn.api.annotation.Export;
            import com.sample.api.SomeService;

            @Export
            public class SomeServiceImpl implements SomeService.Iface {
                @Override
                public String echo(String msg) throws org.apache.thrift.TException { return "Hello, " + msg; }
                @Override
                public int addUser(com.sample.api.User user) throws org.apache.thrift.TException { return 1; }
            }
            """;

    private static final String REFERENCE_CONSUMER_SRC = """
            package com.sample.consumer;

            import com.microapp.autumn.api.annotation.Reference;
            import com.sample.api.SomeService;

            public class SomeConsumer {
                @Reference(refer = "training-b")
                private SomeService.Iface someService;
            }
            """;

    @Test
    void testGenerateCode() throws Exception {
        Path workDir = Files.createTempDirectory("autumn-compiler-test");
        Path srcDir = workDir.resolve("src");
        Path outDir = workDir.resolve("out");
        Files.createDirectories(srcDir);
        Files.createDirectories(outDir);

        writeSource(srcDir, "com/sample/api/SomeService.java", THRIFT_IFACE_SRC);
        writeSource(srcDir, "com/sample/api/User.java", USER_SRC);
        writeSource(srcDir, "com/sample/service/SomeServiceImpl.java", EXPORT_IMPL_SRC);
        writeSource(srcDir, "com/sample/consumer/SomeConsumer.java", REFERENCE_CONSUMER_SRC);

        String classpath = buildClasspath();
        log.info("test classpath:{}", classpath);

        // invoke javac CLI directly (same as maven annotationProcessorPaths): reliable processor
        // discovery via -processorpath, no JavaCompiler API quirks under JDK 25
        String javac = Path.of(System.getProperty("java.home"), "bin", "javac").toString();
        List<String> cmd = new ArrayList<>();
        cmd.add(javac);
        cmd.addAll(Arrays.asList(
                "-classpath", classpath,
                "-processorpath", classpath,
                "-proc:full",
                "-d", outDir.toString(),
                srcDir.resolve("com/sample/api/SomeService.java").toString(),
                srcDir.resolve("com/sample/api/User.java").toString(),
                srcDir.resolve("com/sample/service/SomeServiceImpl.java").toString(),
                srcDir.resolve("com/sample/consumer/SomeConsumer.java").toString()));
        Process javacProcess = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String javacOutput = new String(javacProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = javacProcess.waitFor();
        if (exitCode != 0) {
            log.error("javac output:{}", javacOutput);
        }
        assertTrue(exitCode == 0, "sample source compile should succeed: " + javacOutput);

        // 1. export register class generated
        Path registerClass = outDir.resolve("com/sample/service/_SomeServiceImplServiceRegister.class");
        assertTrue(Files.exists(registerClass), "generated _SomeServiceImplServiceRegister.class should exist");

        // 2. reference proxy class generated
        Path proxyClass = outDir.resolve("com/sample/api/_SomeServiceProxy.class");
        assertTrue(Files.exists(proxyClass), "generated _SomeServiceProxy.class should exist");

        // 3. SPI services file generated
        Path servicesFile = outDir.resolve("META-INF/services/com.microapp.autumn.api.extension.AutumnServiceAssembler");
        assertTrue(Files.exists(servicesFile), "SPI services file should exist");
        String servicesContent = Files.readString(servicesFile, StandardCharsets.UTF_8);
        log.info("services content:{}", servicesContent);
        assertTrue(servicesContent.contains("com.sample.service._SomeServiceImplServiceRegister"), "services file should register generated assembler");

        // 4. generated assembler works without reflection on thrift classes
        ClassLoader loader = new java.net.URLClassLoader(new java.net.URL[]{outDir.toUri().toURL()}, getClass().getClassLoader());
        Class<?> registerClazz = Class.forName("com.sample.service._SomeServiceImplServiceRegister", true, loader);
        Constructor<?> ctor = registerClazz.getDeclaredConstructor();
        Object register = ctor.newInstance();
        assertTrue(register instanceof AutumnServiceAssembler, "generated class should implement AutumnServiceAssembler");
        AutumnServiceAssembler assembler = (AutumnServiceAssembler) register;

        Class<?> implClazz = Class.forName("com.sample.service.SomeServiceImpl", true, loader);
        Object impl = implClazz.getDeclaredConstructor().newInstance();
        assertTrue(assembler.supports(implClazz), "assembler should support the impl class");
        assertTrue(!assembler.supports(Object.class), "assembler should not support unrelated class");

        ServiceConfig<?> serviceConfig = assembler.build(impl);
        assertNotNull(serviceConfig, "assembler build should return ServiceConfig");
        assertEquals("com.sample.api.SomeService$Iface", serviceConfig.getInterfaceClass().getName(),
                "ServiceConfig interfaceClass should be the thrift Iface interface");
        assertNotNull(serviceConfig.getRef(), "ServiceConfig ref should be a TProcessor");
    }

    /**
     * collect required jars for javac by code source location of key classes:
     * libthrift / autumn-api / autumn-core / freemarker / autumn-compiler(processor+templates).
     * surefire forks with a manifest-only booter jar, so java.class.path is not usable directly.
     */
    private static String buildClasspath() throws Exception {
        java.util.LinkedHashSet<String> entries = new java.util.LinkedHashSet<>();
        String[] classes = {
                "org.apache.thrift.TException",
                "org.apache.thrift.protocol.TProtocol",
                "org.apache.thrift.TServiceClient",
                "com.microapp.autumn.api.annotation.Export",
                "com.microapp.autumn.api.annotation.Reference",
                "com.microapp.autumn.api.config.ServiceConfig",
                "com.microapp.autumn.api.extension.AttachableProcessor",
                "com.microapp.autumn.api.extension.AutumnServiceAssembler",
                "com.microapp.autumn.api.util.AutumnException",
                "com.microapp.autumn.core.pool.AutumnPool",
                "com.microapp.autumn.core.pool.impl.ConcurrentBagEntry",
                "freemarker.template.Configuration",
                "org.slf4j.LoggerFactory",
                "com.microapp.autumn.compiler.ExportServiceProcessor"
        };
        for (String name : classes) {
            Class<?> c = Class.forName(name);
            java.net.URI loc = c.getProtectionDomain().getCodeSource().getLocation().toURI();
            entries.add(new File(loc).getPath());
        }
        return String.join(File.pathSeparator, entries);
    }

    private void writeSource(Path srcDir, String relative, String content) throws IOException {
        Path file = srcDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
