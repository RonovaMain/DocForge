package dev.ronova.docforge.print;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Запускает внешний фильтр (foo2zjs и др.): страницы пишутся в его stdin, всё, что он выдал в stdout,
 * по мере поступления отдаётся в [Sink] (на Android — в USB-канал принтера).
 */
public final class FilterProcess {

    public interface Sink {
        void write(byte[] data, int length) throws IOException;
    }

    private final Process process;
    private final OutputStream stdin;
    private final Thread pump;
    private final Thread errReader;
    private final ByteArrayOutputStream errTail = new ByteArrayOutputStream();
    private volatile IOException sinkError;

    private FilterProcess(Process process, Sink sink) {
        this.process = process;
        this.stdin = process.getOutputStream();
        final InputStream stdout = process.getInputStream();
        final InputStream stderr = process.getErrorStream();
        final Sink target = sink;
        pump = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[16 * 1024];
                try {
                    int n;
                    while ((n = stdout.read(buf)) >= 0) {
                        if (n > 0) target.write(buf, n);
                    }
                } catch (IOException e) {
                    sinkError = e;
                    FilterProcess.this.process.destroy();
                }
            }
        }, "filter-stdout");
        errReader = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[1024];
                try {
                    int n;
                    while ((n = stderr.read(buf)) >= 0) {
                        synchronized (errTail) {
                            if (errTail.size() < 4096) errTail.write(buf, 0, Math.min(n, 4096 - errTail.size()));
                        }
                    }
                } catch (IOException ignored) {
                    // процесс завершён
                }
            }
        }, "filter-stderr");
        pump.setDaemon(true);
        errReader.setDaemon(true);
        pump.start();
        errReader.start();
    }

    public static FilterProcess start(List<String> command, Sink sink) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        return new FilterProcess(pb.start(), sink);
    }

    public OutputStream stdin() {
        return stdin;
    }

    /** Закрывает ввод, ждёт конца вывода и проверяет код возврата. */
    public void finish() throws IOException {
        try {
            stdin.close();
        } catch (IOException ignored) {
            // фильтр мог завершиться раньше — причина будет в коде возврата
        }
        int code;
        try {
            pump.join();
            code = process.waitFor();
            errReader.join(500);
        } catch (InterruptedException e) {
            process.destroy();
            Thread.currentThread().interrupt();
            throw new IOException("Печать прервана");
        }
        if (sinkError != null) throw sinkError;
        if (code != 0) {
            String tail;
            synchronized (errTail) {
                tail = new String(errTail.toByteArray(), StandardCharsets.UTF_8).trim();
            }
            throw new IOException("Фильтр печати завершился с кодом " + code + (tail.isEmpty() ? "" : ": " + tail));
        }
    }

    public void abort() {
        process.destroy();
    }
}
