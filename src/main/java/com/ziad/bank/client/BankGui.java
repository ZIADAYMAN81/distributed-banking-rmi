package com.ziad.bank.client;

import com.ziad.bank.api.BankService;
import com.ziad.bank.model.BankException;
import com.ziad.bank.model.Money;
import com.ziad.bank.model.Transaction;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.rmi.registry.LocateRegistry;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Swing desktop client: {@code java -cp out com.ziad.bank.client.BankGui [host] [port]}. */
public final class BankGui extends JFrame {
    private static final long serialVersionUID = 1L;
    private final transient BankService bank;
    private final JTextField owner = new JTextField(), account = new JTextField(), target = new JTextField(),
            amount = new JTextField();
    private final JTextArea output = new JTextArea(12, 50);

    public BankGui(BankService bank) {
        super("Distributed Bank");
        this.bank = bank;
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        form.add(new JLabel("Owner (new account)")); form.add(owner);
        form.add(new JLabel("Account")); form.add(account);
        form.add(new JLabel("Transfer to")); form.add(target);
        form.add(new JLabel("Amount")); form.add(amount);

        JPanel buttons = new JPanel(new GridLayout(1, 0, 6, 6));
        buttons.add(button("Open", () -> say("Opened " + bank.openAccount(owner.getText(), Money.parse(amount.getText())))));
        buttons.add(button("Deposit", () -> { bank.deposit(account.getText(), Money.parse(amount.getText())); say("Deposited"); }));
        buttons.add(button("Withdraw", () -> { bank.withdraw(account.getText(), Money.parse(amount.getText())); say("Withdrew"); }));
        buttons.add(button("Transfer", () -> {
            bank.transfer(account.getText(), target.getText(), Money.parse(amount.getText()));
            say("Transferred");
        }));
        buttons.add(button("Balance", () -> say(account.getText() + ": " + Money.format(bank.balance(account.getText())))));
        buttons.add(button("Log", () -> {
            StringBuilder sb = new StringBuilder();
            for (Transaction t : bank.recentTransactions(15)) sb.append(t).append('\n');
            say(sb.toString());
        }));

        output.setEditable(false);
        setLayout(new BorderLayout(6, 6));
        add(form, BorderLayout.NORTH);
        add(buttons, BorderLayout.CENTER);
        add(new JScrollPane(output), BorderLayout.SOUTH);
        pack();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
    }

    private interface Action {
        void run() throws Exception;
    }

    private JButton button(String label, Action action) {
        JButton b = new JButton(label);
        b.addActionListener(e -> {
            try {
                action.run();
            } catch (BankException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Request rejected", JOptionPane.WARNING_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Server error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        return b;
    }

    private void say(String message) {
        output.append(message + "\n");
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 1099;
        BankService bank = (BankService) LocateRegistry.getRegistry(host, port).lookup(BankService.BINDING);
        try {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // fall back to the default look and feel
        }
        SwingUtilities.invokeLater(() -> new BankGui(bank).setVisible(true));
    }
}
