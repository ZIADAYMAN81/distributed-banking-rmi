package com.ziad.bank.api;

import com.ziad.bank.model.BankException;
import com.ziad.bank.model.Transaction;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/** The client-facing banking API, published in the RMI registry by the coordinator (master node). */
public interface BankService extends Remote {
    String BINDING = "BankService";

    String openAccount(String owner, long initialCents) throws RemoteException, BankException;

    void deposit(String accountId, long cents) throws RemoteException, BankException;

    void withdraw(String accountId, long cents) throws RemoteException, BankException;

    void transfer(String fromId, String toId, long cents) throws RemoteException, BankException;

    long balance(String accountId) throws RemoteException, BankException;

    List<Transaction> recentTransactions(int limit) throws RemoteException;

    /** Which worker stores the account (shows how accounts are partitioned). */
    String locate(String accountId) throws RemoteException, BankException;
}
