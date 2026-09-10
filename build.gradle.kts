plugins {
    id("com.android.application") version "9.3.2" apply false
    // AGP 9 compiles Kotlin itself (built-in Kotlin, KGP 2.2.10), so
    // org.jetbrains.kotlin.android is neither needed nor allowed any more.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
