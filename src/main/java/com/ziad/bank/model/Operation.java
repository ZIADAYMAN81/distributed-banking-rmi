package com.ziad.bank.model;

import java.io.Serializable;

/** One balance change inside a transaction: add {@code deltaCents} (negative for a debit) to an account. */
public record Operation(String accountId, long deltaCents) implements Serializable {
}
