package com.celoext

import com.celoext.data.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Version comparison used by the updater: installed versionName vs the release tag. */
class UpdaterTest {
    @Test fun newerBuildOfSameVersionWins() {
        assertTrue(Updater.compare("v0.0.2-beta-1-build37", "0.0.2 beta-1 build 36") > 0)
        assertTrue(Updater.compare("v0.0.2-beta-1-build36", "0.0.2 beta-1 build 37") < 0)
        assertEquals(0, Updater.compare("v0.0.2-beta-1-build36", "0.0.2 beta-1 build 36"))
    }

    @Test fun newerVersionWinsOverBuildNumber() {
        assertTrue(Updater.compare("v0.0.2-beta-1-build1", "0.0.1 beta-9 build 99") > 0)
        assertTrue(Updater.compare("v0.1.0-beta-1-build1", "0.0.9 beta-9 build 99") > 0)
    }

    @Test fun stageOrder() {
        assertTrue(Updater.compare("v0.0.2-beta-2", "0.0.2 beta-1") > 0)
        assertTrue(Updater.compare("v0.0.2-rc-1", "0.0.2 beta-9") > 0)
        assertTrue(Updater.compare("v0.0.2", "0.0.2-rc-9") > 0)
    }

    @Test fun oldUnknownTagsDoNotBeatInstalledVersion() {
        assertTrue(Updater.compare("vunknown-build35", "0.0.1 beta-5") < 0)
    }
}
