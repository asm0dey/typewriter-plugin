package com.github.asm0dey.typewriter

import com.intellij.testFramework.common.ThreadLeakTracker
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase5

// `@RunInEdt(writeIntent = true)` used to live here, since most subclasses mutate PSI via
// configureByText and need EDT + the write-intent lock for that. It moved to each concrete
// subclass that needs it (Task 11): EDT-ness turned out to be a property of what a given test
// *does*, not of this fixture, once RunServiceTest needed to be the first test that must NOT run
// on the EDT. RunServiceTest calls `runBlocking { svc.run(...) }`, and RunService.run does
// `withContext(Dispatchers.EDT)` to guarantee the document mutation it wraps happens on the EDT
// (see RunService's own kdoc) -- if the calling thread were itself the EDT, blocked inside
// runBlocking, that withContext could never be serviced and the test would hang forever. Every
// other existing test still carries the annotation directly; only RunServiceTest omits it.
abstract class TypeWriterFixtureTestCase : LightJavaCodeInsightFixtureTestCase5() {
    // No file-based fixtures are used (only configureByText), so no test data directory exists;
    // the default tries to resolve one against a full intellij-community checkout and throws.
    override fun getRelativePath(): String = ""

    companion object {
        init {
            // Touching the AWT event queue (via @RunInEdt) lazily starts sun.awt.UNIXToolkit's
            // "SystemPropertyWatcher" background thread on Linux, regardless of headless mode.
            // It's a one-time, JVM-lifetime thread the platform's own allowlist doesn't yet know
            // about on this JDK/build, so register it as long-running to avoid a false leak report.
            ThreadLeakTracker.longRunningThreadCreated({ }, "SystemPropertyWatcher")
        }
    }
}
