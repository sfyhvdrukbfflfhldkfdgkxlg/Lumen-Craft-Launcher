package dev.lumencraft.account;
import java.io.IOException;

/** Local, redacted diagnostics that can be shown on screen. */
final class AccountProblem extends IOException {
    AccountProblem(String message) { super(message); }
}
