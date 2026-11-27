package me.zed_0xff.zombie_buddy.patches.experimental;

import static org.junit.jupiter.api.Assertions.*;

import me.zed_0xff.zombie_buddy.Callbacks;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import sun.misc.Signal;
import sun.misc.SignalHandler;

import java.lang.reflect.Field;
import java.util.List;

class JavaStateDumperTest {
    @Test
    void unsupportedSignalPreservesKeyboardCallbackAndIdempotentInitialization() throws Exception {
        Field initialized = JavaStateDumper.class.getDeclaredField("_initialized");
        initialized.setAccessible(true);
        Field callbacks = Callbacks.Callback.class.getDeclaredField("callbacks");
        callbacks.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Runnable> listeners = (List<Runnable>) callbacks.get(Callbacks.onDisplayCreate);
        boolean wasInitialized = initialized.getBoolean(null);
        List<Runnable> originalListeners = List.copyOf(listeners);
        initialized.setBoolean(null, false);
        try (MockedConstruction<Signal> signals = Mockito.mockConstruction(Signal.class);
                MockedStatic<Signal> registration = Mockito.mockStatic(Signal.class)) {
            registration
                    .when(
                            () ->
                                    Signal.handle(
                                            Mockito.any(Signal.class),
                                            Mockito.any(SignalHandler.class)))
                    .thenThrow(new IllegalArgumentException("SIGINFO unavailable"));
            assertDoesNotThrow(JavaStateDumper::init);
            assertEquals(1, signals.constructed().size());
            assertEquals(originalListeners.size() + 1, listeners.size());
            assertTrue(initialized.getBoolean(null));
            assertDoesNotThrow(JavaStateDumper::init);
            assertEquals(1, signals.constructed().size());
            assertEquals(originalListeners.size() + 1, listeners.size());
        } finally {
            initialized.setBoolean(null, wasInitialized);
            listeners.clear();
            listeners.addAll(originalListeners);
        }
    }
}
