package com.systemutility.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.systemutility.core.database.dao.MessageDao
import com.systemutility.core.database.dao.UserDao
import com.systemutility.core.database.entity.ConversationEntity
import com.systemutility.core.model.UserProfile
import com.systemutility.core.network.ws.RealtimeWebSocketClient
import com.systemutility.core.network.ws.WsConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val selfUser: UserProfile? = null,
    val partnerUser: UserProfile? = null,
    val conversation: ConversationEntity? = null,
    val pendingOutboxCount: Int = 0,
    val wsState: WsConnectionState = WsConnectionState.DISCONNECTED
)

@HiltViewModel
class ConversationHomeViewModel @Inject constructor(
    userDao: UserDao,
    messageDao: MessageDao,
    wsClient: RealtimeWebSocketClient,
    private val chatRepository: ChatRepository
) : ViewModel() {

    init {
        chatRepository.startRealtimeObserver()
        wsClient.connect()
        viewModelScope.launch {
            chatRepository.ensurePartnerProfileLoaded()
            chatRepository.uploadPreKeysIfNeeded()
            chatRepository.syncFromServer()
        }
    }

    val uiState: StateFlow<HomeUiState> = combine(
        userDao.observeSelfUser(),
        userDao.observePartnerUser(),
        userDao.observePrimaryConversation(),
        messageDao.observePendingOutboxCount(),
        wsClient.connectionState
    ) { self, partner, conv, outboxCount, ws ->
        HomeUiState(
            selfUser = self?.toDomain(),
            partnerUser = partner?.toDomain(),
            conversation = conv,
            pendingOutboxCount = outboxCount,
            wsState = ws
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState()
    )
}
