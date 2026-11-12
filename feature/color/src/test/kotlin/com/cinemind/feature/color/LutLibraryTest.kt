package com.cinemind.feature.color

import com.cinemind.feature.color.library.LutLibrary
import org.junit.Assert.assertEquals
import org.junit.Test

class LutLibraryTest {

    @Test fun `git blob hash matches git's own value`() {
        // `printf 'hello\n' | git hash-object --stdin`
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", LutLibrary.gitBlobSha1("hello\n".toByteArray()))
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", LutLibrary.gitBlobSha1(ByteArray(0)))
    }
}
