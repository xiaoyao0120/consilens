package com.consilens.cluster.application;

import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsilensApplicationMasterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldRegisterLaunchCoordinatorAndReportSuccess() throws Exception {
        Path descriptor = temporaryDirectory.resolve("submission-descriptor.yaml");
        Files.writeString(descriptor, "source: {}\n");
        RecordingAmClient client = new RecordingAmClient();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        ConsilensApplicationMaster master = new ConsilensApplicationMaster(new String[]{
                "--coordinator-class", TestCoordinator.class.getName(),
                "--descriptor", descriptor.toString(),
                "--staging-dir", "hdfs://namenode/apps/consilens/staging/submission-1",
        }, client, new PrintStream(error));

        master.run();

        assertTrue(client.registered);
        assertEquals(FinalApplicationStatus.SUCCEEDED, client.finishedStatus);
        assertTrue(client.closed);
        String errors = error.toString(StandardCharsets.UTF_8);
        assertFalse(errors.contains("Unable to launch coordinator"));
        assertFalse(errors.contains("AM heartbeat failed"));
    }

    @Test
    void shouldPassSecretsArgumentThroughToCoordinator() throws Exception {
        Path descriptor = temporaryDirectory.resolve("submission-descriptor.yaml");
        Path secrets = temporaryDirectory.resolve("submission-secrets.properties");
        Files.writeString(descriptor, "source: {}\n");
        Files.writeString(secrets, "MYSQL_USER=root\n");
        RecordingAmClient client = new RecordingAmClient();
        ConsilensApplicationMaster master = new ConsilensApplicationMaster(new String[]{
                "--coordinator-class", RecordingCoordinator.class.getName(),
                "--descriptor", descriptor.toString(),
                "--secrets", secrets.toString(),
        }, client, new PrintStream(new ByteArrayOutputStream()));

        master.run();

        assertEquals(FinalApplicationStatus.SUCCEEDED, client.finishedStatus);
        assertEquals(List.of(descriptor.toString(), secrets.toString()), RecordingCoordinator.receivedArguments);
    }

    @Test
    void shouldRejectUnknownOrIncompleteArguments() {
        RecordingAmClient client = new RecordingAmClient();

        assertThrows(IllegalArgumentException.class, () -> new ConsilensApplicationMaster(
                new String[]{"--descriptor", "x.yaml"}, client, new PrintStream(new ByteArrayOutputStream())).run());
        assertThrows(IllegalArgumentException.class, () -> new ConsilensApplicationMaster(
                new String[]{"--coordinator-class", "a.B", "--descriptor", "x.yaml", "--bogus", "v"},
                client, new PrintStream(new ByteArrayOutputStream())).run());
        assertEquals(0, client.allocations);
    }

    /** Coordinator main that returns normally, i.e. exit code zero. */
    public static final class TestCoordinator {
        public static void main(String[] args) {
            // normal return signals success
        }
    }

    /** Coordinator main that records the localized file arguments it received. */
    public static final class RecordingCoordinator {
        static List<String> receivedArguments;

        public static void main(String[] args) {
            receivedArguments = List.of(args[1], args[2]);
        }
    }

    private static final class RecordingAmClient implements ConsilensApplicationMaster.AmClient {
        private boolean registered;
        private int allocations;
        private FinalApplicationStatus finishedStatus;
        private boolean closed;

        @Override
        public void register(String host) {
            registered = true;
        }

        @Override
        public void allocate() {
            allocations++;
        }

        @Override
        public void unregister(FinalApplicationStatus status, String diagnostics) {
            finishedStatus = status;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
