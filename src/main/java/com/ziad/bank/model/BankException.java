package com.ziad.bank.model;

/** Business error reported to clients (unknown account, insufficient funds, invalid amount...). */
public class BankException extends Exception {
    private static final long serialVersionUID = 1L;

    public BankException(String message) {
        super(message);
    }
}
