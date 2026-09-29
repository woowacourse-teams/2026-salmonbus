package com.gustler.backend.api.chat.infrastructure.mongo;

import com.github.dockerjava.api.command.InspectContainerResponse;
import java.io.IOException;
import java.nio.file.Path;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.MountableFile;

public final class ChatMongoContainer extends MongoDBContainer {

    private static final String IMAGE = "mongo:8.0";
    private static final String INIT_SCRIPT_PROPERTY = "chat.init.script";
    private static final String INIT_SCRIPT_IN_CONTAINER = "/tmp/init-chat-db.js";

    public ChatMongoContainer() {
        super(IMAGE);
    }

    @Override
    protected void containerIsStarted(InspectContainerResponse containerInfo, final boolean reused) {
        super.containerIsStarted(containerInfo, reused);
        copyFileToContainer(MountableFile.forHostPath(initScript()), INIT_SCRIPT_IN_CONTAINER);
        try {
            ExecResult result = execInContainer("mongosh", "--quiet", INIT_SCRIPT_IN_CONTAINER);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("init-chat-db.js failed: " + result.getStderr());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("cannot run init-chat-db.js", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running init-chat-db.js", exception);
        }
    }

    private static Path initScript() {
        String path = System.getProperty(INIT_SCRIPT_PROPERTY);
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(INIT_SCRIPT_PROPERTY + " system property is required");
        }
        return Path.of(path);
    }
}
