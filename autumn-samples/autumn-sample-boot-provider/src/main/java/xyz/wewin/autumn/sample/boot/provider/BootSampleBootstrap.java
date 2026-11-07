package xyz.wewin.autumn.sample.boot.provider;

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

import xyz.wewin.autumn.sample.api.User;
import xyz.wewin.autumn.sample.nativehints.TomcatNativeHints;

/**
 * @author: baoxin.zhao
 * @date: 2024/11/9
 *
 * <p>native-image 元数据分三处来源，构建期全部就绪，运行期无需 agent、仓库里无需手写 json：</p>
 * <ul>
 *   <li>autumn 自身的 {@code _XxxServiceRegister}/{@code _XxxProxy}/{@code Xxx$Client}/{@code @Reference}
 *       字段 —— JSR-269 注解处理器编译期生成（{@code META-INF/native-image/xyz.we-win.autumn/autumn-*-hints/}）；</li>
 *   <li>嵌入式 Tomcat 的反射与资源 —— {@link TomcatNativeHints}，由 Spring AOT 写入；</li>
 *   <li>接口返回的 thrift POJO 交给 Jackson —— {@code @RegisterReflectionForBinding}。</li>
 * </ul>
 */
@SpringBootApplication
@ImportRuntimeHints(TomcatNativeHints.class)
@RegisterReflectionForBinding(User.class)
public class BootSampleBootstrap {

    public static void main(String[] args) {
        SpringApplication.run(BootSampleBootstrap.class, args);
    }

}
