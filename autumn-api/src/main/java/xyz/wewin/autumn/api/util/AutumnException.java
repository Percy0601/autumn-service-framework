package xyz.wewin.autumn.api.util;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
// RuntimeException 的 equals/hashCode 是 Object 语义（按实例比较），Lombok 默认不调 super 会改变这一语义；
// 这里显式声明保持“按 status + message 比较”的既有行为，同时消除 Lombok 的 callSuper 告警。
@EqualsAndHashCode(callSuper = false)
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
