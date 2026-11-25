package com.ziad.bank.server;

import com.ziad.bank.api.BankService;
import com.ziad.bank.api.Worker;
import java.rmi.Naming;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts the system.
 *
 * <pre>
 *   java -cp out com.ziad.bank.server.Launcher local [workers=3] [port=1099]      # everything in one JVM
 *   java -cp out com.ziad.bank.server.Launcher worker &lt;name&gt; [port]              # one worker process
 *   java -cp out com.ziad.bank.server.Launcher master &lt;port&gt; &lt;rmi-url&gt; ...       # coordinator using remote workers
 * </pre>
 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "local";
        switch (mode) {
            case "local" -> {
                int n = args.length > 1 ? Integer.parseInt(args[1]) : 3;
                int port = args.length > 2 ? Integer.parseInt(args[2]) : 1099;
                Registry registry = LocateRegistry.createRegistry(port);
                List<Worker> workers = new ArrayList<>();
                for (int i = 1; i <= n; i++) {
                    WorkerNode w = new WorkerNode("worker-" + i);
                    registry.rebind("worker-" + i, w);
                    workers.add(w);
                }
                registry.rebind(BankService.BINDING, new MasterNode(workers));
                System.out.printf("Bank running on port %d with %d workers. Press Ctrl+C to stop.%n", port, n);
            }
            case "worker" -> {
                String name = args[1];
                int port = args.length > 2 ? Integer.parseInt(args[2]) : 1100;
                LocateRegistry.createRegistry(port).rebind(name, new WorkerNode(name));
                System.out.printf("Worker %s listening on port %d%n", name, port);
            }
            case "master" -> {
                int port = Integer.parseInt(args[1]);
                List<Worker> workers = new ArrayList<>();
                for (int i = 2; i < args.length; i++) {
                    workers.add((Worker) Naming.lookup(args[i]));
                }
                LocateRegistry.createRegistry(port).rebind(BankService.BINDING, new MasterNode(workers));
                System.out.printf("Coordinator on port %d using %d remote workers%n", port, workers.size());
            }
            default -> {
                System.err.println("usage: Launcher local [workers] [port] | worker <name> [port] | master <port> <worker-url>...");
                System.exit(2);
            }
        }
    }
}
