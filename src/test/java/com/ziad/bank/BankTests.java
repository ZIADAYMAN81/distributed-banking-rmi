package com.ziad.bank;

import com.ziad.bank.api.BankService;
import com.ziad.bank.api.Worker;
import com.ziad.bank.model.BankException;
import com.ziad.bank.model.Money;
import com.ziad.bank.model.Operation;
import com.ziad.bank.server.MasterNode;
import com.ziad.bank.server.WorkerNode;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Framework-free test runner: {@code java -cp out com.ziad.bank.BankTests}. */
public final class BankTests {
    private static int passed, failed;

    private static void check(boolean condition, String what) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.out.println("  FAILED: " + what);
        }
    }

    private static void expectRejected(ThrowingRunnable r, String what) {
        try {
            r.run();
            check(false, what + " should have been rejected");
        } catch (BankException e) {
            check(true, what);
        } catch (Exception e) {
            check(false, what + " threw " + e);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static MasterNode bank(int workers) throws RemoteException {
        List<Worker> ws = new ArrayList<>();
        for (int i = 1; i <= workers; i++) {
            ws.add(new WorkerNode("w" + i));
        }
        return new MasterNode(ws);
    }

    static void moneyParsingIsExact() throws Exception {
        check(Money.parse("10.10") == 1010 && Money.parse("0.3") == 30, "decimal parsing");
        check(Money.format(123456) .equals("1234.56"), "formatting");
        expectRejected(() -> Money.parse("1.005"), "more than two decimals");
        expectRejected(() -> Money.parse("abc"), "non-numeric amount");
    }

    static void basicOperations() throws Exception {
        MasterNode b = bank(3);
        String a = b.openAccount("Ali", 10_000), c = b.openAccount("Mona", 5_000);
        b.deposit(a, 2_500);
        b.withdraw(c, 1_000);
        b.transfer(a, c, 4_000);
        check(b.balance(a) == 8_500 && b.balance(c) == 8_000, "deposit, withdraw and transfer balances");
        check(b.recentTransactions(10).stream().filter(t -> t.status().equals("COMMITTED")).count() == 3, "transaction log");
    }

    static void failedTransferChangesNothing() throws Exception {
        MasterNode b = bank(3);
        String a = b.openAccount("Ali", 1_000), c = b.openAccount("Mona", 0);
        expectRejected(() -> b.transfer(a, c, 5_000), "transfer with insufficient funds");
        check(b.balance(a) == 1_000 && b.balance(c) == 0, "aborted transfer leaves both balances unchanged");
        expectRejected(() -> b.withdraw(a, 0), "zero amount");
        expectRejected(() -> b.transfer(a, a, 100), "transfer to self");
        expectRejected(() -> b.balance("AC999"), "unknown account");
        expectRejected(() -> b.openAccount(" ", 100), "blank owner");
    }

    static void accountsAreSpreadAcrossWorkers() throws Exception {
        MasterNode b = bank(3);
        java.util.Set<String> used = new java.util.HashSet<>();
        for (int i = 0; i < 30; i++) {
            used.add(b.locate(b.openAccount("user" + i, 100)));
        }
        check(used.size() == 3, "30 accounts land on all 3 workers");
    }

    static void workerVotingNoAbortsTheWholeTransaction() throws Exception {
        WorkerNode good = new WorkerNode("good");
        Worker refusing = new WorkerNode("refusing") {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean prepare(long txId, List<Operation> ops) {
                return false;  // simulates a participant that cannot commit
            }
        };
        MasterNode b = new MasterNode(List.of(good, refusing));
        List<String> onGood = new ArrayList<>(), onRefusing = new ArrayList<>();
        for (int i = 0; onGood.isEmpty() || onRefusing.isEmpty(); i++) {
            String id = b.openAccount("u" + i, 1_000);
            (b.locate(id).equals("good") ? onGood : onRefusing).add(id);
        }
        expectRejected(() -> b.transfer(onGood.get(0), onRefusing.get(0), 100), "cross-worker transfer with a no vote");
        check(b.balance(onGood.get(0)) == 1_000, "prepared worker rolled back (locks released, balance unchanged)");
        b.withdraw(onGood.get(0), 100);  // would block if the abort had leaked a lock
        check(b.balance(onGood.get(0)) == 900, "account usable again after abort");
    }

    static void concurrentTransfersConserveMoney() throws Exception {
        MasterNode b = bank(3);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(b.openAccount("user" + i, 100_000));
        }
        long before = b.totalCents();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger committed = new AtomicInteger(), rejected = new AtomicInteger();
        for (int t = 0; t < 16; t++) {
            final int seed = t;
            pool.submit(() -> {
                Random rnd = new Random(seed);
                for (int k = 0; k < 250; k++) {
                    String from = ids.get(rnd.nextInt(ids.size())), to = ids.get(rnd.nextInt(ids.size()));
                    if (from.equals(to)) {
                        continue;
                    }
                    try {
                        b.transfer(from, to, 1 + rnd.nextInt(30_000));
                        committed.incrementAndGet();
                    } catch (BankException e) {
                        rejected.incrementAndGet();
                    } catch (RemoteException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }
        pool.shutdown();
        check(pool.awaitTermination(60, TimeUnit.SECONDS), "4,000 concurrent transfers finish (no deadlock)");
        check(b.totalCents() == before, "total money conserved across " + committed.get() + " committed transfers");
        for (String id : ids) {
            check(b.balance(id) >= 0, "no negative balance");
        }
        System.out.printf("  %d committed, %d rejected for insufficient funds%n", committed.get(), rejected.get());
    }

    static void worksOverRealRmi() throws Exception {
        Registry registry = LocateRegistry.createRegistry(1199);
        try {
            List<Worker> ws = List.of(new WorkerNode("rmi-1"), new WorkerNode("rmi-2"));
            registry.rebind(BankService.BINDING, new MasterNode(ws));
            BankService remote = (BankService) LocateRegistry.getRegistry("localhost", 1199).lookup(BankService.BINDING);
            String a = remote.openAccount("Remote Ali", 2_000), c = remote.openAccount("Remote Mona", 0);
            remote.transfer(a, c, 750);
            check(remote.balance(c) == 750, "transfer through a real RMI registry");
            expectRejected(() -> remote.withdraw(c, 10_000), "business errors travel over RMI");
            var history = remote.recentTransactions(10);
            check(history.size() == 4 && history.get(2).status().equals("COMMITTED"), "transaction log over RMI");
            check(history.get(3).status().equals("ABORTED"), "aborted transaction is logged");
        } finally {
            java.rmi.server.UnicastRemoteObject.unexportObject(registry, true);
        }
    }

    public static void main(String[] args) throws Exception {
        String[] names = {"money parsing", "basic operations", "failed transfer changes nothing",
                "accounts spread across workers", "a no vote aborts the transaction", "concurrent transfers",
                "real RMI round trip"};
        ThrowingRunnable[] tests = {BankTests::moneyParsingIsExact, BankTests::basicOperations,
                BankTests::failedTransferChangesNothing, BankTests::accountsAreSpreadAcrossWorkers,
                BankTests::workerVotingNoAbortsTheWholeTransaction, BankTests::concurrentTransfersConserveMoney,
                BankTests::worksOverRealRmi};
        for (int i = 0; i < tests.length; i++) {
            System.out.println("- " + names[i]);
            tests[i].run();
        }
        System.out.printf("%d/%d checks passed%n", passed, passed + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
