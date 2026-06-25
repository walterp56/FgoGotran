package com.fgogotran

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point for Hilt dependency injection.
 *
 * The @HiltAndroidApp annotation triggers Hilt's code generator,
 * which creates the base class for the DI component hierarchy.
 * No manual initialization is needed here — all @Singleton @Inject
 * providers are created lazily on first access.
 */
@HiltAndroidApp
class App : Application()
