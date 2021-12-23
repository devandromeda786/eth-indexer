package com.rarible.protocol.block.scanner.configuration

import com.github.cloudyrock.spring.v5.EnableMongock
import com.rarible.blockchain.scanner.ethereum.EnableEthereumScanner
import com.rarible.blockchain.scanner.reconciliation.DefaultReconciliationFormProvider
import com.rarible.blockchain.scanner.reconciliation.ReconciliationFromProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@EnableMongock
@Configuration
@EnableEthereumScanner
class BlockIndexerConfiguration {
    @Bean
    fun reconciliationFromProvider(): ReconciliationFromProvider {
        return DefaultReconciliationFormProvider()
    }
}
