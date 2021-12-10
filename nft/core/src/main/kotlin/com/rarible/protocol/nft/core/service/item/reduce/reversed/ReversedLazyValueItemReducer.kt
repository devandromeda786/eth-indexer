package com.rarible.protocol.nft.core.service.item.reduce.reversed

import com.rarible.core.entity.reducer.exception.ReduceException
import com.rarible.core.entity.reducer.service.Reducer
import com.rarible.core.entity.reducer.service.ReversedReducer
import com.rarible.protocol.nft.core.model.Item
import com.rarible.protocol.nft.core.model.ItemEvent
import org.springframework.stereotype.Component

@Component
class ReversedLazyValueItemReducer : ReversedReducer<ItemEvent, Item> {
    override suspend fun reduce(entity: Item, event: ItemEvent): Item {
        return when (event) {
            is ItemEvent.ItemMintEvent -> {
                if (entity.lastLazyEventTimestamp != null) {
                    entity.copy(lazySupply = entity.lazySupply + entity.supply)
                } else {
                    entity
                }
            }
            is ItemEvent.ItemTransferEvent,
            is ItemEvent.ItemBurnEvent,
            is ItemEvent.ItemCreatorsEvent,
            is ItemEvent.LazyItemBurnEvent, is ItemEvent.LazyItemMintEvent ->
                throw ReduceException("This events can't be in this reducer")
        }
    }
}
