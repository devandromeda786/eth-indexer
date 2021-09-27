package com.rarible.protocol.nft.api.service.admin

import com.rarible.core.task.Task
import com.rarible.core.task.TaskStatus
import com.rarible.protocol.nft.core.model.*
import com.rarible.protocol.nft.core.model.ReindexTokenTaskParams.Companion.SUPPORTED_REINDEX_TOKEN_STANDARD
import com.rarible.protocol.nft.core.model.ReduceTokenItemsTaskParams
import com.rarible.protocol.nft.core.model.ReindexTokenTaskParams
import com.rarible.protocol.nft.core.model.Token
import com.rarible.protocol.nft.core.model.TokenStandard
import com.rarible.protocol.nft.core.repository.TempTaskRepository
import com.rarible.protocol.nft.core.repository.TokenRepository
import com.rarible.protocol.nft.core.service.token.TokenRegistrationService
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Component
import scalether.domain.Address

@Component
class ReindexTokenService(
    private val tokenRegistrationService: TokenRegistrationService,
    private val tokenRepository: TokenRepository,
    private val taskRepository: TempTaskRepository
) {
    suspend fun getToken(token: Address): Token? {
        return tokenRepository.findById(token).awaitFirstOrNull()
    }

    suspend fun removeToken(token: Address) {
        tokenRepository.remove(token).awaitFirstOrNull()
    }

    suspend fun getTokenTasks(): List<Task> {
        return taskRepository.findByType(ReindexTokenTaskParams.ADMIN_REINDEX_TOKEN).toList()
    }

    suspend fun createReindexTokenTask(tokens: List<Address>, fromBlock: Long?): Task {
        val tokenStandardMap = tokens.toSet().map { it to tokenRegistrationService.getTokenStandard(it).awaitFirst() }
        val standards = tokenStandardMap.map { it.second }.toSet()

        if (standards.size != 1) {
            throw IllegalArgumentException("All tokens must be the same standard: ${formatToString(tokenStandardMap)}")
        }
        if (standards.first() !in SUPPORTED_REINDEX_TOKEN_STANDARD) {
            throw IllegalArgumentException("Reindex support only $SUPPORTED_REINDEX_TOKEN_STANDARD, but you have ${formatToString(tokenStandardMap)}")
        }
        val params = ReindexTokenTaskParams(standards.first(), tokens)
        checkOtherReindexTokenTasks(params)
        return saveTask(params.toParamString(), ReindexTokenTaskParams.ADMIN_REINDEX_TOKEN)
    }

    suspend fun createReduceTokenItemsTask(tokens: Address): Task {
        val params = ReduceTokenItemsTaskParams(tokens)
        checkOtherTasks(params, ReduceTokenItemsTaskParams.ADMIN_REDUCE_TOKEN_ITEMS)
        return saveTask(params.toParamString(), ReduceTokenItemsTaskParams.ADMIN_REDUCE_TOKEN_ITEMS)
    }

    suspend fun createReindexRoyaltiesTask(tokens: Address): Task {
        val params = ReindexTokenRoyaltiesTaskParam(tokens)
        checkOtherTasks(params, ReindexTokenRoyaltiesTaskParam.ADMIN_REINDEX_TOKEN_ROYALTIES)
        return saveTask(params.toParamString(), ReindexTokenRoyaltiesTaskParam.ADMIN_REINDEX_TOKEN_ROYALTIES)
    }

    private suspend fun checkOtherReindexTokenTasks(params: ReindexTokenTaskParams) {
        taskRepository.findByType(ReindexTokenTaskParams.ADMIN_REINDEX_TOKEN).collect { task ->
            if (task.lastStatus != TaskStatus.COMPLETED) {
                val existedReindexTokens = ReindexTokenTaskParams
                    .fromParamString(task.param)
                    .tokens
                    .filter { it in params.tokens }

                if (existedReindexTokens.isNotEmpty()) {
                    throw IllegalArgumentException("Tokens $existedReindexTokens already reindexing in other task ${task.id}")
                }
            }
        }
    }

    private suspend fun checkOtherTasks(params: TokenTaskParam, type: String) {
        taskRepository.findByType(type).collect { task ->
            if (task.lastStatus != TaskStatus.COMPLETED) {
                val existedToken = ReduceTokenItemsTaskParams.fromParamString(task.param).token

                if (existedToken == params.token) {
                    throw IllegalArgumentException("Token $existedToken already in other task ${task.id}, type $task")
                }
            }
        }
    }

    private suspend fun saveTask(params: String, type: String): Task {
        return saveTask(
            Task(
                type = type,
                param = params,
                state = null,
                running = false,
                lastStatus = TaskStatus.NONE
            )
        )
    }

    private suspend fun saveTask(task: Task): Task {
        return try {
            taskRepository.save(task)
        } catch (ex: Exception) {
            when (ex) {
                is OptimisticLockingFailureException, is DuplicateKeyException -> {
                    throw IllegalArgumentException("Reindex task already exist")
                }
                else -> throw ex
            }
        }
    }

    private fun formatToString(tokenStandardMap: List<Pair<Address, TokenStandard>>): String {
        return tokenStandardMap.joinToString(",") { "${it.second}:${it.first}" }
    }
}

