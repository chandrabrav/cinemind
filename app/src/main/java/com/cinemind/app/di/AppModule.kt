package com.cinemind.app.di

import android.content.Context
import com.cinemind.core.data.local.database.AppDatabase
import com.cinemind.core.data.repository.*
import com.cinemind.core.domain.repository.*
import dagger.Module
import dagger.Provides
import hilt.android.ApplicationContext
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    @Provides
    fun provideProjectDao(database: AppDatabase): ProjectDao = database.projectDao()

    @Provides
    fun provideTrackDao(database: AppDatabase): TrackDao = database.trackDao()

    @Provides
    fun provideClipDao(database: AppDatabase): ClipDao = database.clipDao()

    @Provides
    fun provideMediaAssetDao(database: AppDatabase): MediaAssetDao = database.mediaAssetDao()
}

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    fun provideProjectRepository(
        projectDao: ProjectDao,
        trackDao: TrackDao,
        clipDao: ClipDao,
        mediaAssetDao: MediaAssetDao
    ): ProjectRepository =
        ProjectRepositoryImpl(projectDao, trackDao, clipDao, mediaAssetDao)

    @Provides
    fun provideTrackRepository(trackDao: TrackDao): TrackRepository =
        TrackRepositoryImpl(trackDao)

    @Provides
    fun provideClipRepository(clipDao: ClipDao): ClipRepository =
        ClipRepositoryImpl(clipDao)

    @Provides
    fun provideMediaAssetRepository(mediaAssetDao: MediaAssetDao): MediaAssetRepository =
        MediaAssetRepositoryImpl(mediaAssetDao)
}