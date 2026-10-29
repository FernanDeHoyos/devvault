package com.devvault.runtime.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class LocalPortProbeTest {

    private final LocalPortProbe probe = new LocalPortProbe();

    @Test
    void parsesLinuxSsOutputAndFiltersToProcessTree() {
        List<String> output = List.of(
                "State Recv-Q Send-Q Local Address:Port Peer Address:Port Process",
                "LISTEN 0 4096 127.0.0.1:5174 0.0.0.0:* users:((\"node\",pid=2345,fd=19))",
                "LISTEN 0 4096 0.0.0.0:8080 0.0.0.0:* users:((\"java\",pid=9999,fd=21))");

        assertEquals(List.of(5174), probe.parse(output, Set.of(2345L)));
    }

    @Test
    void keepsParsingLsofOutput() {
        List<String> output = List.of(
                "COMMAND PID USER FD TYPE DEVICE SIZE/OFF NODE NAME",
                "java 2345 user 22u IPv4 0x123 0t0 TCP 127.0.0.1:8080 (LISTEN)");

        assertEquals(List.of(8080), probe.parse(output, Set.of(2345L)));
    }
}
