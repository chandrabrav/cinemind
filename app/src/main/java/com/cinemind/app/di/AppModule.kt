package com.cinemind.app.di

import android.content.Context
import com.cinemind.core.data.local.dao.ClipDao
import com.cinemind.core.data.local.dao.EditBriefDao
import com.cinemind.core.data.local.dao.MediaAssetDao
import com.cinemind.core.data.local.dao.ProjectDao
import com.cinemind.core.data.local.dao.TrackDao
import com.cinemind.core.data.local.database.AppDatabase
import com.cinemind.core.data.repository.ClipRepositoryImpl
import com.cinemind.core.data.repository.EditBriefRepositoryImpl
import com.cinemind.core.data.repository.MediaAssetRepositoryImpl
import com.cinemind.core.data.repository.ProjectRepositoryImpl
import com.cinemind.core.data.repository.TrackRepositoryImpl
import com.cinemind.core.domain.repository.ClipRepository
import com.cinemind.core.domain.repository.EditBriefRepository
import com.cinemind.core.domain.repository.MediaAssetRepository
import com.cinemind.core.domain.repository.ProjectRepository
import com.cinemind.core.domain.repository.TrackRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.getDatabase(context)

    @Provides
    fun provideProjectDao(database: AppDatabase): ProjectDao = database.projectDao()

    @Provides
    fun provideTrackDao(database: AppDatabase): TrackDao = database.trackDao()

    @Provides
    fun provideClipDao(database: AppDatabase): ClipDao = database.clipDao()

    @Provides
    fun provideMediaAssetDao(database: AppDatabase): MediaAssetDao = database.mediaAssetDao()

    @Provides
    fun provideEditBriefDao(database: AppDatabase): EditBriefDao = database.editBriefDao()

    @Provides
    fun provideCaptionDao(database: AppDatabase): com.cinemind.core.data.local.dao.CaptionDao = database.captionDao()

    @Provides
    fun provideTextLayerDao(database: AppDatabase): com.cinemind.core.data.local.dao.TextLayerDao = database.textLayerDao()

    @Provides
    fun provideGenerationJobDao(database: AppDatabase): com.cinemind.core.data.local.dao.GenerationJobDao = database.generationJobDao()
}

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    fun provideProjectRepository(database: AppDatabase): ProjectRepository =
        ProjectRepositoryImpl(database)

    @Provides
    fun provideTrackRepository(trackDao: TrackDao): TrackRepository =
        TrackRepositoryImpl(trackDao)

    @Provides
    fun provideClipRepository(clipDao: ClipDao): ClipRepository =
        ClipRepositoryImpl(clipDao)

    @Provides
    fun provideMediaAssetRepository(mediaAssetDao: MediaAssetDao): MediaAssetRepository =
        MediaAssetRepositoryImpl(mediaAssetDao)

    @Provides
    fun provideEditBriefRepository(editBriefDao: EditBriefDao): EditBriefRepository =
        EditBriefRepositoryImpl(editBriefDao)

    @Provides
    fun provideCaptionRepository(dao: com.cinemind.core.data.local.dao.CaptionDao): com.cinemind.core.domain.repository.CaptionRepository =
        com.cinemind.core.data.repository.CaptionRepositoryImpl(dao)

    @Provides
    fun provideTextLayerRepository(dao: com.cinemind.core.data.local.dao.TextLayerDao): com.cinemind.core.domain.repository.TextLayerRepository =
        com.cinemind.core.data.repository.TextLayerRepositoryImpl(dao)

    @Provides
    fun provideGenerationJobRepository(dao: com.cinemind.core.data.local.dao.GenerationJobDao): com.cinemind.core.domain.repository.GenerationJobRepository =
        com.cinemind.core.data.repository.GenerationJobRepositoryImpl(dao)
}
