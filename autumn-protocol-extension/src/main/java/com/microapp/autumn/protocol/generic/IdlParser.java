package com.microapp.autumn.protocol.generic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal recursive-descent parser for the Thrift IDL subset needed by generic routing:
 * {@code struct / union / exception / enum / service}, primitive and container types,
 * field ids, {@code required|optional}, {@code oneway}, {@code void} and {@code throws}.
 *
 * <p>It produces {@link ServiceSpec} descriptors at <b>runtime</b> — no thrift compiler and
 * no generated Java classes. Namespace/include/const/typedef/senum declarations are skipped,
 * so aliases and included IDLs are not resolved (see v1 limitations).</p>
 */
public final class IdlParser {

    private IdlParser() {
    }

    public static List<ServiceSpec> parseAll(String idl) {
        if (idl == null || idl.isBlank()) {
            throw new IllegalArgumentException("IDL text is empty");
        }
        return new Parser(idl).parse();
    }

    /** Parse and return the first declared service (the common single-service IDL layout). */
    public static ServiceSpec parse(String idl) {
        List<ServiceSpec> services = parseAll(idl);
        if (services.isEmpty()) {
            throw new IllegalArgumentException("no service declared in IDL");
        }
        return services.get(0);
    }

    private static final class Parser {

        private final List<String> tokens;
        private final Map<String, TypeSpec> types = new LinkedHashMap<>();
        private final List<ServiceSpec> services = new ArrayList<>();
        private int pos;

        private Parser(String idl) {
            this.tokens = tokenize(idl);
        }

        private List<ServiceSpec> parse() {
            while (hasNext()) {
                switch (peek()) {
                    case "enum" -> parseEnum();
                    case "struct", "union", "exception" -> parseStruct();
                    case "service" -> parseService();
                    default -> next(); // namespace / include / const / typedef / senum / stray separators
                }
            }
            return services;
        }

        private void parseEnum() {
            next(); // enum
            String name = next();
            types.put(name, TypeSpec.of(ThriftType.ENUM));
            expect("{");
            int depth = 1;
            while (hasNext() && depth > 0) {
                String token = next();
                if (token.equals("{")) {
                    depth++;
                } else if (token.equals("}")) {
                    depth--;
                }
            }
        }

        private void parseStruct() {
            next(); // struct / union / exception
            String name = next();
            expect("{");
            List<TypeSpec> fields = parseFieldList("}");
            types.put(name, TypeSpec.struct(name, fields.toArray(new TypeSpec[0])));
        }

        private void parseService() {
            next(); // service
            String name = next();
            if (peekIs("extends")) {
                next();
                next(); // parent service name
            }
            expect("{");
            ServiceSpec.Builder builder = ServiceSpec.builder(name);
            types.forEach(builder::type);
            while (hasNext() && !peekIs("}")) {
                if (peekIs(",") || peekIs(";")) {
                    next();
                    continue;
                }
                boolean oneway = false;
                if (peekIs("oneway")) {
                    next();
                    oneway = true;
                }
                TypeSpec result = parseType();
                String methodName = next();
                expect("(");
                List<TypeSpec> arguments = parseFieldList(")");
                List<TypeSpec> exceptions = List.of();
                if (peekIs("throws")) {
                    next();
                    expect("(");
                    exceptions = parseFieldList(")");
                }
                if (peekIs(",") || peekIs(";")) {
                    next();
                }
                MethodSpec.Builder method = MethodSpec.builder(methodName).returns(result);
                arguments.forEach(method::arg);
                for (TypeSpec exception : exceptions) {
                    method.throwsEx(exception.fieldId(), exception.name(), exception);
                }
                if (oneway) {
                    method.oneway();
                }
                builder.method(method.build());
            }
            expect("}");
            services.add(builder.build());
        }

        private List<TypeSpec> parseFieldList(String closing) {
            List<TypeSpec> fields = new ArrayList<>();
            while (hasNext() && !peekIs(closing)) {
                if (peekIs(",") || peekIs(";")) {
                    next();
                    continue;
                }
                Short explicitId = null;
                if (isInteger(peek()) && peek(1).equals(":")) {
                    explicitId = Short.valueOf(next());
                    next(); // ':'
                }
                // Thrift "default requiredness" (no modifier) may be omitted by a caller;
                // only an explicit `required` field is mandatory.
                boolean required = false;
                if (peekIs("required")) {
                    next();
                    required = true;
                } else if (peekIs("optional")) {
                    next();
                }
                TypeSpec base = parseType();
                String fieldName = next();
                if (peekIs("=")) {
                    next();
                    skipDefaultValue();
                }
                if (peekIs(",") || peekIs(";")) {
                    next();
                }
                short id = explicitId != null ? explicitId : (short) (fields.size() + 1);
                fields.add(required
                        ? TypeSpec.field(id, fieldName, base)
                        : TypeSpec.optionalField(id, fieldName, base));
            }
            expect(closing);
            return fields;
        }

