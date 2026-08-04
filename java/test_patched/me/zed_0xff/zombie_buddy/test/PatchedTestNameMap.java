package me.zed_0xff.zombie_buddy.test;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import testjar.FieldValueTarget;

public class PatchedTestNameMap {
    @Test
    void testNameMapContainsResolvedFieldNames() {
        FieldValueTarget t = new FieldValueTarget();
        FieldValueTarget.capturedNameMap = null;

        t.captureNameMap();

        assertNotNull(FieldValueTarget.capturedNameMap);
        assertEquals("name", FieldValueTarget.capturedNameMap.get("logicalName"));
        assertEquals("counter", FieldValueTarget.capturedNameMap.get("counter"));
    }
}
