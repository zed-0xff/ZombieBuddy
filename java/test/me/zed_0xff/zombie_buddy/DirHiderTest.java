package me.zed_0xff.zombie_buddy;

import me.zed_0xff.zombie_buddy.DirHider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class DirHiderTest {
    @Nested
    class WhenEnabled {
        @BeforeAll
        static void setUp() {
            DirHider.setEnabled(true);
        }

        @Test
        void shouldReturnStatus() {
            assertTrue(DirHider.isEnabled());
        }

        @Test
        void shouldHide_null() {
            assertFalse(DirHider.shouldHide(null));
        }

        @Test
        void shouldHide_tmp() {
            assertTrue(DirHider.shouldHide(new File("tmp")));
            assertTrue(DirHider.shouldHide(new File("TMP")));
            assertTrue(DirHider.shouldHide(new File("tmp/")));
            assertTrue(DirHider.shouldHide(new File("/tmp")));
            assertTrue(DirHider.shouldHide(new File("/tmp/")));
            assertTrue(DirHider.shouldHide(new File("/tmp/foo")));
            assertTrue(DirHider.shouldHide(new File("tmp/foo")));
            assertTrue(DirHider.shouldHide(new File("foo/tmp")));
            assertTrue(DirHider.shouldHide(new File("foo/tmp/")));
            assertTrue(DirHider.shouldHide(new File("/foo/tmp/")));
            assertTrue(DirHider.shouldHide(new File("/foo/TmP/")));
            assertTrue(DirHider.shouldHide(new File("/tmp/foo/tmp")));

            assertTrue(DirHider.shouldHide(new File("/foo/tmp/bar/")));
            assertTrue(DirHider.shouldHide(new File("/foo/tmp/bar")));
            assertTrue(DirHider.shouldHide(new File("foo/tmp/bar/")));
            assertTrue(DirHider.shouldHide(new File("FOO/tmp/bar")));

            assertFalse(DirHider.shouldHide(new File("/foo/tmpx/bar/")));
            assertFalse(DirHider.shouldHide(new File("/foo/xtmp/bar")));
            assertFalse(DirHider.shouldHide(new File("foo/tmpx/bar/")));
            assertFalse(DirHider.shouldHide(new File("FOO/xtmp/bar")));
        }

        @Test
        void shouldHide_otherNames() {
            assertFalse(DirHider.shouldHide(new File("")));
            assertFalse(DirHider.shouldHide(new File("foo")));
            assertFalse(DirHider.shouldHide(new File("tmpx")));
            assertFalse(DirHider.shouldHide(new File("xtmp")));
        }

        @Test
        void shouldHide_File_String() {
            assertTrue(DirHider.shouldHide(new File("tmp"), "tmp"));
            assertTrue(DirHider.shouldHide(new File("tmp"), "foo"));
            assertTrue(DirHider.shouldHide(new File("foo"), "tmp"));
            assertTrue(DirHider.shouldHide(new File("foo"), "tmp/bar"));
            assertTrue(DirHider.shouldHide(new File("bar/tmp"), "foo"));

            assertTrue(DirHider.shouldHide(new File("tmp/foo"), "foo"));

            assertFalse(DirHider.shouldHide(new File("foo"), "foo"));
            assertFalse(DirHider.shouldHide(new File("tmpx"), "foo"));
            assertFalse(DirHider.shouldHide(new File("foo"), "tmpx"));
            assertFalse(DirHider.shouldHide(new File("xtmp"), "foo"));
            assertFalse(DirHider.shouldHide(new File("foo"), "xtmp"));
        }

        @Test
        void shouldHide_URI_File() throws Exception {
            assertTrue(DirHider.shouldHide(new URI("file:///tmp"), new File("tmp")));
            assertTrue(DirHider.shouldHide(new URI("file:///tmp"), new File("foo")));
            assertTrue(DirHider.shouldHide(new URI("file:///foo"), new File("tmp")));
            assertTrue(DirHider.shouldHide(new URI("file:///foo"), new File("tmp/bar")));
            assertTrue(DirHider.shouldHide(new URI("file:///bar/tmp"), new File("foo")));
            assertTrue(DirHider.shouldHide(new URI("file:///tmp/foo"), new File("foo")));

            assertFalse(DirHider.shouldHide(new URI("file:///foo"), new File("foo")));
            assertFalse(DirHider.shouldHide(new URI("file:///tmpx"), new File("foo")));
            assertFalse(DirHider.shouldHide(new URI("file:///oo"), new File("tmpx")));
            assertFalse(DirHider.shouldHide(new URI("file:///xtmp"), new File("foo")));
            assertFalse(DirHider.shouldHide(new URI("file:///foo"), new File("xtmp")));
        }
    }

    @Nested
    class WhenDisabled {
        @BeforeAll
        static void setUp() {
            DirHider.setEnabled(false);
        }

        @Test
        void shouldReturnStatus() {
            assertFalse(DirHider.isEnabled());
        }

        @Test
        void shouldHide_null() {
            assertFalse(DirHider.shouldHide(null));
        }

        @Test
        void shouldHide_tmp() {
            assertFalse(DirHider.shouldHide(new File("tmp")));
            assertFalse(DirHider.shouldHide(new File("TMP")));
            assertFalse(DirHider.shouldHide(new File("tmp/")));
            assertFalse(DirHider.shouldHide(new File("/tmp")));
            assertFalse(DirHider.shouldHide(new File("/tmp/")));
            assertFalse(DirHider.shouldHide(new File("/tmp/foo")));
            assertFalse(DirHider.shouldHide(new File("tmp/foo")));
            assertFalse(DirHider.shouldHide(new File("foo/tmp")));
            assertFalse(DirHider.shouldHide(new File("foo/tmp/")));
            assertFalse(DirHider.shouldHide(new File("/foo/tmp/")));
            assertFalse(DirHider.shouldHide(new File("/foo/TmP/")));
            assertFalse(DirHider.shouldHide(new File("/tmp/foo/tmp")));

            assertFalse(DirHider.shouldHide(new File("/foo/tmp/bar/")));
            assertFalse(DirHider.shouldHide(new File("/foo/tmp/bar")));
            assertFalse(DirHider.shouldHide(new File("foo/tmp/bar/")));
            assertFalse(DirHider.shouldHide(new File("FOO/tmp/bar")));

            assertFalse(DirHider.shouldHide(new File("/foo/tmpx/bar/")));
            assertFalse(DirHider.shouldHide(new File("/foo/xtmp/bar")));
            assertFalse(DirHider.shouldHide(new File("foo/tmpx/bar/")));
            assertFalse(DirHider.shouldHide(new File("FOO/xtmp/bar")));
        }

        @Test
        void shouldHide_otherNames() {
            assertFalse(DirHider.shouldHide(new File("")));
            assertFalse(DirHider.shouldHide(new File("foo")));
            assertFalse(DirHider.shouldHide(new File("tmpx")));
            assertFalse(DirHider.shouldHide(new File("xtmp")));
        }

        @Test
        void shouldHide_File_String() {
            assertFalse(DirHider.shouldHide(new File("tmp"), "tmp"));
            assertFalse(DirHider.shouldHide(new File("tmp"), "foo"));
            assertFalse(DirHider.shouldHide(new File("foo"), "tmp"));
            assertFalse(DirHider.shouldHide(new File("foo"), "tmp/bar"));
            assertFalse(DirHider.shouldHide(new File("bar/tmp"), "foo"));

            assertFalse(DirHider.shouldHide(new File("tmp/foo"), "foo"));

            assertFalse(DirHider.shouldHide(new File("foo"), "foo"));
            assertFalse(DirHider.shouldHide(new File("tmpx"), "foo"));
            assertFalse(DirHider.shouldHide(new File("foo"), "tmpx"));
            assertFalse(DirHider.shouldHide(new File("xtmp"), "foo"));
            assertFalse(DirHider.shouldHide(new File("foo"), "xtmp"));
        }

        @Test
        void shouldHide_URI_File() throws Exception {
            assertFalse(DirHider.shouldHide(new URI("file:///tmp"), new File("tmp")));
            assertFalse(DirHider.shouldHide(new URI("file:///tmp"), new File("foo")));
            assertFalse(DirHider.shouldHide(new URI("file:///foo"), new File("tmp")));
            assertFalse(DirHider.shouldHide(new URI("file:///foo"), new File("tmp/bar")));
            assertFalse(DirHider.shouldHide(new URI("file:///bar/tmp"), new File("foo")));
            assertFalse(DirHider.shouldHide(new URI("file:///tmp/foo"), new File("foo")));

            assertFalse(DirHider.shouldHide(new URI("file:///foo"), new File("foo")));
            assertFalse(DirHider.shouldHide(new URI("file:///tmpx"), new File("foo")));
            assertFalse(DirHider.shouldHide(new URI("file:///oo"), new File("tmpx")));
            assertFalse(DirHider.shouldHide(new URI("file:///xtmp"), new File("foo")));
            assertFalse(DirHider.shouldHide(new URI("file:///foo"), new File("xtmp")));
        }
    }
}
