package com.microapp.autumn.protocol.generic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class IdlParserTest {

    static String idl() {
        try (InputStream in = IdlParserTest.class.getResourceAsStream("/generic-demo.thrift")) {
            if (in == null) {
                throw new IllegalStateException("missing test resource generic-demo.thrift");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read generic-demo.thrift", e);
        }
    }

    @Test
    void parsesStructsContainersMethodsAndExceptions() {
        ServiceSpec spec = IdlParser.parse(idl());

        assertEquals("HelloWorldService", spec.name());
        assertEquals(Set.of("sayHello", "returnModel", "modelProcess", "sum", "echoMap", "fail", "ping"),
                spec.methods().keySet());

        MethodSpec sayHello = spec.requireMethod("sayHello");
        assertEquals(ThriftType.STRING, sayHello.result().type());
        assertEquals(1, sayHello.arguments().size());
        assertEquals(1, sayHello.arguments().get(0).fieldId());
        assertEquals("username", sayHello.arguments().get(0).name());

        TypeSpec model = spec.requireMethod("returnModel").result();
        assertEquals(ThriftType.STRUCT, model.type());
        assertEquals(3, model.fields().size());
        assertEquals(ThriftType.I32, model.fieldById((short) 1).type());
        assertFalse(model.fieldById((short) 1).optional());
        assertTrue(model.fieldById((short) 2).optional());
        // unmodified IDL field = default requiredness, omittable on write
        assertTrue(model.fieldById((short) 3).optional());
        assertEquals(ThriftType.LIST, model.fieldById((short) 3).type());
        assertEquals(ThriftType.I32, model.fieldById((short) 3).elementType().type());

        TypeSpec structArg = spec.requireMethod("modelProcess").arguments().get(0);
        assertEquals(ThriftType.STRUCT, structArg.type());
        assertEquals(3, structArg.fields().size());

        MethodSpec sum = spec.requireMethod("sum");
        assertEquals(ThriftType.LIST, sum.arguments().get(0).type());
        assertEquals(ThriftType.I32, sum.result().type());

        TypeSpec echoMap = spec.requireMethod("echoMap").result();
        assertEquals(ThriftType.MAP, echoMap.type());
        assertEquals(ThriftType.STRING, echoMap.keyType().type());
        assertEquals(ThriftType.I32, echoMap.valueType().type());

        MethodSpec fail = spec.requireMethod("fail");
        assertEquals(ThriftType.VOID, fail.result().type());
        assertEquals(1, fail.exceptions().size());
        assertEquals(1, fail.exceptions().get(0).fieldId());
        assertEquals("e", fail.exceptions().get(0).name());
        assertEquals(ThriftType.STRUCT, fail.exceptions().get(0).type());

        assertTrue(spec.requireMethod("ping").oneway());
        assertEquals(ThriftType.VOID, spec.requireMethod("ping").result().type());

        assertTrue(spec.types().containsKey("Model"));
        assertTrue(spec.types().containsKey("ErrorEx"));
        assertEquals(ThriftType.ENUM, spec.types().get("Status").type());
    }

    @Test
    void forwardOrUnknownTypeFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> IdlParser.parse("service S { Model m(1: string x) }"));
        assertThrows(IllegalArgumentException.class, () -> IdlParser.parse("struct A { 1: i32 x }"));
        assertThrows(IllegalArgumentException.class, () -> IdlParser.parse("  "));
    }

    @Test
    void registryHotLoadsIdlWithoutRestarting() {
        ServiceRegistry registry = new ServiceRegistry();
        assertTrue(registry.names().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> registry.require("HelloWorldService"));

        List<ServiceSpec> parsed = registry.registerIdl(idl());
        assertEquals(1, parsed.size());
        assertSame(parsed.get(0), registry.require("HelloWorldService"));
        assertEquals(Set.of("HelloWorldService"), registry.names());
        assertEquals(7, registry.require("HelloWorldService").methods().size());
    }
}
