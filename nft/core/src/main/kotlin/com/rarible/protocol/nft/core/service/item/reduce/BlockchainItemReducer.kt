package com.rarible.protocol.nft.core.service.item.reduce

import com.rarible.core.entity.reducer.service.Reducer
import com.rarible.ethereum.domain.EthUInt256
import com.rarible.protocol.nft.core.model.Item
import com.rarible.protocol.nft.core.model.ItemEvent
import org.springframework.stereotype.Component
import java.lang.IllegalStateException

@Component
class BlockchainItemReducer : Reducer<ItemEvent, Item> {
    override suspend fun reduce(entity: Item, event: ItemEvent): Item {
        val supply = when (event) {
            is ItemEvent.ItemMintEvent -> entity.supply + event.supply
            is ItemEvent.ItemBurnEvent -> entity.supply - event.supply

            is ItemEvent.LazyItemBurnEvent, is ItemEvent.LazyItemMintEvent ->
                throw IllegalStateException("This events can't be in this reducer")
        }
        return entity.copy(supply = supply, deleted = supply == EthUInt256.ZERO)
    }
}
