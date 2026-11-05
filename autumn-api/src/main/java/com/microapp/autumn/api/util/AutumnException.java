package com.microapp.autumn.api.util;

import lombok.Data;

@Data
public class AutumnException extends RuntimeException{
    private int status;
    private String message;

    public AutumnException() {
        super();
    }

    public AutumnException(String message) {
        super(message);
        this.status = 500;
        this.message = message;
    }

    public AutumnException(String message, Throwable throwable) {
        super(message, throwable);
        this.status = 500;
        this.message = message;
    }

}
