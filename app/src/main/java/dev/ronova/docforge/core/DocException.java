package dev.ronova.docforge.core;

/** Понятная пользователю ошибка чтения или правки документа. */
public class DocException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DocException(String message) {
        super(message);
    }

    public DocException(String message, Throwable cause) {
        super(message, cause);
    }
}
