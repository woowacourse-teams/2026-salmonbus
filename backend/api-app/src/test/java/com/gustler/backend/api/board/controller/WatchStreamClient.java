package com.gustler.backend.api.board.controller;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring 은 응답에 붙기 전에 쌓인 쓰기를 한 번에 내보내므로, 그사이 쌓인 ping 은 첫 쓰기와 같은 청크로 나갈 수 있다. 그래서 청크
 * 경계에 기대지 않고 SSE 블록으로 나눈다.
 */
final class WatchStreamClient implements AutoCloseable {
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final String BLOCK_END = "\n\n";

    private final Socket socket;
    private final InputStream input;
    private final int status;
    private final List<String> headers;
    private final StringBuilder pending = new StringBuilder();
    private boolean ended;

    private WatchStreamClient(Socket socket, InputStream input, int status, List<String> headers) {
        this.socket = socket;
        this.input = input;
        this.status = status;
        this.headers = headers;
    }

    static WatchStreamClient open(int port, String path, String... extraHeaders) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        try {
            socket.setSoTimeout((int) READ_TIMEOUT.toMillis());
            StringBuilder request = new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: 127.0.0.1:").append(port).append("\r\n")
                .append("Accept: text/event-stream\r\n");
            for (String header : extraHeaders) {
                request.append(header).append("\r\n");
            }
            request.append("\r\n");
            OutputStream output = socket.getOutputStream();
            output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();

            InputStream input = new BufferedInputStream(socket.getInputStream());
            int status = Integer.parseInt(readLine(input).split(" ")[1]);
            List<String> headers = new ArrayList<>();
            String line;
            while (!(line = readLine(input)).isEmpty()) {
                headers.add(line);
            }
            return new WatchStreamClient(socket, input, status, headers);
        } catch (IOException | RuntimeException failure) {
            socket.close();
            throw failure;
        }
    }

    int status() {
        return status;
    }

    List<String> headers(String name) {
        return headers.stream()
            .filter(header -> header.regionMatches(true, 0, name + ":", 0, name.length() + 1))
            .map(header -> header.substring(name.length() + 1).trim())
            .toList();
    }

    String header(String name) {
        List<String> values = headers(name);
        return values.isEmpty() ? null : values.getFirst();
    }

    String nextBlock() throws IOException {
        while (pending.indexOf(BLOCK_END) < 0) {
            String chunk = readChunk();
            if (chunk == null) {
                if (!pending.isEmpty()) {
                    throw new EOFException("블록이 끝나기 전에 스트림이 끝났다: " + pending);
                }
                return null;
            }
            pending.append(chunk);
        }
        int end = pending.indexOf(BLOCK_END);
        String block = pending.substring(0, end);
        pending.delete(0, end + BLOCK_END.length());
        return block;
    }

    boolean ended() {
        return ended;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private String readChunk() throws IOException {
        if (ended) {
            return null;
        }
        String sizeLine = readLine(input);
        int size = Integer.parseInt(sizeLine.trim(), 16);
        if (size == 0) {
            readLine(input);
            ended = true;
            return null;
        }
        byte[] payload = input.readNBytes(size);
        readLine(input);
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = input.read()) != -1) {
            if (previous == '\r' && current == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
            }
            line.write(current);
            previous = current;
        }
        throw new EOFException("종료 청크 없이 연결이 끊겼다");
    }
}
