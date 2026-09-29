package com.gustler.backend.maintenance;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

final class LoopbackRelay implements AutoCloseable {

    private final ServerSocket server;
    private final String targetHost;
    private final int targetPort;

    LoopbackRelay(
        String targetHost,
        int targetPort
    ) throws IOException {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::accept, "loopback-relay");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return server.getLocalPort();
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Socket target = new Socket(targetHost, targetPort);
                pipe(client, target);
                pipe(target, client);
            } catch (IOException ignored) {
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
