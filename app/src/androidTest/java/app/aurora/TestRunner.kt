package app.aurora

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Runs device tests on the fake account, as the JVM tests do. */
class TestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, TestAuroraApplication::class.java.name, context)
}
