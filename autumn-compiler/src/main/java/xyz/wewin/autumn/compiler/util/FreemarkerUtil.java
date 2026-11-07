package xyz.wewin.autumn.compiler.util;

import xyz.wewin.autumn.compiler.model.ReferenceEntry;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import lombok.extern.slf4j.Slf4j;

import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

/**
 * freemarker template util, generate source code for @Export service register
 * and @Reference client proxy at compile time.
 */
@Slf4j
public class FreemarkerUtil {

    private FreemarkerUtil() {

    }

    private static Template loadTemplate(String templateName) throws Exception {
        Configuration configuration = new Configuration(Configuration.VERSION_2_3_31);
        configuration.setClassForTemplateLoading(FreemarkerUtil.class, "/templates");
        configuration.setDefaultEncoding("utf-8");
        configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        return configuration.getTemplate(templateName);
    }

    /**
     * generate _XxxServiceRegister class which implements AutumnServiceAssembler,
     * assembling {@code SomeService.Processor + AttachableProcessor} into ServiceConfig without reflection.
     *
     * @param packageName    implementation class package
     * @param simpleName     implementation class simple name
     * @param implClassName  implementation class full name
     * @param ifaceName      thrift Iface interface full name, e.g. com.x.SomeService.Iface
     * @param ifaceOuterName thrift outer class full name, e.g. com.x.SomeService
     * @param writer         source file writer
     */
    public static void handleServer(String packageName, String simpleName, String implClassName,
                                    String ifaceName, String ifaceOuterName, Writer writer) {
        try {
            Template template = loadTemplate("export.ftl");
            Map<String, Object> root = new HashMap<>();
            root.put("packageName", packageName);
            root.put("simpleClassName", simpleName);
            root.put("implClassName", implClassName);
            root.put("ifaceName", ifaceName);
            root.put("ifaceOuterName", ifaceOuterName);
            template.process(root, writer);
            writer.flush();
        } catch (Exception e) {
            log.warn("FreemarkerUtil handleServer exception:", e);
            throw new IllegalStateException("generate export register source fail, impl:" + implClassName, e);
        }
    }

    /**
     * generate _XxxProxy class which implements the thrift Iface interface,
     * each method borrows connection from AutumnPool and invokes generated thrift Client.
     *
     * @param packageName     interface package
     * @param outerSimpleName thrift outer class simple name, e.g. SomeService
     * @param ifaceName       thrift Iface interface full name, e.g. com.x.SomeService.Iface
     * @param outerName       thrift outer class full name, e.g. com.x.SomeService
     * @param refer           register center service name
     * @param entry           interface methods metadata
     * @param writer          source file writer
     */
    public static void handleProxy(String packageName, String outerSimpleName, String ifaceName,
                                   String outerName, String refer, ReferenceEntry entry, Writer writer) {
        try {
            Template template = loadTemplate("reference.ftl");
            Map<String, Object> root = new HashMap<>();
            root.put("packageName", packageName);
            root.put("outerSimpleName", outerSimpleName);
            root.put("ifaceName", ifaceName);
            root.put("outerName", outerName);
            root.put("refer", refer);
            root.put("methods", entry.getMethodElements());
            template.process(root, writer);
            writer.flush();
        } catch (Exception e) {
            log.warn("FreemarkerUtil handleProxy exception:", e);
            throw new IllegalStateException("generate reference proxy source fail, iface:" + ifaceName, e);
        }
    }

}
