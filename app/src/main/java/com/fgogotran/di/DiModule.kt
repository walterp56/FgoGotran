package com.fgogotran.di

import android.content.Context
import com.fgogotran.terminology.TermDao
import com.fgogotran.terminology.TermDatabase
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
 * Hilt DI module providing singleton-scoped database and DAO instances.
 *
 * Room databases are expensive to create, so they're provided as @Singleton
 * and created lazily on first injection. DAOs are extracted from the database
 * instance rather than created independently.
 */
@Module
@InstallIn(SingletonComponent::class)
object DiModule {

    @Provides
    @Singleton
    fun provideTermDatabase(@ApplicationContext context: Context): TermDatabase {
        FgoLogger.info("DI", "Providing TermDatabase")
        return TermDatabase.create(context)
    }

    @Provides
    @Singleton
    fun provideTermDao(db: TermDatabase): TermDao {
        return db.termDao()
    }

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
