package com.kit.wallet.data.mapper

import com.kit.wallet.data.remote.BankDto
import com.kit.wallet.ui.model.BankInstitution

internal fun BankDto.toBankInstitution(): BankInstitution = BankInstitution(
    id = id,
    name = name,
    currency = currency,
    capabilities = capabilities.orEmpty().mapValues { (_, enabled) -> enabled == true },
)