        private void skipDefaultValue() {
            int depth = 0;
            while (hasNext()) {
                String token = peek();
                if (depth == 0 && (token.equals(",") || token.equals(";")
                        || token.equals(")") || token.equals("}"))) {
                    return;
                }
                next();
                switch (token) {
                    case "[", "{", "(" -> depth++;
                    case "]", "}", ")" -> depth--;
                    default -> {
                    }
                }
            }
        }

        private TypeSpec parseType() {
            String token = next();
            ThriftType primitive = ThriftType.fromIdl(token);
            if (primitive != null) {
                return TypeSpec.of(primitive);
            }
            return switch (token) {
                case "list" -> {
                    expect("<");
                    TypeSpec element = parseType();
                    expect(">");
                    yield TypeSpec.list(element);
                }
                case "set" -> {
                    expect("<");
                    TypeSpec element = parseType();
                    expect(">");
                    yield TypeSpec.set(element);
                }
                case "map" -> {
                    expect("<");
                    TypeSpec key = parseType();
                    expect(",");
                    TypeSpec value = parseType();
                    expect(">");
                    yield TypeSpec.map(key, value);
                }
                default -> {
                    TypeSpec named = types.get(token);
                    if (named == null) {
                        throw new IllegalArgumentException("unknown or forward-referenced type '" + token
                                + "' at token " + pos + "; declare structs/enums before use");
                    }
                    yield named;
                }
            };
        }

        private boolean hasNext() {
            return pos < tokens.size();
        }

        private String peek() {
            return hasNext() ? tokens.get(pos) : "";
        }

        private String peek(int offset) {
            int index = pos + offset;
            return index < tokens.size() ? tokens.get(index) : "";
        }

        private String next() {
            if (!hasNext()) {
                throw new IllegalArgumentException("unexpected end of IDL at token " + pos);
            }
            return tokens.get(pos++);
        }

        private boolean peekIs(String expected) {
            return peek().equals(expected);
        }

        private void expect(String expected) {
            String actual = next();
            if (!actual.equals(expected)) {
                throw new IllegalArgumentException("expected '" + expected + "' but got '" + actual
                        + "' at token " + pos);
            }
        }

        private static boolean isInteger(String token) {
            if (token.isEmpty()) {
                return false;
            }
            int start = token.charAt(0) == '-' ? 1 : 0;
            if (start == token.length()) {
                return false;
            }
            for (int i = start; i < token.length(); i++) {
                if (!Character.isDigit(token.charAt(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    private static List<String> tokenize(String idl) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        int length = idl.length();
        while (i < length) {
            char c = idl.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '#' || (c == '/' && i + 1 < length && idl.charAt(i + 1) == '/')) {
                while (i < length && idl.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < length && idl.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < length && !(idl.charAt(i) == '*' && idl.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(length, i + 2);
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                StringBuilder literal = new StringBuilder();
                while (i < length && idl.charAt(i) != quote) {
                    if (idl.charAt(i) == '\\' && i + 1 < length) {
                        i++;
                    }
                    literal.append(idl.charAt(i));
                    i++;
                }
                i++;
                tokens.add(literal.toString());
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < length && (Character.isLetterOrDigit(idl.charAt(i))
                        || idl.charAt(i) == '_' || idl.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(idl.substring(start, i));
                continue;
            }
            if (Character.isDigit(c) || (c == '-' && i + 1 < length && Character.isDigit(idl.charAt(i + 1)))) {
                int start = i;
                while (i < length && (Character.isDigit(idl.charAt(i))
                        || "+-.eE".indexOf(idl.charAt(i)) >= 0)) {
                    i++;
                }
                tokens.add(idl.substring(start, i));
                continue;
            }
            tokens.add(String.valueOf(c));
            i++;
        }
        return tokens;
    }
}
