package com.microapp.autumn.protocol.generic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Runtime description of one Thrift service method: argument fields, success type and declared exceptions. */
public final class MethodSpec {

    private final String name;
    private final List<TypeSpec> arguments;
    private final TypeSpec result;
    private final List<TypeSpec> exceptions;
    private final boolean oneway;

    private MethodSpec(Builder builder) {
        this.name = builder.name;
        this.arguments = List.copyOf(builder.arguments);
        this.result = builder.result;
        this.exceptions = List.copyOf(builder.exceptions);
        this.oneway = builder.oneway;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public List<TypeSpec> arguments() {
        return arguments;
    }

    public TypeSpec result() {
        return result;
    }

    public List<TypeSpec> exceptions() {
        return exceptions;
    }

    public boolean oneway() {
        return oneway;
    }

    public TypeSpec argument(String argumentName) {
        for (TypeSpec argument : arguments) {
            if (argument.name().equals(argumentName)) {
                return argument;
            }
        }
        return null;
    }

    public TypeSpec exceptionById(short id) {
        for (TypeSpec exception : exceptions) {
            if (exception.fieldId() == id) {
                return exception;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return name + "(" + arguments + ") -> " + result + " throws " + exceptions;
    }

    public static final class Builder {

        private final String name;
        private final List<TypeSpec> arguments = new ArrayList<>();
        private final List<TypeSpec> exceptions = new ArrayList<>();
        private TypeSpec result = TypeSpec.of(ThriftType.VOID);
        private boolean oneway;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("method name must not be blank");
            }
        }

        public Builder arg(short id, String argumentName, TypeSpec base) {
            arguments.add(TypeSpec.field(id, argumentName, base));
            return this;
        }

        public Builder optionalArg(short id, String argumentName, TypeSpec base) {
            arguments.add(TypeSpec.optionalField(id, argumentName, base));
            return this;
        }

        public Builder arg(TypeSpec field) {
            arguments.add(field);
            return this;
        }

        public Builder returns(TypeSpec spec) {
            this.result = Objects.requireNonNull(spec, "result");
            return this;
        }

        public Builder throwsEx(short id, String exceptionName, TypeSpec base) {
            exceptions.add(TypeSpec.field(id, exceptionName, base));
            return this;
        }

        public Builder oneway() {
            this.oneway = true;
            return this;
        }

        public MethodSpec build() {
            return new MethodSpec(this);
        }
    }
}
