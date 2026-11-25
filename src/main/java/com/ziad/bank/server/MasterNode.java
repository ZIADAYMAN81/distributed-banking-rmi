package com.ziad.bank.server;

import com.ziad.bank.api.BankService;
import com.ziad.bank.api.Worker;
import com.ziad.bank.model.BankException;
import com.ziad.bank.model.Money;
import com.ziad.bank.model.Operation;
import com.ziad.bank.model.Transaction;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Coordinator: assigns each account to a worker (hash partitioning), and runs every balance change as a
 * two-phase-commit transaction across the workers that hold the affected accounts.
 */
public class MasterNode extends UnicastRemoteObject implements BankService {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(MasterNode.class.getName());
    private static final int LOG_CAPACITY = 1000;

    private final transient List<Worker> workers;
    private final AtomicLong nextAccount = new AtomicLong(100_000);
    private final AtomicLong nextTx = new AtomicLong(1);
    private final transient Deque<Transaction> log = new ArrayDeque<>();

    public MasterNode(List<Worker> workers) throws RemoteException {
        super();
        if (workers.isEmpty()) {
            throw new IllegalArgumentException("at least one worker is required");
        }
        this.workers = List.copyOf(workers);
    }

    Worker shardFor(String accountId) {
        return workers.get(Math.floorMod(accountId.hashCode(), workers.size()));
    }

    @Override
    public String openAccount(String owner, long initialCents) throws RemoteException, BankException {
        if (owner == null || owner.isBlank()) {
            throw new BankException("Owner name is required");
        }
        String id = "AC" + nextAccount.getAndIncrement();
        shardFor(id).createAccount(id, owner.trim(), initialCents);
        record(nextTx.getAndIncrement(), "OPEN", null, id, initialCents, "DONE");
        return id;
    }

    @Override
    public void deposit(String accountId, long cents) throws RemoteException, BankException {
        requirePositive(cents);
        run("DEPOSIT", null, accountId, cents, List.of(new Operation(accountId, cents)));
    }

    @Override
    public void withdraw(String accountId, long cents) throws RemoteException, BankException {
        requirePositive(cents);
        run("WITHDRAW", accountId, null, cents, List.of(new Operation(accountId, -cents)));
    }

    @Override
    public void transfer(String fromId, String toId, long cents) throws RemoteException, BankException {
        requirePositive(cents);
        if (fromId.equals(toId)) {
            throw new BankException("Cannot transfer to the same account");
        }
        run("TRANSFER", fromId, toId, cents, List.of(new Operation(fromId, -cents), new Operation(toId, cents)));
    }

    @Override
    public long balance(String accountId) throws RemoteException, BankException {
        return shardFor(accountId).balance(accountId);
    }

    @Override
    public String locate(String accountId) throws RemoteException, BankException {
        Worker w = shardFor(accountId);
        w.balance(accountId);  // throws if the account does not exist
        return w.name();
    }

    @Override
    public synchronized List<Transaction> recentTransactions(int limit) {
        List<Transaction> out = new ArrayList<>(log);
        // copy into a fresh ArrayList: subList views are not serializable and cannot be sent over RMI
        return new ArrayList<>(out.subList(Math.max(0, out.size() - limit), out.size()));
    }

    /** Sum of balances across all workers; constant under transfers (used by tests and the demo). */
    public long totalCents() throws RemoteException {
        long total = 0;
        for (Worker w : workers) {
            total += w.totalCents();
        }
        return total;
    }

    // ---- two-phase commit ------------------------------------------------------------------------------

    private void run(String type, String from, String to, long cents, List<Operation> ops)
            throws RemoteException, BankException {
        for (Operation op : ops) {
            shardFor(op.accountId()).balance(op.accountId());  // fail fast on unknown accounts
        }
        long txId = nextTx.getAndIncrement();
        Map<Worker, List<Operation>> byWorker = new LinkedHashMap<>();
        for (Operation op : ops) {
            byWorker.computeIfAbsent(shardFor(op.accountId()), w -> new ArrayList<>()).add(op);
        }

        // Phase 1: every participant must vote yes.
        List<Worker> prepared = new ArrayList<>();
        boolean allYes = true;
        for (Map.Entry<Worker, List<Operation>> e : byWorker.entrySet()) {
            boolean vote;
            try {
                vote = e.getKey().prepare(txId, e.getValue());
            } catch (RemoteException ex) {
                LOG.warning("worker unreachable during prepare of tx " + txId + ": " + ex.getMessage());
                vote = false;
            }
            if (!vote) {
                allYes = false;
                break;
            }
            prepared.add(e.getKey());
        }

        // Phase 2: commit everywhere, or abort everything that was prepared.
        if (allYes) {
            for (Worker w : prepared) {
                w.commit(txId);
            }
            record(txId, type, from, to, cents, "COMMITTED");
        } else {
            for (Worker w : prepared) {
                try {
                    w.abort(txId);
                } catch (RemoteException ex) {
                    LOG.warning("abort of tx " + txId + " failed on a worker: " + ex.getMessage());
                }
            }
            record(txId, type, from, to, cents, "ABORTED");
            throw new BankException(type.charAt(0) + type.substring(1).toLowerCase()
                    + " of " + Money.format(cents) + " was rejected (insufficient funds or account busy)");
        }
    }

    private synchronized void record(long txId, String type, String from, String to, long cents, String status) {
        log.addLast(new Transaction(txId, type, from, to, cents, status, Instant.now()));
        if (log.size() > LOG_CAPACITY) {
            log.removeFirst();
        }
    }

    private static void requirePositive(long cents) throws BankException {
        if (cents <= 0) {
            throw new BankException("Amount must be greater than zero");
        }
    }
}
