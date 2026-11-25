# Distributed Transactional Banking System (Java RMI)

> Distributed Systems coursework, The British University in Egypt. Author: Ziad Ayman.

A banking system whose accounts are **partitioned across several worker servers**. A coordinator routes each request to the right worker and runs every balance change, including transfers between accounts on different machines, as a **two-phase-commit transaction**, so money is never lost or duplicated. Built with plain Java (no frameworks) using Java RMI.

```text
$ java -cp out com.ziad.bank.server.Launcher worker worker-A 1301
$ java -cp out com.ziad.bank.server.Launcher worker worker-B 1302
$ java -cp out com.ziad.bank.server.Launcher master 1300 rmi://localhost:1301/worker-A rmi://localhost:1302/worker-B
$ java -cp out com.ziad.bank.client.BankCli localhost 1300
> open Ali 1000            Opened AC100000
> open Mona 250.50         Opened AC100001
> where AC100000           AC100000 is stored on worker-B
> where AC100001           AC100001 is stored on worker-A
> transfer AC100000 AC100001 300.25     OK   (cross-worker transaction)
> transfer AC100002 AC100000 50         Error: Transfer of 50.00 was rejected (insufficient funds or account busy)
> log
#4 TRANSFER COMMITTED AC100000 -> AC100001  300.25
#5 TRANSFER ABORTED   AC100002 -> AC100000  50.00
```
*(A real session with three separate JVM processes; output trimmed.)*

## Architecture
```
  BankCli / BankGui ──RMI──▶ MasterNode (coordinator, BankService)
                                │  hash(accountId) → worker
                ┌───────────────┼───────────────┐
                ▼               ▼               ▼
           WorkerNode A    WorkerNode B    WorkerNode C      each stores one shard of accounts
```
- **Partitioning:** accounts are assigned to workers by hashing the account ID.
- **Two-phase commit:** in phase 1 each participating worker validates its operations, locks the affected accounts and votes; in phase 2 the coordinator commits on every worker, or aborts everything that was prepared if any worker votes no or cannot be reached.
- **Concurrency:** per-account locks are taken in sorted order (no deadlocks) with a timeout. They are semaphores rather than `ReentrantLock`s, because RMI may run `prepare` and `commit` on different threads.
- **Exact money:** amounts are whole cents in a `long`, parsed with `BigDecimal`, with no floating-point rounding.
- **Deployment modes:** everything in one JVM (`local`), or separate worker and coordinator processes on any hosts (`worker` and `master`).
- **Clients:** an interactive command-line client and a Swing desktop client.

## Tests (42 checks, no external libraries)
```bash
./build.sh        # or build.bat on Windows
```
- Exact money parsing and formatting, and rejection of invalid amounts.
- Deposit, withdraw and transfer balances, plus the transaction log.
- A failed transfer leaves both balances unchanged; invalid requests are rejected.
- Accounts spread across all workers.
- **A worker voting "no" aborts the whole transaction**, and the prepared worker releases its locks.
- **16 threads running 4,000 random transfers concurrently:** no deadlock, no negative balance, and the total amount of money is exactly conserved.
- A full round trip over a real RMI registry, including business errors and the transaction log.

## Run it
```bash
./build.sh
java -cp out com.ziad.bank.server.Launcher local 3     # coordinator + 3 workers in one JVM, port 1099
java -cp out com.ziad.bank.client.BankGui              # or BankCli
```

## About this version
The original coursework code was an early skeleton: the account class was missing, the worker nodes were not remote objects, and transfers were not implemented, so it did not compile. This version completes the design: remote workers, a coordinator with two-phase commit, locking, clients and tests.

## Tech stack
Java 17 · Java RMI · java.util.concurrent · Swing
