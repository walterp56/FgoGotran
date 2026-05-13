package com.fgogotran.di

import android.content.Context
import com.fgogotran.translation.TranslationCacheDb
import com.fgogotran.translation.TranslationCacheDao
import com.fgogotran.util.FgoLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt DI module providing singleton-scoped database instances.
 *
 * Translation pipeline providers (TermDatabase, TermDao) are removed while
 * the debug background-detection mode is active; only TranslationCache
 * remains because HistoryScreen reads from it.
 */
@Module
@InstallIn(SingletonComponent::class)
object DiModule {

    @Provides
    @Singleton
    fun provideTranslationCacheDb(@ApplicationContext context: Context): TranslationCacheDb {
        FgoLogger.info("DI", "Providing TranslationCacheDb")
        return TranslationCacheDb.create(context)
    }

    @Provides
    @Singleton
    fun provideTranslationCacheDao(db: TranslationCacheDb): TranslationCacheDao {
        return db.cacheDao()
    }
}
