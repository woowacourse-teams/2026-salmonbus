package com.gustler.devtools;

import java.nio.file.Path;

record Layout(Path root, Path config, Path data, Path locks, Path units) {
    static final Layout SERVER =
            new Layout(
                    Path.of("/opt/salmonbus-dev"),
                    Path.of("/etc/salmonbus-dev"),
                    Path.of("/srv/salmonbus-dev"),
                    Path.of("/run/lock"),
                    Path.of("/etc/systemd/system"));

    Path tools() {
        return root.resolve("tools");
    }
}
