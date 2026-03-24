package com.claude.remote.core.ssh.di

import com.claude.remote.core.ssh.SshClient
import com.claude.remote.core.ssh.SshClientImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SshModule {
    @Binds
    @Singleton
    abstract fun bindSshClient(impl: SshClientImpl): SshClient
}
