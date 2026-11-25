package com.ziad.bank.client;

import com.ziad.bank.api.BankService;
import com.ziad.bank.model.BankException;
import com.ziad.bank.model.Money;
import com.ziad.bank.model.Transaction;
import java.rmi.registry.LocateRegistry;
import java.util.Scanner;

/** Interactive console client: {@code java -cp out com.ziad.bank.client.BankCli [host] [port]}. */
public final class BankCli {
    private BankCli() {
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 1099;
        BankService bank = (BankService) LocateRegistry.getRegistry(host, port).lookup(BankService.BINDING);
        System.out.println("Connected. Commands: open <name> <amount> | deposit <acct> <amount> | withdraw <acct> <amount>");
        System.out.println("          transfer <from> <to> <amount> | balance <acct> | where <acct> | log | quit");
        Scanner in = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            if (!in.hasNextLine()) {
                return;
            }
            String[] p = in.nextLine().trim().split("\\s+");
            try {
                switch (p[0].toLowerCase()) {
                    case "open" -> System.out.println("Opened " + bank.openAccount(p[1], Money.parse(p[2])));
                    case "deposit" -> { bank.deposit(p[1], Money.parse(p[2])); System.out.println("OK"); }
                    case "withdraw" -> { bank.withdraw(p[1], Money.parse(p[2])); System.out.println("OK"); }
                    case "transfer" -> { bank.transfer(p[1], p[2], Money.parse(p[3])); System.out.println("OK"); }
                    case "balance" -> System.out.println(Money.format(bank.balance(p[1])));
                    case "where" -> System.out.println(p[1] + " is stored on " + bank.locate(p[1]));
                    case "log" -> { for (Transaction t : bank.recentTransactions(20)) System.out.println(t); }
                    case "quit", "exit" -> { return; }
                    case "" -> { }
                    default -> System.out.println("Unknown command: " + p[0]);
                }
            } catch (BankException e) {
                System.out.println("Error: " + e.getMessage());
            } catch (ArrayIndexOutOfBoundsException e) {
                System.out.println("Missing arguments for " + p[0]);
            }
        }
    }
}
