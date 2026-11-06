package com.microapp.autumn.sample.boot.provider;

import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.ApplicationContextFacade;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.util.CharsetMapper;
import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.UpgradeProtocol;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.NioEndpoint;
import org.apache.tomcat.util.net.SocketProperties;
import org.apache.tomcat.websocket.server.WsFilter;
import org.apache.tomcat.websocket.server.WsSci;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * 嵌入式 Tomcat / WebSocket 在 native-image 下用到的反射与资源元数据。
 *
 * <p>autumn 自身的反射（{@code _XxxServiceRegister}、{@code _XxxProxy}、{@code Xxx$Client}、
 * {@code @Reference} 注入字段）已由 JSR-269 注解处理器在编译期生成到
 * {@code META-INF/native-image/com.micro-app/autumn-{export,reference}-hints/}，
 * 见 {@code NativeImageMetadataWriter}。这里是框架管不到的部分：</p>
 *
 * <ul>
 *   <li>Spring Boot 启动时 {@code TomcatWebServer#disableBindOnInit} 经
 *       {@code org.apache.tomcat.util.IntrospectionUtils} 反射调用
 *       {@code AbstractProtocol#getProperty(String)} / {@code AbstractEndpoint#setBindOnInit(boolean)}，
 *       这些属性读写必须是 INVOKE 级别（QUERY 不够）；</li>
 *   <li>{@code com.micro-app:tomcat-embed-core:10.1.x} 在 GraalVM reachability metadata 仓库里
 *       只能回落到 10.0.x 的配置，上述类型与方法没有被覆盖，缺失即启动失败
 *       （{@code MissingReflectionRegistrationError}）；</li>
 *   <li>Tomcat 静态资源、JSP/WebSocket 初始化与 {@code LocalStrings} 资源包，
 *       涉及 {@code *.xml} 描述符与 i18n 提示文案。</li>
 * </ul>
 *
 * <p>由 {@code @ImportRuntimeHints} 在 Spring AOT 阶段（{@code process-aot}）写进
 * {@code target/classes/META-INF/native-image/.../reflect-config.json} 与
 * {@code resource-config.json}，因此不再需要在仓库里维护手写 json 文件。</p>
 *
 * @author baoxin.zhao
 */
public class TomcatNativeHints implements RuntimeHintsRegistrar {

    /** Tomcat 各包下的 i18n 文案资源包（缺失只会丢提示文案，但会打警告，一并注册）。 */
    private static final String[] TOMCAT_LOCAL_STRINGS = {
            "org.apache.catalina.authenticator.LocalStrings",
            "org.apache.catalina.authenticator.jaspic.LocalStrings",
            "org.apache.catalina.connector.LocalStrings",
            "org.apache.catalina.core.LocalStrings",
            "org.apache.catalina.deploy.LocalStrings",
            "org.apache.catalina.loader.LocalStrings",
            "org.apache.catalina.mapper.LocalStrings",
            "org.apache.catalina.mbeans.LocalStrings",
            "org.apache.catalina.realm.LocalStrings",
            "org.apache.catalina.security.LocalStrings",
            "org.apache.catalina.session.LocalStrings",
            "org.apache.catalina.startup.LocalStrings",
            "org.apache.catalina.util.LocalStrings",
            "org.apache.catalina.valves.LocalStrings",
            "org.apache.catalina.webresources.LocalStrings",
            "org.apache.tomcat.util.LocalStrings",
            "org.apache.tomcat.util.buf.LocalStrings",
            "org.apache.tomcat.util.compat.LocalStrings",
            "org.apache.tomcat.util.descriptor.web.LocalStrings",
            "org.apache.tomcat.util.http.LocalStrings",
            "org.apache.tomcat.util.http.parser.LocalStrings",
            "org.apache.tomcat.util.modeler.LocalStrings",
            "org.apache.tomcat.util.net.LocalStrings",
            "org.apache.tomcat.util.scan.LocalStrings",
            "org.apache.tomcat.util.threads.LocalStrings",
            "org.apache.tomcat.websocket.LocalStrings",
            "org.apache.tomcat.websocket.server.LocalStrings"
    };

    /** 需要 INVOKE 全部 public 方法（IntrospectionUtils 反射 setProperty/getProperty 的对象）。 */
    private static final TypeReference[] INTROSPECTED_TYPES = {
            TypeReference.of(AbstractProtocol.class),
            TypeReference.of(AbstractHttp11Protocol.class),
            TypeReference.of(Http11NioProtocol.class),
            TypeReference.of(AbstractEndpoint.class),
            TypeReference.of(NioEndpoint.class),
            TypeReference.of(SocketProperties.class),
            TypeReference.of(Connector.class)
    };

    /** 需要反射构造的对象。 */
    private static final TypeReference[] CONSTRUCTIBLE_TYPES = {
            TypeReference.of(CharsetMapper.class),
            TypeReference.of(ApplicationContextFacade.class),
            TypeReference.of(Tomcat.class),
            TypeReference.of(WsSci.class),
            TypeReference.of(WsFilter.class)
    };

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        for (TypeReference type : INTROSPECTED_TYPES) {
            hints.reflection().registerType(type, MemberCategory.INVOKE_PUBLIC_METHODS);
        }
        for (TypeReference type : CONSTRUCTIBLE_TYPES) {
            hints.reflection().registerType(type, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        }
        // HTTP/2 升级协议按接口名反射查找
        hints.reflection().registerType(TypeReference.of(UpgradeProtocol.class));

        hints.resources().registerPattern(".*\\.properties");
        hints.resources().registerPattern("META-INF/services/.*");
        hints.resources().registerPattern("logback-spring.xml");
        hints.resources().registerPattern("org/apache/catalina/.*\\.xml");
        hints.resources().registerPattern("org/apache/tomcat/.*\\.xml");
        for (String bundle : TOMCAT_LOCAL_STRINGS) {
            hints.resources().registerResourceBundle(bundle);
        }
    }
}
