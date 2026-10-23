package com.resq.support;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IsolatedTestEnvironmentTest {
    @Test void missingOrInvalidGuardIsRejectedBeforeAnyConnection() {
        assertThrows(IllegalStateException.class, () -> IsolatedTestEnvironment.requireGuard(null));
        assertThrows(IllegalStateException.class, () -> IsolatedTestEnvironment.requireGuard(""));
        assertThrows(IllegalStateException.class, () -> IsolatedTestEnvironment.requireGuard("development"));
        assertDoesNotThrow(() -> IsolatedTestEnvironment.requireGuard("0123456789abcdef0123456789abcdef"));
    }
}
