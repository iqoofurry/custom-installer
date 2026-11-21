package com.iqoofurry.sheetinstaller;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class RootShell {
    static final int CODE_TIMEOUT = -2;
    static final int CODE_ERROR = -3;

    static final class Result {
        final int code;
        final String output;

        Result(int code, String output) {
            this.code = code;
            this.output = output == null ? "" : output;
        }
    }

    private RootShell() {
    }

    static Result run(String command, long timeoutSeconds) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            final Process p = process;
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = p.getInputStream()) {
                    byte[] chunk = new byte[8192];
                    int read;
                    while ((read = in.read(chunk)) != -1) {
                        buffer.write(chunk, 0, read);
                    }
                } catch (Throwable ignored) {
                }
            }, "ci-root-reader");
            reader.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                return new Result(CODE_TIMEOUT, "Таймаут выполнения root-команды");
            }
            reader.join(1500);
            return new Result(process.exitValue(), new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            if (process != null) {
                process.destroy();
            }
            return new Result(CODE_ERROR, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    interface CopyListener {
        void onCopied(long bytes);
    }

    /**
     * Выполняет root-команду, подавая ей на stdin содержимое input.
     * Нужно для копирования OBB прямо из ZIP без промежуточной распаковки.
     */
    static Result runWithInput(String command, InputStream input, long timeoutSeconds,
                               CopyListener listener) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            final Process p = process;
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = p.getInputStream()) {
                    byte[] chunk = new byte[8192];
                    int read;
                    while ((read = in.read(chunk)) != -1) {
                        buffer.write(chunk, 0, read);
                    }
                } catch (Throwable ignored) {
                }
            }, "ci-root-reader");
            reader.start();
            try (OutputStream out = process.getOutputStream()) {
                byte[] chunk = new byte[64 * 1024];
                int read;
                long copied = 0;
                while ((read = input.read(chunk)) != -1) {
                    out.write(chunk, 0, read);
                    copied += read;
                    if (listener != null) {
                        listener.onCopied(copied);
                    }
                }
            } catch (Throwable ignored) {
                // Команда могла завершиться раньше (например, нет доступа к папке).
                // Итог всё равно определим по коду возврата и выводу.
            }
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                return new Result(CODE_TIMEOUT, "Таймаут выполнения root-команды");
            }
            reader.join(1500);
            return new Result(process.exitValue(), new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            if (process != null) {
                process.destroy();
            }
            return new Result(CODE_ERROR, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    static boolean available() {
        Result result = run("id", 8);
        return result.code == 0 && result.output.contains("uid=0");
    }

    static boolean copyToCache(String sourcePath, File destination) {
        String command = "cp " + quote(sourcePath) + " " + quote(destination.getAbsolutePath())
                + " && chmod 644 " + quote(destination.getAbsolutePath());
        Result result = run(command, 60);
        return result.code == 0 && destination.isFile() && destination.length() > 0;
    }

    static boolean copyFile(File source, File destination) throws Exception {
        try (InputStream in = new java.io.FileInputStream(source);
             OutputStream out = new java.io.FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
        return destination.isFile() && destination.length() > 0;
    }

    static String quote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
