package dev.mekkaoui.shortener.url;

public class CodeGenerationExhaustedException extends RuntimeException {

    public CodeGenerationExhaustedException(String message) {
        super(message);
    }

    public CodeGenerationExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
