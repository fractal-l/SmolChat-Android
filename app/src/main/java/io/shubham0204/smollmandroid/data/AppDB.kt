package io.shubham0204.smollmandroid.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.koin.core.annotation.Single
import java.util.Date

@Database(
    entities = [Chat::class, ChatMessage::class, LLMModel::class, Folder::class],
    version = 4,
)
@TypeConverters(Converters::class)
abstract class AppRoomDatabase : RoomDatabase() {
    abstract fun chatsDao(): ChatsDao

    abstract fun chatMessagesDao(): ChatMessageDao

    abstract fun llmModelDao(): LLMModelDao

    abstract fun folderDao(): FolderDao

    companion object {
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE Chat ADD COLUMN topK INTEGER NOT NULL DEFAULT 40")
                    db.execSQL("ALTER TABLE Chat ADD COLUMN topP REAL NOT NULL DEFAULT 0.95")
                    db.execSQL(
                        "ALTER TABLE Chat ADD COLUMN repeatPenalty REAL NOT NULL DEFAULT 1.1"
                    )
                    db.execSQL("ALTER TABLE Chat ADD COLUMN repeatLastN INTEGER NOT NULL DEFAULT 64")
                    db.execSQL("ALTER TABLE Chat ADD COLUMN seed INTEGER NOT NULL DEFAULT -1")
                }
            }

        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_Chat_folderId ON Chat(folderId)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_ChatMessage_chatId ON ChatMessage(chatId)"
                    )
                }
            }

        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Tasks were removed in favor of character presets. Preserve chats that were
                    // created from tasks as ordinary chats, while dropping task definitions.
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `Chat_new` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `name` TEXT NOT NULL,
                            `systemPrompt` TEXT NOT NULL,
                            `dateCreated` INTEGER NOT NULL,
                            `dateUsed` INTEGER NOT NULL,
                            `llmModelId` INTEGER NOT NULL,
                            `minP` REAL NOT NULL,
                            `temperature` REAL NOT NULL,
                            `topK` INTEGER NOT NULL DEFAULT 40,
                            `topP` REAL NOT NULL DEFAULT 0.95,
                            `repeatPenalty` REAL NOT NULL DEFAULT 1.1,
                            `repeatLastN` INTEGER NOT NULL DEFAULT 64,
                            `seed` INTEGER NOT NULL DEFAULT -1,
                            `nThreads` INTEGER NOT NULL,
                            `useMmap` INTEGER NOT NULL,
                            `useMlock` INTEGER NOT NULL,
                            `contextSize` INTEGER NOT NULL,
                            `contextSizeConsumed` INTEGER NOT NULL,
                            `chatTemplate` TEXT NOT NULL,
                            `folderId` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        INSERT INTO `Chat_new` (
                            `id`, `name`, `systemPrompt`, `dateCreated`, `dateUsed`,
                            `llmModelId`, `minP`, `temperature`, `topK`, `topP`,
                            `repeatPenalty`, `repeatLastN`, `seed`, `nThreads`, `useMmap`,
                            `useMlock`, `contextSize`, `contextSizeConsumed`, `chatTemplate`,
                            `folderId`
                        )
                        SELECT
                            `id`, `name`, `systemPrompt`, `dateCreated`, `dateUsed`,
                            `llmModelId`, `minP`, `temperature`, `topK`, `topP`,
                            `repeatPenalty`, `repeatLastN`, `seed`, `nThreads`, `useMmap`,
                            `useMlock`, `contextSize`, `contextSizeConsumed`, `chatTemplate`,
                            `folderId`
                        FROM `Chat`
                        """.trimIndent()
                    )
                    db.execSQL("DROP TABLE `Chat`")
                    db.execSQL("ALTER TABLE `Chat_new` RENAME TO `Chat`")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_Chat_folderId` ON `Chat` (`folderId`)"
                    )
                    db.execSQL("DROP TABLE IF EXISTS `Task`")
                }
            }
    }
}

@Single
class AppDB(context: Context) {
    private val db =
        Room.databaseBuilder(context, AppRoomDatabase::class.java, "app-database")
            .addMigrations(
                AppRoomDatabase.MIGRATION_1_2,
                AppRoomDatabase.MIGRATION_2_3,
                AppRoomDatabase.MIGRATION_3_4,
            )
            .build()

    /** Get all chats from the database sorted by dateUsed in descending order. */
    fun getChats(): Flow<List<Chat>> = db.chatsDao().getChats()

    fun loadDefaultChat(): Chat =
        runBlocking(Dispatchers.IO) {
            db.withTransaction {
                db.chatsDao().getRecentlyUsedChat()
                    ?: newChat("Untitled").let {
                        it.copy(id = db.chatsDao().insertChat(it))
                    }
            }
        }

    /**
     * Get the most recently used chat from the database. This function might return null, if there
     * are no chats in the database.
     */
    fun getRecentlyUsedChat(): Chat? =
        runBlocking(Dispatchers.IO) { db.chatsDao().getRecentlyUsedChat() }

    /**
     * Adds a new chat to the database initialized with given arguments and returns the new Chat
     * object
     */
    fun addChat(
        chatName: String,
        chatTemplate: String = "",
        systemPrompt: String = "You are a helpful assistant.",
        llmModelId: Long = -1,
    ): Chat =
        runBlocking(Dispatchers.IO) {
            val chat =
                newChat(
                    chatName = chatName,
                    chatTemplate = chatTemplate,
                    systemPrompt = systemPrompt,
                    llmModelId = llmModelId,
                )
            chat.copy(id = db.chatsDao().insertChat(chat))
        }

    private fun newChat(
        chatName: String,
        chatTemplate: String = "",
        systemPrompt: String = "You are a helpful assistant.",
        llmModelId: Long = -1,
    ) = Chat(
        name = chatName,
        systemPrompt = systemPrompt,
        dateCreated = Date(),
        dateUsed = Date(),
        llmModelId = llmModelId,
        contextSize = 2048,
        chatTemplate = chatTemplate,
    )

    /** Update the chat in the database. */
    fun updateChat(modifiedChat: Chat) =
        runBlocking(Dispatchers.IO) { db.chatsDao().updateChat(modifiedChat) }

    fun deleteChat(chat: Chat) =
        runBlocking(Dispatchers.IO) {
            db.withTransaction {
                db.chatMessagesDao().deleteMessages(chat.id)
                db.chatsDao().deleteChat(chat.id)
            }
        }

    fun getChatsCount(): Long = runBlocking(Dispatchers.IO) { db.chatsDao().getChatsCount() }

    fun getChatsForFolder(folderId: Long): Flow<List<Chat>> =
        db.chatsDao().getChatsForFolder(folderId)

    // Chat Messages

    fun getMessages(chatId: Long): Flow<List<ChatMessage>> =
        db.chatMessagesDao().getMessages(chatId)

    fun getMessagesForModel(chatId: Long): List<ChatMessage> =
        runBlocking(Dispatchers.IO) { db.chatMessagesDao().getMessagesForModel(chatId) }

    fun addUserMessage(chatId: Long, message: String) =
        runBlocking(Dispatchers.IO) {
            db.chatMessagesDao()
                .insertMessage(
                    ChatMessage(chatId = chatId, message = message, isUserMessage = true)
                )
        }

    fun addAssistantMessage(chatId: Long, message: String) =
        runBlocking(Dispatchers.IO) {
            db.chatMessagesDao()
                .insertMessage(
                    ChatMessage(chatId = chatId, message = message, isUserMessage = false)
                )
        }

    fun deleteMessage(messageId: Long) =
        runBlocking(Dispatchers.IO) { db.chatMessagesDao().deleteMessage(messageId) }

    fun deleteMessages(chatId: Long) =
        runBlocking(Dispatchers.IO) { db.chatMessagesDao().deleteMessages(chatId) }

    fun deleteMessagesFrom(chatId: Long, messageId: Long) =
        runBlocking(Dispatchers.IO) { db.chatMessagesDao().deleteMessagesFrom(chatId, messageId) }

    // Models

    fun addModel(name: String, url: String, path: String, contextSize: Int, chatTemplate: String) =
        runBlocking(Dispatchers.IO) {
            db.llmModelDao()
                .insertModels(
                    LLMModel(
                        name = name,
                        url = url,
                        path = path,
                        contextSize = contextSize,
                        chatTemplate = chatTemplate,
                    )
                )
        }

    fun getModel(id: Long): LLMModel? =
        runBlocking(Dispatchers.IO) { db.llmModelDao().getModel(id) }

    fun getModels(): Flow<List<LLMModel>> = db.llmModelDao().getAllModels()

    fun getModelsList(): List<LLMModel> =
        runBlocking(Dispatchers.IO) { db.llmModelDao().getAllModelsList() }

    fun updateModel(model: LLMModel) =
        runBlocking(Dispatchers.IO) { db.llmModelDao().updateModel(model) }

    fun deleteModel(id: Long) =
        runBlocking(Dispatchers.IO) {
            db.withTransaction {
                db.chatsDao().clearModelReferences(id)
                db.llmModelDao().deleteModel(id)
            }
        }

    // Folders

    fun getFolders(): Flow<List<Folder>> = db.folderDao().getFolders()

    fun addFolder(folderName: String) =
        runBlocking(Dispatchers.IO) { db.folderDao().insertFolder(Folder(name = folderName)) }

    fun updateFolder(folder: Folder) =
        runBlocking(Dispatchers.IO) { db.folderDao().updateFolder(folder) }

    /** Deletes the folder from the Folder table only */
    fun deleteFolder(folderId: Long) =
        runBlocking(Dispatchers.IO) {
            db.withTransaction {
                db.chatsDao().updateFolderIds(folderId, -1L)
                db.folderDao().deleteFolder(folderId)
            }
        }

    /** Deletes the folder from the Folder table and corresponding chats from the Chat table */
    fun deleteFolderWithChats(folderId: Long) =
        runBlocking(Dispatchers.IO) {
            db.withTransaction {
                db.chatMessagesDao().deleteMessagesInFolder(folderId)
                db.chatsDao().deleteChatsInFolder(folderId)
                db.folderDao().deleteFolder(folderId)
            }
        }

    // Import / Export

    /** Builds a portable archive of all chats and their messages. */
    fun createChatArchive(): ChatArchive =
        runBlocking(Dispatchers.IO) {
            val folders = db.folderDao().getAllFolders()
            val chats = db.chatsDao().getAllChats()
            val messagesByChat = db.chatMessagesDao().getAllMessages().groupBy { it.chatId }
            ChatArchive(
                exportedAt = System.currentTimeMillis(),
                folders = folders.map { ArchivedFolder(id = it.id, name = it.name) },
                chats =
                    chats.map { chat ->
                        ArchivedChat(
                            name = chat.name,
                            systemPrompt = chat.systemPrompt,
                            dateCreated = chat.dateCreated.time,
                            dateUsed = chat.dateUsed.time,
                            minP = chat.minP,
                            temperature = chat.temperature,
                            topK = chat.topK,
                            topP = chat.topP,
                            repeatPenalty = chat.repeatPenalty,
                            repeatLastN = chat.repeatLastN,
                            seed = chat.seed,
                            nThreads = chat.nThreads,
                            useMmap = chat.useMmap,
                            useMlock = chat.useMlock,
                            contextSize = chat.contextSize,
                            chatTemplate = chat.chatTemplate,
                            folderId = chat.folderId,
                            messages =
                                messagesByChat[chat.id].orEmpty().map {
                                    ArchivedMessage(it.message, it.isUserMessage)
                                },
                        )
                    },
            )
        }

    /**
     * Restores chats from an archive. Chats are appended (existing chats are kept) and folders are
     * reused when a folder with the same name is already present. Model references are cleared so
     * the user can pick a locally available model for each imported chat.
     */
    fun importChatArchive(archive: ChatArchive): ChatImportResult =
        runBlocking(Dispatchers.IO) {
            require(archive.version <= ChatArchive.CURRENT_VERSION) {
                "Unsupported archive version ${archive.version}"
            }
            var chatCount = 0
            var messageCount = 0
            db.withTransaction {
                val folderIdByName =
                    db.folderDao()
                        .getAllFolders()
                        .associate { it.name to it.id }
                        .toMutableMap()
                val folderIdMap = mutableMapOf<Long, Long>()
                for (archivedFolder in archive.folders) {
                    val name = archivedFolder.name.ifBlank { "Imported folder" }
                    val folderId =
                        folderIdByName[name]
                            ?: db.folderDao().insertFolder(Folder(name = name)).also {
                                folderIdByName[name] = it
                            }
                    folderIdMap[archivedFolder.id] = folderId
                }
                val importedAt = System.currentTimeMillis()
                for (archivedChat in archive.chats) {
                    val newChatId =
                        db.chatsDao()
                            .insertChat(
                                Chat(
                                    name = archivedChat.name.ifBlank { "Imported chat" },
                                    systemPrompt = archivedChat.systemPrompt,
                                    dateCreated =
                                        Date(
                                            archivedChat.dateCreated.takeIf { it > 0 }
                                                ?: importedAt
                                        ),
                                    dateUsed =
                                        Date(
                                            archivedChat.dateUsed.takeIf { it > 0 }
                                                ?: importedAt
                                        ),
                                    llmModelId = -1L,
                                    minP = archivedChat.minP,
                                    temperature = archivedChat.temperature,
                                    topK = archivedChat.topK,
                                    topP = archivedChat.topP,
                                    repeatPenalty = archivedChat.repeatPenalty,
                                    repeatLastN = archivedChat.repeatLastN,
                                    seed = archivedChat.seed,
                                    nThreads = archivedChat.nThreads,
                                    useMmap = archivedChat.useMmap,
                                    useMlock = archivedChat.useMlock,
                                    contextSize =
                                        archivedChat.contextSize.takeIf { it > 0 } ?: 2048,
                                    contextSizeConsumed = 0,
                                    chatTemplate = archivedChat.chatTemplate,
                                    folderId = folderIdMap[archivedChat.folderId] ?: -1L,
                                )
                            )
                    val messages =
                        archivedChat.messages.map {
                            ChatMessage(
                                chatId = newChatId,
                                message = it.message,
                                isUserMessage = it.isUserMessage,
                            )
                        }
                    if (messages.isNotEmpty()) {
                        db.chatMessagesDao().insertMessages(messages)
                        messageCount += messages.size
                    }
                    chatCount++
                }
            }
            ChatImportResult(chats = chatCount, messages = messageCount)
        }
}
