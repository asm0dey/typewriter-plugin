package com.github.asm0dey.typewriter

import com.intellij.openapi.Disposable
import com.intellij.testFramework.common.ThreadLeakTracker
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase5
import com.intellij.testFramework.junit5.RunInEdt

// configureByText mutates PSI, so tests need EDT + the write-intent lock.
@RunInEdt(writeIntent = true)
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
            ThreadLeakTracker.longRunningThreadCreated(Disposable { }, "SystemPropertyWatcher")
        }
    }
}
