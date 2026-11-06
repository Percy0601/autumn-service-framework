package com.microapp.autumn.compiler;

import java.io.Writer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microapp.autumn.compiler.model.ExportEntry;
import com.microapp.autumn.compiler.model.TargetClass;
import com.microapp.autumn.compiler.util.ClassNameUtil;
import com.microapp.autumn.compiler.util.ClassResolverUtil;
import com.microapp.autumn.compiler.util.FreemarkerUtil;
import com.microapp.autumn.compiler.util.MetaHolder;
import com.microapp.autumn.compiler.util.NativeImageMetadataWriter;

/**
 * 为 {@code @Export} 生成 AutumnServiceAssembler 与 SPI 注册文件。
 *
 * <p>按 round 累积生成：每一轮只写本轮新收集到的类型，后续 round 出现的注解不会再被漏掉；
 * SPI 文件累积所有生成的 assembler 类名（不是实现类），多轮下也能完整覆盖。</p>
 */
@SupportedAnnotationTypes(value = {"com.microapp.autumn.api.annotation.Export"})
@SupportedSourceVersion(value = SourceVersion.RELEASE_25)
public class ExportServiceProcessor extends AbstractProcessor {

    private static final String IFACE_SUFFIX = ".Iface";
    private static final String SERVICE_REGISTER_SUFFIX = "ServiceRegister";
    private static final String SERVICES_LOCATION = "META-INF/services";
    private static final String ASSEMBLER_INTERFACE = "com.microapp.autumn.api.extension.AutumnServiceAssembler";

    private final Logger log = LoggerFactory.getLogger(ExportServiceProcessor.class);

    private final MetaHolder.State state = new MetaHolder.State();
    /** 本轮新收集、尚未生成源文件的实现类 */
    private final Set<String> pending = new LinkedHashSet<>();
    /** 跨 round 累积的 assembler FQN，只把生成类写进 SPI 文件 */
    private final Set<String> generatedAssemblers = new LinkedHashSet<>();
    /** 编译期产出的 native-image 反射元数据，导出侧只关心生成类与 thrift Processor */
    private final NativeImageMetadataWriter nativeMetadata = new NativeImageMetadataWriter();

    private Filer filer;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        this.filer = processingEnvironment.getFiler();
        log.info("begin handle export annotation process init.");
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (TypeElement annotationElement : annotations) {
            for (Element annotatedClass : roundEnv.getElementsAnnotatedWith(annotationElement)) {
                collect(annotatedClass);
            }
        }
        // 最后一轮不允许再创建源文件，只在有新增条目的普通 round 生成
        if (!roundEnv.processingOver() && !pending.isEmpty()) {
            writePending();
        }
        return true;
    }

    private void collect(Element annotatedClass) {
        TargetClass targetClass = ClassResolverUtil.handleExport(annotatedClass);
        String implName = targetClass.getFullName();
        if (state.containsExport(implName)) {
            return;
        }
        List<String> interfaces = targetClass.getImplementList();
        String ifaceName = Objects.isNull(interfaces) ? null : interfaces.stream()
                .filter(it -> it.endsWith(IFACE_SUFFIX))
                .findFirst()
                .orElse(null);
        if (Objects.isNull(ifaceName)) {
            log.warn("autumn export class has no thrift Iface interface, skip code generation. class:{}", implName);
            return;
        }
        ExportEntry entry = new ExportEntry();
        entry.setImplementName(implName);
        entry.setInterfaceName(ifaceName);
        entry.setName(targetClass.getName());
        entry.setMethods(targetClass.getMethods());
        state.addExportEntry(entry);
        pending.add(implName);
        log.info("autumn export collect entry:{}", entry);
    }

    private void writePending() {
        List<ExportEntry> entries = state.getExportEntries();
        for (ExportEntry entry : entries) {
            if (!pending.remove(entry.getImplementName())) {
                continue;
            }
            String implFullName = entry.getImplementName();
            String ifaceName = entry.getInterfaceName();
            String packageName = ClassNameUtil.getPackageName(implFullName);
            String simpleClassName = ClassNameUtil.getSimpleClassName(implFullName);
            String ifaceOuterName = ifaceName.substring(0, ifaceName.length() - IFACE_SUFFIX.length());
            String generatedFullName = packageName + "._" + simpleClassName + SERVICE_REGISTER_SUFFIX;
            try {
                JavaFileObject builderFile = filer.createSourceFile(generatedFullName);
                // must close the writer: javac skips generated sources whose writer is left open
                try (Writer sourceWriter = builderFile.openWriter()) {
                    FreemarkerUtil.handleServer(packageName, simpleClassName, implFullName, ifaceName,
                            ifaceOuterName, sourceWriter);
                }
                generatedAssemblers.add(generatedFullName);
                // ServiceLoader 通过 Class.newInstance 实例化 assembler，反射元数据必须注册
                nativeMetadata.constructors(generatedFullName);
                // 兜底路径：AutumnBeanPostProcessor#exportService 里 Class.forName(outer + "$Processor")
                nativeMetadata.constructors(ifaceOuterName + "$Processor");
                log.info("autumn export generate register class:{}", generatedFullName);
            } catch (FilerException alreadyGenerated) {
                // 同一编译单元里同名源文件已由上一轮创建，忽略即可
                log.debug("autumn export source already generated:{}", generatedFullName);
            } catch (Exception e) {
                log.warn("autumn export write source exception, class:{}", generatedFullName, e);
            }
        }
        pending.clear();
        writeSpi();
        writeNativeMetadata();
    }

    /**
     * 编译期落盘 native-image 反射元数据（META-INF/native-image/com.micro-app/autumn-export-hints/），
     * 随模块 jar 打包，native-image 构建时自动合并，宿主工程无需手写 reflect-config.json。
     */
    private void writeNativeMetadata() {
        if (nativeMetadata.isEmpty()) {
            return;
        }
        nativeMetadata.write(filer, "autumn-export-hints", log);
    }

    /**
     * SPI 文件每轮重写为全量 assembler 列表；首次用 createResource，已存在则复用并覆盖写。
     */
    private void writeSpi() {
        if (generatedAssemblers.isEmpty()) {
            return;
        }
        String path = SERVICES_LOCATION + "/" + ASSEMBLER_INTERFACE;
        try {
            FileObject servicesFile;
            try {
                servicesFile = filer.createResource(StandardLocation.CLASS_OUTPUT, "", path);
            } catch (FilerException alreadyExists) {
                // 已由本编译的上一轮创建（或增量编译残留），复用并全量覆盖
                servicesFile = filer.getResource(StandardLocation.CLASS_OUTPUT, "", path);
            }
            try (Writer writer = servicesFile.openWriter()) {
                for (String fqn : generatedAssemblers) {
                    writer.write(fqn + "\n");
                }
            }
            log.info("autumn export generate spi services file:{}, assemblers:{}", path, generatedAssemblers.size());
        } catch (Exception e) {
            log.warn("autumn export write spi exception:", e);
        }
    }
}
