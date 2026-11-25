package com.ziad.bank.model;

import java.io.Serializable;
import java.time.Instant;

/** An entry in the coordinator's transaction log. */
public record Transaction(long id, String type, String from, String to, long amountCents, String status, Instant at)
        implements Serializable {

    @Override
    public String toString() {
        return String.format("#%d %-8s %-9s %s -> %s  %s", id, type, status, from == null ? "-" : from,
                to == null ? "-" : to, Money.format(amountCents));
    }
}
