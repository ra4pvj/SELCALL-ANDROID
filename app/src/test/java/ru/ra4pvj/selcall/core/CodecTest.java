package ru.ra4pvj.selcall.core;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.InputStream;

public class CodecTest {
    @Test
    public void selfTest() throws Exception {
        try (InputStream rec = getClass().getClassLoader().getResourceAsStream("SELCALL_from_1111_to_1234.wav")) {
            assertTrue(SelfTest.run(rec, System.out::println));
        }
    }
}
