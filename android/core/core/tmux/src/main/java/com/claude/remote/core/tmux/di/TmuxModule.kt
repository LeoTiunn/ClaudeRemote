package com.claude.remote.core.tmux.di

import com.claude.remote.core.tmux.TmuxSessionManager
import com.claude.remote.core.tmux.TmuxSessionManagerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TmuxModule {
    @Binds
    @Singleton
    abstract fun bindTmuxSessionManager(impl: TmuxSessionManagerImpl): TmuxSessionManager
}
