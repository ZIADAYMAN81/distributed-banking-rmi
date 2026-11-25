package com.ziad.bank.api;

import com.ziad.bank.model.BankException;
import com.ziad.bank.model.Operation;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * A worker node stores one shard of the accounts and takes part in two-phase commit.
 *
 * <p>Phase 1: {@link #prepare} validates the operations, locks the accounts and votes yes or no.
 * Phase 2: the coordinator calls {@link #commit} if every participant voted yes, otherwise {@link #abort}.
 */
public interface Worker extends Remote {
    void createAccount(String accountId, String owner, long initialCents) throws RemoteException, BankException;

    long balance(String accountId) throws RemoteException, BankException;

    boolean prepare(long txId, List<Operation> operations) throws RemoteException;

    void commit(long txId) throws RemoteException;

    void abort(long txId) throws RemoteException;

    /** Sum of all balances on this worker (used for consistency checks). */
    long totalCents() throws RemoteException;

    String name() throws RemoteException;
}
