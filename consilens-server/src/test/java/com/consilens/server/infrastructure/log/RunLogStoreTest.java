package com.consilens.server.infrastructure.log;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunLogStoreTest {

    @TempDir
    Path tempDir;

    private RunLogStore store;

    @BeforeEach
    void setUp() {
        store = new RunLogStore(tempDir, true);
    }

    @AfterEach
    void tearDown() {
        store.closeAll();
    }

    @Test
    void shouldAppendAndReadTail() {
        store.beginRun("inst_1");
        store.append(event(Level.INFO, "hello"));
        store.append(event(Level.WARN, "warn"));
        store.endRun("inst_1");

        RunLogStore.LogPage page = store.readTail("inst_1", 10);

        assertTrue(page.isAvailable());
        assertEquals(2L, page.getNextOffset());
        assertTrue(page.getContent().contains("hello"));
        assertTrue(page.getContent().contains("WARN"));
    }

    @Test
    void shouldPageFromOffset() {
        store.beginRun("inst_2");
        IntStream.range(0, 5).forEach(i -> store.append(event(Level.INFO, "line-" + i)));
        store.endRun("inst_2");

        RunLogStore.LogPage first = store.read("inst_2", 0, 2);
        assertEquals(2L, first.getNextOffset());
        assertTrue(first.isHasMore());
        assertTrue(first.getContent().contains("line-0"));
        assertFalse(first.getContent().contains("line-2"));

        RunLogStore.LogPage rest = store.read("inst_2", first.getNextOffset(), 10);
        assertEquals(5L, rest.getNextOffset());
        assertFalse(rest.isHasMore());
        assertTrue(rest.getContent().contains("line-4"));
    }

    @Test
    void shouldReturnUnavailableWhenNoFile() {
        RunLogStore.LogPage page = store.read("missing", 0, 10);

        assertFalse(page.isAvailable());
        assertEquals("", page.getContent());
    }

    @Test
    void shouldSanitizeInstanceKey() {
        assertEquals(tempDir.resolve("a_b_c.log"), store.fileFor("a/b c"));
        assertEquals(tempDir.resolve("unknown.log"), store.fileFor(""));
    }

    @Test
    void shouldIgnoreEventsWithoutMdcContext() {
        store.append(event(Level.INFO, "no context"));

        assertFalse(store.read("unknown", 0, 10).isAvailable());
    }

    @Test
    void shouldNotWriteWhenDisabled() {
        RunLogStore disabled = new RunLogStore(tempDir.resolve("off"), false);
        disabled.beginRun("inst_3");
        disabled.append(event(Level.INFO, "ignored"));
        disabled.endRun("inst_3");

        assertFalse(disabled.read("inst_3", 0, 10).isAvailable());
    }

    private LogEvent event(Level level, String message) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName("com.consilens.test")
                .setLevel(level)
                .setMessage(new SimpleMessage(message))
                .setTimeMillis(System.currentTimeMillis())
                .setThreadName(Thread.currentThread().getName())
                .build();
    }
}
