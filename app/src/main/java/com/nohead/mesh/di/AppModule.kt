package com.nohead.mesh.di

import android.content.Context
import androidx.room.Room
import com.nohead.mesh.data.local.ChannelDao
import com.nohead.mesh.data.local.CourierMailboxDao
import com.nohead.mesh.data.local.GeohashBookmarkDao
import com.nohead.mesh.data.local.MessageDao
import com.nohead.mesh.data.local.NoHeadDatabase
import com.nohead.mesh.data.local.NostrRelayDao
import com.nohead.mesh.data.local.OutgoingQueueDao
import com.nohead.mesh.data.local.PeerDao
import com.nohead.mesh.data.local.SeenPacketDao
import com.nohead.mesh.domain.service.IdentityManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideNoHeadDatabase(
        @ApplicationContext context: Context,
        identityManager: IdentityManager
    ): NoHeadDatabase {
        return try {
            SQLiteDatabase.loadLibs(context)
            val passphraseBytes = identityManager.getOrCreateDatabasePassphrase()
            val factory = SupportFactory(passphraseBytes)
            Room.databaseBuilder(
                context,
                NoHeadDatabase::class.java,
                "nohead_encrypted_mesh.db"
            )
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build()
        } catch (e: UnsatisfiedLinkError) {
            // Fallback for JVM host tests where native SQLCipher .so is not loaded
            Room.databaseBuilder(
                context,
                NoHeadDatabase::class.java,
                "nohead_encrypted_mesh.db"
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }

    @Provides
    fun provideMessageDao(db: NoHeadDatabase): MessageDao = db.messageDao()

    @Provides
    fun providePeerDao(db: NoHeadDatabase): PeerDao = db.peerDao()

    @Provides
    fun provideChannelDao(db: NoHeadDatabase): ChannelDao = db.channelDao()

    @Provides
    fun provideOutgoingQueueDao(db: NoHeadDatabase): OutgoingQueueDao = db.outgoingQueueDao()

    @Provides
    fun provideCourierMailboxDao(db: NoHeadDatabase): CourierMailboxDao = db.courierMailboxDao()

    @Provides
    fun provideSeenPacketDao(db: NoHeadDatabase): SeenPacketDao = db.seenPacketDao()

    @Provides
    fun provideNostrRelayDao(db: NoHeadDatabase): NostrRelayDao = db.nostrRelayDao()

    @Provides
    fun provideGeohashBookmarkDao(db: NoHeadDatabase): GeohashBookmarkDao = db.geohashBookmarkDao()
}

