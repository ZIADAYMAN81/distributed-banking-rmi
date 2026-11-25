package com.ziad.bank.server;

import com.ziad.bank.api.Worker;
import com.ziad.bank.model.BankException;
import com.ziad.bank.model.Operation;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Stores a shard of accounts. Account locks are taken in a fixed (sorted) order during prepare and released on
 * commit or abort, so concurrent transactions can never deadlock on this node. Locks are binary semaphores
 * rather than {@code ReentrantLock}s because RMI may run prepare and commit on different threads, and a
 * semaphore can be released by any thread.
 */
public class WorkerNode extends UnicastRemoteObject implements Worker {
    private static final long serialVersionUID = 1L;
    private static final long LOCK_TIMEOUT_MS = 2000;

    private static final class Account {
        final String owner;
        long balanceCents;
        final Semaphore lock = new Semaphore(1, true);

        Account(String owner, long balanceCents) {
            this.owner = owner;
            this.balanceCents = balanceCents;
        }
    }

    private record Pending(List<Operation> operations, List<Account> locked) {
    }

    private final String name;
    private final transient Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final transient Map<Long, Pending> pending = new ConcurrentHashMap<>();

    public WorkerNode(String name) throws RemoteException {
        super();
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void createAccount(String accountId, String owner, long initialCents) throws BankException {
        if (initialCents < 0) {
            throw new BankException("Initial balance cannot be negative");
        }
        if (accounts.putIfAbsent(accountId, new Account(owner, initialCents)) != null) {
            throw new BankException("Account already exists: " + accountId);
        }
    }

    @Override
    public long balance(String accountId) throws BankException {
        Account a = accounts.get(accountId);
        if (a == null) {
            throw new BankException("Unknown account: " + accountId);
        }
        a.lock.acquireUninterruptibly();
        try {
            return a.balanceCents;
        } finally {
            a.lock.release();
        }
    }

    @Override
    public boolean prepare(long txId, List<Operation> operations) {
        List<String> ids = operations.stream().map(Operation::accountId).distinct().sorted(Comparator.naturalOrder()).toList();
        List<Account> locked = new ArrayList<>();
        try {
            for (String id : ids) {
                Account a = accounts.get(id);
                if (a == null || !a.lock.tryAcquire(LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    release(locked);
                    return false;
                }
                locked.add(a);
            }
            Map<String, Long> net = new HashMap<>();
            for (Operation op : operations) {
                net.merge(op.accountId(), op.deltaCents(), Long::sum);
            }
            for (Map.Entry<String, Long> e : net.entrySet()) {
                if (accounts.get(e.getKey()).balanceCents + e.getValue() < 0) {
                    release(locked);
                    return false;  // vote no: insufficient funds
                }
            }
            pending.put(txId, new Pending(List.copyOf(operations), locked));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            release(locked);
            return false;
        }
    }

    @Override
    public void commit(long txId) {
        Pending p = pending.remove(txId);
        if (p == null) {
            return;  // already finished (commit is idempotent)
        }
        for (Operation op : p.operations()) {
            accounts.get(op.accountId()).balanceCents += op.deltaCents();
        }
        release(p.locked());
    }

    @Override
    public void abort(long txId) {
        Pending p = pending.remove(txId);
        if (p != null) {
            release(p.locked());
        }
    }

    @Override
    public long totalCents() {
        long total = 0;
        for (Account a : accounts.values()) {
            a.lock.acquireUninterruptibly();
            try {
                total += a.balanceCents;
            } finally {
                a.lock.release();
            }
        }
        return total;
    }

    private static void release(List<Account> locked) {
        for (int i = locked.size() - 1; i >= 0; i--) {
            locked.get(i).lock.release();
        }
    }
}
