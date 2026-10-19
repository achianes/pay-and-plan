package com.payandplan.app.net

/** What the server can do about banks, and what the owner has already let it read. */
data class BankingStatus(
    val configured: Boolean = false,
    val redirectReady: Boolean = false,
    val connections: List<BankConnection> = emptyList()
) {
    val usable: Boolean get() = configured && redirectReady
}

data class BankConnection(
    val id: String,
    val bank: String,
    val account: String,
    val ibanTail: String,
    /** when the bank will stop answering for this consent and it has to be asked again */
    val validUntil: Long,
    val lastReadAt: Long
)

data class BankChoice(val name: String, val country: String, val beta: Boolean = false)

/** One line of the account, before the house decides what it is. */
data class AccountLine(
    val externalId: String,
    val bank: String,
    val name: String,
    val text: String,
    val amountCents: Long,
    val kind: String,
    val happenedAt: Long
)
