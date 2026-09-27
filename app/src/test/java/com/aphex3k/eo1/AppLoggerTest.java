package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class AppLoggerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File logFile;
    private AppLogger logger;

    @Before
    public void setUp() throws Exception {
        logFile = new File(temp.getRoot(), "test.log");
        logger = new AppLogger(logFile);
    }

    @Test
    public void emptyTailJsonIsEmptyArray() {
        assertEquals("[]", logger.tailJson(10));
    }

    @Test
    public void tailJsonOrdersOldestFirst() {
        logger.info("tag", "msg1");
        logger.info("tag", "msg2");
        logger.info("tag", "msg3");
        String json = logger.tailJson(10);
        int i1 = json.indexOf("msg1");
        int i2 = json.indexOf("msg2");
        int i3 = json.indexOf("msg3");
        assertTrue(i1 >= 0 && i2 > i1 && i3 > i2);
    }

    @Test
    public void tailReturnsLastN() {
        for (int i = 0; i < 505; i++) {
            logger.info("t", "MM" + i);
        }
        String tail = logger.tail(3);
        String[] lines = tail.split("\n");
        assertEquals(3, lines.length);
        assertTrue(tail.contains("MM504"));
        // Oldest (MM0) must have been evicted from the ring.
        assertFalse(tail.contains("MM0\n"));
        // The first of the last three is MM502.
        assertTrue(lines[0].contains("MM502"));
    }

    @Test
    public void tailClampsToCapacity() {
        for (int i = 0; i < 10; i++) {
            logger.info("t", "X" + i);
        }
        String[] lines = logger.tail(100).split("\n");
        assertEquals(10, lines.length);
    }

    @Test
    public void writesToFile() {
        logger.info("tag", "hello disk");
        logger.error("tag", "boom");
        String tailFile = logger.tailFile(10);
        assertTrue(tailFile.contains("hello disk"));
        assertTrue(tailFile.contains("boom"));
        assertTrue(tailFile.contains("[tag]"));
    }

    @Test
    public void rotatesWhenOverCap() {
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            pad.append('x');
        }
        String big = pad.toString();
        for (int i = 0; i < 4000; i++) {
            logger.info("t", big + " i=" + i);
        }
        File rotated = new File(temp.getRoot(), "test.log.1");
        assertTrue("expected rotated file to exist", rotated.exists());
        assertTrue("expected active log to exist after rotation", logFile.exists());
    }
}
