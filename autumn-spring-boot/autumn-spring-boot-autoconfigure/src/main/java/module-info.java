/**
 * 
 * @author: baoxin.zhao
 * @date: 2024/11/10 
 */
module autumn.spring.boot.autoconfigure {
    requires lombok;
    requires org.slf4j;
    requires org.apache.thrift;
    requires transitive java.compiler;
    requires transitive autumn.core;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.boot;
    requires spring.boot.autoconfigure;

    exports com.microapp.autumn.boot;
    exports com.microapp.autumn.boot.config;
}
