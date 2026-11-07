package com.gustler.backend.support;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

public final class LoopbackRelay implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    private final ServerSocket server;
    private final String targetHost;
    private final int targetPort;

    public LoopbackRelay(
        String targetHost,
        final int targetPort
    ) throws IOException {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread acceptor = new Thread(this::accept, "loopback-relay");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Socket target = new Socket();
                try {
                    target.connect(new InetSocketAddress(targetHost, targetPort), CONNECT_TIMEOUT_MILLIS);
                } catch (IOException connectionFailed) {
                    try {
                        target.close();
                    } finally {
                        client.close();
                    }
                    continue;
                }
                pipe(client, target);
                pipe(target, client);
            } catch (IOException ignored) {
                // 수락 실패 뒤 종료 여부를 다시 확인한다.
                continue;
            }
        }
    }

    private static void pipe(
        Socket from,
        Socket to
    ) {
        Thread thread = new Thread(() -> {
            try (from; to) {
                from.getInputStream().transferTo(to.getOutputStream());
            } catch (IOException ignored) {
                // 한쪽 연결이 닫히면 이 방향의 복사를 끝낸다.
                return;
            }
        }, "loopback-relay-pipe");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
