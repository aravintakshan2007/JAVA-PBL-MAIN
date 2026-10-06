import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Cashpoint {

    private static final String DB_FILE = System.getProperty("atm.db.path", "atm.db");
    private static final String DB_URL = "jdbc:sqlite:" + DB_FILE;
    private static final Map<String, String> sessions = new HashMap<>();
    private static final Object LOCK = new Object();

    // Data Models
    static class Account {
        final String no;
        final String cardNo;
        final String name;
        String pin;
        double balance;
        boolean locked;
        int fails;
        final boolean isAdmin;
        double dailyWithdrawn;
        String lastWithdrawDate;
        final String createdAt;

        Account(String no, String cardNo, String name, String pin, double balance, boolean locked, int fails, boolean isAdmin, double dailyWithdrawn, String lastWithdrawDate, String createdAt) {
            this.no = no;
            this.cardNo = cardNo;
            this.name = name;
            this.pin = pin;
            this.balance = balance;
            this.locked = locked;
            this.fails = fails;
            this.isAdmin = isAdmin;
            this.dailyWithdrawn = dailyWithdrawn;
            this.lastWithdrawDate = lastWithdrawDate;
            this.createdAt = createdAt;
        }
    }

    static class TransactionRecord {
        final String id;
        final String accountNo;
        final String type;
        final double amount;
        final double balanceAfter;
        final String description;
        final String targetAccount;
        final String timestamp;

        TransactionRecord(String id, String accountNo, String type, double amount, double balanceAfter, String description, String targetAccount, String timestamp) {
            this.id = id;
            this.accountNo = accountNo;
            this.type = type;
            this.amount = amount;
            this.balanceAfter = balanceAfter;
            this.description = description;
            this.targetAccount = targetAccount;
            this.timestamp = timestamp;
        }
    }

    static class AtmVault {
        final double totalCash;
        final String lastRefilledAt;

        AtmVault(double totalCash, String lastRefilledAt) {
            this.totalCash = totalCash;
            this.lastRefilledAt = lastRefilledAt;
        }
    }

    // --- Database Layer (JDBC) ---
    private static Connection getConnection() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            System.err.println("SQLite JDBC Driver not found: " + e.getMessage());
        }
        return DriverManager.getConnection(DB_URL);
    }

    private static void initDatabase() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    account_number TEXT PRIMARY KEY,
                    card_number TEXT UNIQUE NOT NULL,
                    full_name TEXT NOT NULL,
                    pin TEXT NOT NULL,
                    balance REAL NOT NULL DEFAULT 0.0,
                    is_locked INTEGER NOT NULL DEFAULT 0,
                    failed_attempts INTEGER NOT NULL DEFAULT 0,
                    is_admin INTEGER NOT NULL DEFAULT 0,
                    daily_withdrawn REAL NOT NULL DEFAULT 0.0,
                    last_withdraw_date TEXT,
                    created_at TEXT NOT NULL
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS transactions (
                    transaction_id TEXT PRIMARY KEY,
                    account_number TEXT NOT NULL,
                    type TEXT NOT NULL,
                    amount REAL NOT NULL,
                    balance_after REAL NOT NULL,
                    description TEXT NOT NULL,
                    target_account TEXT,
                    timestamp TEXT NOT NULL,
                    FOREIGN KEY(account_number) REFERENCES accounts(account_number)
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS atm_vault (
                    id INTEGER PRIMARY KEY,
                    total_cash REAL NOT NULL DEFAULT 500000.0,
                    last_refilled_at TEXT NOT NULL
                );
            """);

            ResultSet rsVault = stmt.executeQuery("SELECT COUNT(*) FROM atm_vault");
            if (rsVault.next() && rsVault.getInt(1) == 0) {
                String now = timestamp();
                stmt.execute("INSERT INTO atm_vault (id, total_cash, last_refilled_at) VALUES (1, 500000.0, '" + now + "')");
            }

            ResultSet rsAdmin = stmt.executeQuery("SELECT COUNT(*) FROM accounts WHERE account_number = 'admin'");
            if (rsAdmin.next() && rsAdmin.getInt(1) == 0) {
                String now = timestamp();
                stmt.execute("INSERT INTO accounts (account_number, card_number, full_name, pin, balance, is_locked, failed_attempts, is_admin, created_at) " +
                             "VALUES ('admin', '0000-0000-0000-0000', 'ATM Manager', '9999', 0.0, 0, 0, 1, '" + now + "')");
            }

        } catch (SQLException e) {
            System.err.println("Database initialization error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static Account getAccount(String accOrCardNo) {
        String sql = "SELECT * FROM accounts WHERE account_number = ? OR card_number = ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, accOrCardNo);
            pstmt.setString(2, accOrCardNo);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return new Account(
                    rs.getString("account_number"),
                    rs.getString("card_number"),
                    rs.getString("full_name"),
                    rs.getString("pin"),
                    rs.getDouble("balance"),
                    rs.getInt("is_locked") == 1,
                    rs.getInt("failed_attempts"),
                    rs.getInt("is_admin") == 1,
                    rs.getDouble("daily_withdrawn"),
                    rs.getString("last_withdraw_date"),
                    rs.getString("created_at")
                );
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static List<Account> getAllAccounts() {
        List<Account> list = new ArrayList<>();
        // rowid keeps existing databases (which used a display-formatted date) in insertion order.
        String sql = "SELECT * FROM accounts WHERE is_admin = 0 ORDER BY rowid DESC";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new Account(
                    rs.getString("account_number"),
                    rs.getString("card_number"),
                    rs.getString("full_name"),
                    rs.getString("pin"),
                    rs.getDouble("balance"),
                    rs.getInt("is_locked") == 1,
                    rs.getInt("failed_attempts"),
                    rs.getInt("is_admin") == 1,
                    rs.getDouble("daily_withdrawn"),
                    rs.getString("last_withdraw_date"),
                    rs.getString("created_at")
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    private static AtmVault getAtmVault() {
        String sql = "SELECT * FROM atm_vault WHERE id = 1";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return new AtmVault(rs.getDouble("total_cash"), rs.getString("last_refilled_at"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return new AtmVault(500000.0, "N/A");
    }

    private static Account registerAccount(String name, String pin, double initialDeposit) throws Exception {
        if (name == null || name.trim().length() < 2) throw new Exception("Please enter a valid full name");
        if (pin == null || !pin.matches("\\d{4}")) throw new Exception("PIN must be exactly 4 numeric digits");
        validateMoney(initialDeposit, true);

        Random rnd = new Random();
        String accNo;
        do {
            accNo = "100" + String.format("%07d", rnd.nextInt(10000000));
        } while (getAccount(accNo) != null);

        String cardNo;
        do {
            cardNo = String.format("4532-%04d-%04d-%04d", rnd.nextInt(10000), rnd.nextInt(10000), rnd.nextInt(10000));
        } while (getAccount(cardNo) != null);

        String now = timestamp();

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                String sqlAcc = "INSERT INTO accounts (account_number, card_number, full_name, pin, balance, is_locked, failed_attempts, is_admin, daily_withdrawn, created_at) " +
                                "VALUES (?, ?, ?, ?, ?, 0, 0, 0, 0.0, ?)";
                try (PreparedStatement pstmt = conn.prepareStatement(sqlAcc)) {
                    pstmt.setString(1, accNo);
                    pstmt.setString(2, cardNo);
                    pstmt.setString(3, name.trim());
                    pstmt.setString(4, pin);
                    pstmt.setDouble(5, initialDeposit);
                    pstmt.setString(6, now);
                    pstmt.executeUpdate();
                }

                if (initialDeposit > 0) {
                    String txnId = newTransactionId();
                    String sqlTxn = "INSERT INTO transactions (transaction_id, account_number, type, amount, balance_after, description, timestamp) " +
                                    "VALUES (?, ?, 'DEPOSIT', ?, ?, 'Initial Account Deposit', ?)";
                    try (PreparedStatement pstmtTxn = conn.prepareStatement(sqlTxn)) {
                        pstmtTxn.setString(1, txnId);
                        pstmtTxn.setString(2, accNo);
                        pstmtTxn.setDouble(3, initialDeposit);
                        pstmtTxn.setDouble(4, initialDeposit);
                        pstmtTxn.setString(5, now);
                        pstmtTxn.executeUpdate();
                    }

                    String sqlVault = "UPDATE atm_vault SET total_cash = total_cash + ? WHERE id = 1";
                    try (PreparedStatement pstmtV = conn.prepareStatement(sqlVault)) {
                        pstmtV.setDouble(1, initialDeposit);
                        pstmtV.executeUpdate();
                    }
                }

                conn.commit();
                return getAccount(accNo);
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    private static void recordFailedAttempt(String accNo) {
        Account a = getAccount(accNo);
        if (a == null) return;
        int newFails = a.fails + 1;
        boolean lock = newFails >= 3;
        String sql = "UPDATE accounts SET failed_attempts = ?, is_locked = ? WHERE account_number = ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, newFails);
            pstmt.setInt(2, lock ? 1 : 0);
            pstmt.setString(3, accNo);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void resetFailedAttempts(String accNo) {
        String sql = "UPDATE accounts SET failed_attempts = 0 WHERE account_number = ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, accNo);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void unlockAccount(String accNo) throws Exception {
        Account account = getAccount(accNo);
        if (account == null || account.isAdmin) throw new Exception("Customer account not found");
        String sql = "UPDATE accounts SET failed_attempts = 0, is_locked = 0 WHERE account_number = ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, accNo);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new Exception("Could not unlock account", e);
        }
    }

    private static void refillAtmVault(double amount) throws Exception {
        validateMoney(amount, false);
        String now = timestamp();
        String sql = "UPDATE atm_vault SET total_cash = total_cash + ?, last_refilled_at = ? WHERE id = 1";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDouble(1, amount);
            pstmt.setString(2, now);
            pstmt.executeUpdate();
        }
    }

    private static List<TransactionRecord> getTransactions(String accNo, int limit) {
        List<TransactionRecord> list = new ArrayList<>();
        String sql = accNo == null ?
            "SELECT * FROM transactions ORDER BY rowid DESC LIMIT ?" :
            "SELECT * FROM transactions WHERE account_number = ? ORDER BY rowid DESC LIMIT ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            if (accNo == null) {
                pstmt.setInt(1, limit);
            } else {
                pstmt.setString(1, accNo);
                pstmt.setInt(2, limit);
            }
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                list.add(new TransactionRecord(
                    rs.getString("transaction_id"),
                    rs.getString("account_number"),
                    rs.getString("type"),
                    rs.getDouble("amount"),
                    rs.getDouble("balance_after"),
                    rs.getString("description"),
                    rs.getString("target_account"),
                    rs.getString("timestamp")
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    private static String depositCash(Account a, double amount) throws Exception {
        validateMoney(amount, false);

        String now = timestamp();
        double newBal = a.balance + amount;
        String txnId = newTransactionId();

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                String sqlAcc = "UPDATE accounts SET balance = ? WHERE account_number = ?";
                try (PreparedStatement p = conn.prepareStatement(sqlAcc)) {
                    p.setDouble(1, newBal);
                    p.setString(2, a.no);
                    p.executeUpdate();
                }

                String sqlVault = "UPDATE atm_vault SET total_cash = total_cash + ? WHERE id = 1";
                try (PreparedStatement p = conn.prepareStatement(sqlVault)) {
                    p.setDouble(1, amount);
                    p.executeUpdate();
                }

                String sqlTxn = "INSERT INTO transactions (transaction_id, account_number, type, amount, balance_after, description, timestamp) " +
                                "VALUES (?, ?, 'DEPOSIT', ?, ?, 'Cash Deposit at ATM Slot', ?)";
                try (PreparedStatement p = conn.prepareStatement(sqlTxn)) {
                    p.setString(1, txnId);
                    p.setString(2, a.no);
                    p.setDouble(3, amount);
                    p.setDouble(4, newBal);
                    p.setString(5, now);
                    p.executeUpdate();
                }

                conn.commit();
                return "Deposited " + money(amount) + " into your account. Ref: " + txnId;
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    private static String withdrawCash(Account a, double amount) throws Exception {
        validateMoney(amount, false);
        if (amount > 20000) throw new Exception("Single withdrawal limit is ₹20,000");
        if (amount % 100 != 0) throw new Exception("Amount must be a multiple of ₹100");
        if (amount > a.balance) throw new Exception("Insufficient account balance");

        String today = LocalDate.now().toString();
        double currentDaily = a.dailyWithdrawn;
        if (!today.equals(a.lastWithdrawDate)) {
            currentDaily = 0.0;
        }
        if (currentDaily + amount > 50000) {
            throw new Exception("Daily withdrawal limit of ₹50,000 exceeded. Already withdrawn today: " + money(currentDaily));
        }

        AtmVault vault = getAtmVault();
        if (vault.totalCash < amount) {
            throw new Exception("ATM machine has insufficient cash reserve. Max cash available: " + money(vault.totalCash));
        }

        String now = timestamp();
        double newBal = a.balance - amount;
        double newDaily = currentDaily + amount;
        String txnId = newTransactionId();
        String noteMsg = calculateNoteBreakdown((int) amount);

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                String sqlAcc = "UPDATE accounts SET balance = ?, daily_withdrawn = ?, last_withdraw_date = ? WHERE account_number = ?";
                try (PreparedStatement p = conn.prepareStatement(sqlAcc)) {
                    p.setDouble(1, newBal);
                    p.setDouble(2, newDaily);
                    p.setString(3, today);
                    p.setString(4, a.no);
                    p.executeUpdate();
                }

                String sqlVault = "UPDATE atm_vault SET total_cash = total_cash - ? WHERE id = 1";
                try (PreparedStatement p = conn.prepareStatement(sqlVault)) {
                    p.setDouble(1, amount);
                    p.executeUpdate();
                }

                String sqlTxn = "INSERT INTO transactions (transaction_id, account_number, type, amount, balance_after, description, timestamp) " +
                                "VALUES (?, ?, 'WITHDRAWAL', ?, ?, ?, ?)";
                try (PreparedStatement p = conn.prepareStatement(sqlTxn)) {
                    p.setString(1, txnId);
                    p.setString(2, a.no);
                    p.setDouble(3, -amount);
                    p.setDouble(4, newBal);
                    p.setString(5, "ATM Cash Withdrawal (" + noteMsg + ")");
                    p.setString(6, now);
                    p.executeUpdate();
                }

                conn.commit();
                return "Withdrawn " + money(amount) + ". Collect your cash (" + noteMsg + "). Ref: " + txnId;
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    private static String transferMoney(Account fromAcc, String recipientNo, double amount) throws Exception {
        validateMoney(amount, false);
        if (amount > fromAcc.balance) throw new Exception("Insufficient account balance");

        Account toAcc = getAccount(recipientNo.trim());
        if (toAcc == null) throw new Exception("Recipient account number not found");
        if (toAcc.no.equals(fromAcc.no)) throw new Exception("Cannot transfer money to yourself");
        if (toAcc.isAdmin) throw new Exception("Cannot transfer money to Admin account");

        String now = timestamp();
        double newFromBal = fromAcc.balance - amount;
        double newToBal = toAcc.balance + amount;
        String txnIdSender = newTransactionId();
        String txnIdRecv = newTransactionId();

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                String sqlSender = "UPDATE accounts SET balance = ? WHERE account_number = ?";
                try (PreparedStatement p = conn.prepareStatement(sqlSender)) {
                    p.setDouble(1, newFromBal);
                    p.setString(2, fromAcc.no);
                    p.executeUpdate();
                }

                String sqlRecv = "UPDATE accounts SET balance = ? WHERE account_number = ?";
                try (PreparedStatement p = conn.prepareStatement(sqlRecv)) {
                    p.setDouble(1, newToBal);
                    p.setString(2, toAcc.no);
                    p.executeUpdate();
                }

                String sqlTxn1 = "INSERT INTO transactions (transaction_id, account_number, type, amount, balance_after, description, target_account, timestamp) " +
                                 "VALUES (?, ?, 'TRANSFER_SENT', ?, ?, ?, ?, ?)";
                try (PreparedStatement p = conn.prepareStatement(sqlTxn1)) {
                    p.setString(1, txnIdSender);
                    p.setString(2, fromAcc.no);
                    p.setDouble(3, -amount);
                    p.setDouble(4, newFromBal);
                    p.setString(5, "Sent to " + toAcc.name + " (" + toAcc.no + ")");
                    p.setString(6, toAcc.no);
                    p.setString(7, now);
                    p.executeUpdate();
                }

                String sqlTxn2 = "INSERT INTO transactions (transaction_id, account_number, type, amount, balance_after, description, target_account, timestamp) " +
                                 "VALUES (?, ?, 'TRANSFER_RECV', ?, ?, ?, ?, ?)";
                try (PreparedStatement p = conn.prepareStatement(sqlTxn2)) {
                    p.setString(1, txnIdRecv);
                    p.setString(2, toAcc.no);
                    p.setDouble(3, amount);
                    p.setDouble(4, newToBal);
                    p.setString(5, "Received from " + fromAcc.name + " (" + fromAcc.no + ")");
                    p.setString(6, fromAcc.no);
                    p.setString(7, now);
                    p.executeUpdate();
                }

                conn.commit();
                return "Sent " + money(amount) + " to " + toAcc.name + " (" + toAcc.no + "). Ref: " + txnIdSender;
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    private static void changePin(Account a, String oldPin, String newPin) throws Exception {
        if (!a.pin.equals(oldPin)) throw new Exception("Current PIN is incorrect");
        if (newPin == null || !newPin.matches("\\d{4}")) throw new Exception("New PIN must be 4 digits");
        if (newPin.equals(oldPin)) throw new Exception("New PIN must be different from current PIN");

        String sql = "UPDATE accounts SET pin = ? WHERE account_number = ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newPin);
            pstmt.setString(2, a.no);
            pstmt.executeUpdate();
        }
    }

    private static String timestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String newTransactionId() {
        return "TXN-" + UUID.randomUUID();
    }

    private static void validateMoney(double value, boolean zeroAllowed) throws Exception {
        if (!Double.isFinite(value) || value < 0 || (!zeroAllowed && value == 0)) {
            throw new Exception(zeroAllowed ? "Amount cannot be negative" : "Amount must be greater than zero");
        }
        BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
        if (decimal.scale() > 2) throw new Exception("Amount can have at most 2 decimal places");
    }

    private static String calculateNoteBreakdown(int amount) {
        int rem = amount;
        int n2000 = rem / 2000; rem %= 2000;
        int n500 = rem / 500; rem %= 500;
        int n200 = rem / 200; rem %= 200;
        int n100 = rem / 100;

        List<String> notes = new ArrayList<>();
        if (n2000 > 0) notes.add(n2000 + "x\u20B92,000");
        if (n500 > 0) notes.add(n500 + "x\u20B9500");
        if (n200 > 0) notes.add(n200 + "x\u20B9200");
        if (n100 > 0) notes.add(n100 + "x\u20B9100");

        return String.join(", ", notes);
    }

    // --- Styling & Dynamic Themes ---
    static final String CSS = """
            :root{--bg:#f6edd0;--fg:#2a2210;--sub:#7a6a45;--glass:rgba(255,252,242,.76);--glass-b:rgba(255,255,255,.9);--field:rgba(255,253,246,.88);--sheet:#fbf6e8;--line:rgba(120,90,20,.14);--pos:#1b7f4b;--neg:#b3263e;--a1:#c9a227;--a2:#e6c766;--a3:#8a6a12;--blue:#9a7712;--shadow:0 18px 44px -22px rgba(140,100,10,.4);color-scheme:light}
            
            [data-theme='emerald']{--bg:#0b2118;--fg:#e6f5ee;--sub:#6ee7b7;--glass:rgba(15,40,30,.76);--glass-b:rgba(40,80,60,.8);--field:rgba(20,50,38,.88);--sheet:#0d291e;--line:rgba(52,211,153,.2);--pos:#34d399;--neg:#f87171;--a1:#059669;--a2:#34d399;--a3:#047857;--blue:#10b981;--shadow:0 18px 44px -22px rgba(5,150,105,.5);color-scheme:dark}
            [data-theme='sapphire']{--bg:#0a1224;--fg:#f0f6ff;--sub:#93c5fd;--glass:rgba(15,23,42,.76);--glass-b:rgba(30,58,138,.8);--field:rgba(20,35,65,.88);--sheet:#0d1830;--line:rgba(96,165,250,.2);--pos:#38bdf8;--neg:#f87171;--a1:#2563eb;--a2:#60a5fa;--a3:#1d4ed8;--blue:#3b82f6;--shadow:0 18px 44px -22px rgba(37,99,235,.5);color-scheme:dark}
            [data-theme='rose']{--bg:#221019;--fg:#fdf2f8;--sub:#fbcfe8;--glass:rgba(42,20,30,.76);--glass-b:rgba(131,24,67,.8);--field:rgba(50,25,38,.88);--sheet:#27121d;--line:rgba(244,114,182,.2);--pos:#f472b6;--neg:#fb7185;--a1:#be185d;--a2:#f472b6;--a3:#9d174d;--blue:#db2777;--shadow:0 18px 44px -22px rgba(190,24,93,.5);color-scheme:dark}

            *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
            html{scroll-behavior:smooth}
            body{margin:0;min-height:100vh;background:var(--bg) linear-gradient(160deg,var(--bg) 0%,var(--sheet) 100%) fixed;color:var(--fg);font:16px/1.4 'Inter',-apple-system,BlinkMacSystemFont,'SF Pro Display','Segoe UI',Roboto,Arial,sans-serif;-webkit-font-smoothing:antialiased;overflow-x:hidden}
            
            .welcome-tag{display:inline-flex;align-items:center;gap:6px;padding:5px 12px;background:rgba(201,162,39,.12);border:1px solid var(--line);border-radius:99px;font-size:12.5px;font-weight:700;color:var(--a3);margin-bottom:12px;animation:fadeInDown .6s ease both}
            @keyframes fadeInDown{from{opacity:0;transform:translateY(-8px)}to{opacity:1;transform:translateY(0)}}

            .aurora{position:fixed;inset:0;z-index:-1;overflow:hidden;pointer-events:none}
            .aurora i{position:absolute;border-radius:50%;filter:blur(100px);opacity:.4;animation:float 18s ease-in-out infinite}
            .aurora i:nth-child(1){width:46vmax;height:46vmax;background:var(--a1);top:-14vmax;left:-12vmax}
            .aurora i:nth-child(2){width:38vmax;height:38vmax;background:var(--a2);bottom:-12vmax;right:-10vmax;animation-delay:-6s;opacity:.45}
            .aurora i:nth-child(3){width:30vmax;height:30vmax;background:var(--sub);top:40%;left:55%;animation-delay:-11s;opacity:.25}
            @keyframes float{0%,100%{transform:translate(0,0) scale(1)}33%{transform:translate(6vmax,4vmax) scale(1.12)}66%{transform:translate(-4vmax,7vmax) scale(.94)}}
            
            .wrap{max-width:520px;margin:0 auto;padding:max(22px,env(safe-area-inset-top)) 20px 84px;animation:rise .7s cubic-bezier(.2,.8,.2,1) both}
            .wrap-admin{max-width:840px;margin:0 auto;padding:max(22px,env(safe-area-inset-top)) 20px 84px;animation:rise .7s cubic-bezier(.2,.8,.2,1) both}
            @keyframes rise{from{opacity:0;transform:translateY(18px)}}
            
            .top{display:flex;justify-content:space-between;align-items:center;margin:6px 0 20px}
            .who{display:flex;align-items:center;gap:12px}
            .av{width:46px;height:46px;border-radius:50%;display:grid;place-items:center;font-weight:700;font-size:19px;color:#fff;background:linear-gradient(135deg,var(--a2),var(--a3));box-shadow:0 8px 20px -8px var(--a3)}
            .who small{display:block;color:var(--sub);font-size:13px}
            .who b{font-size:20px;letter-spacing:-.02em}

            .top-actions{display:flex;gap:8px;align-items:center}
            .jdbc-pill{background:rgba(27,127,75,.15);color:var(--pos);font-size:12px;font-weight:700;padding:6px 12px;border-radius:99px;border:1px solid rgba(27,127,75,.3);cursor:pointer;display:flex;align-items:center;gap:6px;backdrop-filter:blur(10px)}
            .jdbc-pill i{width:7px;height:7px;border-radius:50%;background:var(--pos);box-shadow:0 0 8px var(--pos)}

            h1{margin:0;font-size:36px;font-weight:800;letter-spacing:-.03em;line-height:1.1}
            h2{font-size:18px;font-weight:700;letter-spacing:-.01em;margin:0}
            .grad{background:linear-gradient(90deg,var(--a3),var(--a1),var(--a2),var(--a1),var(--a3));-webkit-background-clip:text;background-clip:text;color:transparent;background-size:200% auto;animation:shine 6s linear infinite}
            @keyframes shine{to{background-position:200% center}}
            .sp{margin:24px 0 6px}
            .sub{color:var(--sub);margin:0 0 22px;font-size:15px}
            
            button{font-family:inherit}
            button:focus-visible,input:focus-visible{outline:2px solid var(--blue);outline-offset:2px}
            .ghost{background:var(--glass);border:1px solid var(--glass-b);color:var(--fg);font-size:14px;font-weight:600;cursor:pointer;padding:9px 16px;border-radius:999px;backdrop-filter:blur(14px);-webkit-backdrop-filter:blur(14px);transition:transform .15s,background .2s}
            .ghost:hover{transform:translateY(-1px)}
            .ghost:active{transform:scale(.96)}
            
            .tabs{display:flex;background:var(--field);padding:4px;border-radius:16px;border:1px solid var(--line);margin-bottom:20px}
            .tab{flex:1;text-align:center;padding:10px;font-size:14px;font-weight:700;border:0;background:none;color:var(--sub);cursor:pointer;border-radius:12px;transition:all .2s}
            .tab.act{background:var(--sheet);color:var(--fg);box-shadow:0 4px 12px rgba(0,0,0,.08)}

            .scene{perspective:1100px;margin-bottom:12px}
            .card{aspect-ratio:1.586;border-radius:26px;padding:24px;color:#f8edc8;display:flex;flex-direction:column;justify-content:space-between;position:relative;overflow:hidden;background:linear-gradient(135deg,#3a2f14 0%,#241d0c 45%,#120e05 100%);border:1px solid rgba(230,199,102,.4);box-shadow:0 30px 55px -26px rgba(90,62,5,.7),0 0 0 1px rgba(255,255,255,.04) inset;transform:rotateX(var(--rx,0deg)) rotateY(var(--ry,0deg));transition:transform .25s ease-out;transform-style:preserve-3d;will-change:transform}
            .card:before{content:'';position:absolute;width:140%;height:140%;left:-20%;top:-40%;background:radial-gradient(circle at var(--mx,25%) var(--my,10%),rgba(255,226,140,.28),transparent 42%);pointer-events:none;transition:background .2s}
            .card:after{content:'';position:absolute;right:-60px;bottom:-80px;width:240px;height:240px;border-radius:50%;background:radial-gradient(circle,rgba(212,175,55,.45),transparent 65%);pointer-events:none}
            .ct{display:flex;justify-content:space-between;align-items:center;font-size:20px;letter-spacing:-.01em;position:relative;z-index:1}
            .ct b{font-weight:800;letter-spacing:.02em}
            .chiprow{display:flex;align-items:center;gap:14px;position:relative;z-index:1}
            .chip{width:48px;height:36px;border-radius:8px;background:linear-gradient(135deg,#f6dfa0,#b8903f);position:relative;overflow:hidden}
            .chip:before,.chip:after{content:'';position:absolute;background:rgba(0,0,0,.18)}
            .chip:before{left:0;right:0;top:50%;height:1px}
            .chip:after{top:0;bottom:0;left:50%;width:1px}
            .cnum{font-size:18px;letter-spacing:.18em;font-variant-numeric:tabular-nums;text-shadow:0 2px 8px rgba(0,0,0,.35);position:relative;z-index:1}
            .cb{display:flex;justify-content:space-between;font-size:13px;letter-spacing:.12em;text-transform:uppercase;opacity:.95;position:relative;z-index:1}

            .terminal-info-card{background:var(--glass);border:1px solid var(--glass-b);border-radius:20px;padding:14px 18px;margin-bottom:18px;display:flex;justify-content:space-between;align-items:center;backdrop-filter:blur(16px);-webkit-backdrop-filter:blur(16px)}
            .terminal-info-card small{display:block;color:var(--sub);font-size:12px;font-weight:600}
            .terminal-info-card b{font-size:14px;font-weight:700}

            .bal{margin:18px 0 14px;display:flex;justify-content:space-between;align-items:flex-end}
            .bal small{display:block;color:var(--sub);font-size:14px;margin-bottom:2px;font-weight:500}
            .big{font-size:44px;font-weight:800;letter-spacing:-.04em;font-variant-numeric:tabular-nums;line-height:1.05;transition:filter .3s}
            .big span{font-size:26px;color:var(--sub);font-weight:700}
            .big.hide{filter:blur(14px);user-select:none}
            .eye{width:42px;height:42px;border-radius:50%;border:1px solid var(--glass-b);background:var(--glass);color:var(--sub);display:grid;place-items:center;cursor:pointer;backdrop-filter:blur(14px);-webkit-backdrop-filter:blur(14px)}
            
            .stats{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:16px}
            .stat{background:var(--glass);border:1px solid var(--glass-b);border-radius:18px;padding:14px 16px;backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px)}
            .stat small{display:flex;align-items:center;gap:6px;color:var(--sub);font-size:13px;font-weight:500}
            .stat b{display:block;font-size:19px;margin-top:4px;font-variant-numeric:tabular-nums;letter-spacing:-.02em}
            .stat .dot{width:8px;height:8px;border-radius:50%}
            
            .limit-bar{background:var(--glass);border:1px solid var(--glass-b);border-radius:18px;padding:14px 16px;margin-bottom:20px;backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px)}
            .limit-bar small{display:flex;justify-content:space-between;color:var(--sub);font-size:13px;font-weight:600;margin-bottom:8px}
            .track{height:8px;background:rgba(120,90,20,.12);border-radius:999px;overflow:hidden}
            .fill{height:100%;background:linear-gradient(90deg,var(--a1),var(--a2));border-radius:999px;transition:width .5s ease}
            
            /* Voice Cheat Sheet Panel */
            .voice-panel{background:var(--field);border:1px dashed var(--line);border-radius:18px;padding:12px 16px;margin-bottom:24px}
            .voice-panel-head{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;font-size:13px;font-weight:700;color:var(--sub)}
            .voice-chips{display:flex;gap:6px;overflow-x:auto;padding-bottom:4px;scrollbar-width:none}
            .voice-chips::-webkit-scrollbar{display:none}
            .vchip{background:var(--glass);border:1px solid var(--glass-b);color:var(--fg);font-size:12px;font-weight:600;padding:5px 12px;border-radius:99px;white-space:nowrap;cursor:pointer;transition:transform .15s}
            .vchip:hover{transform:translateY(-1px);background:rgba(201,162,39,.18)}

            .acts{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:28px}
            .act{background:none;border:0;color:inherit;font-size:13px;font-weight:600;cursor:pointer;display:flex;flex-direction:column;align-items:center;gap:9px}
            .act i{width:62px;height:62px;border-radius:22px;background:var(--glass);border:1px solid var(--glass-b);display:grid;place-items:center;color:var(--blue);transition:transform .2s,box-shadow .2s,background .2s;backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px);box-shadow:var(--shadow)}
            .act:hover i{transform:translateY(-4px);background:linear-gradient(135deg,var(--a2),var(--a1));color:#2a2210;box-shadow:0 16px 30px -12px var(--a3)}
            .act:active i{transform:scale(.92)}

            .head{display:flex;justify-content:space-between;align-items:center;margin-bottom:12px}
            .head small{color:var(--sub);font-size:13px}
            .list{background:var(--glass);border:1px solid var(--glass-b);border-radius:22px;overflow:hidden;backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px)}
            .row{display:flex;align-items:center;gap:14px;padding:14px 16px;border-bottom:1px solid var(--line);animation:rise .5s cubic-bezier(.2,.8,.2,1) both;transition:background .2s;cursor:pointer}
            .row:hover{background:var(--field)}
            .row:last-child{border:0}
            .ic{width:40px;height:40px;border-radius:14px;display:grid;place-items:center;flex:none}
            .ic.in{background:rgba(27,127,75,.12);color:var(--pos)}
            .ic.out{background:rgba(179,38,62,.1);color:var(--neg)}
            .ic.nil{background:var(--field);color:var(--sub)}
            .t{flex:1;min-width:0}
            .t b{display:block;font-weight:600;font-size:14.5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
            .t small{color:var(--sub);font-size:12px}
            .v{font-weight:700;font-variant-numeric:tabular-nums;letter-spacing:-.01em;text-align:right}
            .v small{display:block;font-weight:500;font-size:11px;color:var(--sub)}
            .pos{color:var(--pos)}
            .empty{padding:36px 16px;text-align:center;color:var(--sub);font-size:15px}
            .empty svg{display:block;margin:0 auto 10px;opacity:.6}

            .group{background:var(--field);border:1px solid var(--line);border-radius:18px;overflow:hidden;margin-bottom:16px}
            .group input,.group select{display:block;width:100%;border:0;border-bottom:1px solid var(--line);padding:16px 18px;font:inherit;background:transparent;color:inherit;outline:0;transition:background .2s}
            .group input::placeholder{color:var(--sub)}
            .group input:last-child,.group select:last-child{border:0}
            .group input:focus,.group select:focus{background:rgba(201,162,39,.12)}
            .recipient-info{padding:0 18px 12px;font-size:13px;font-weight:600;color:var(--pos);display:none}
            
            .pay{width:100%;height:54px;border:0;border-radius:18px;background:linear-gradient(110deg,var(--a2),var(--a1) 50%,var(--a3));color:#241c08;font-size:17px;font-weight:700;cursor:pointer;margin-top:14px;transition:transform .15s,box-shadow .25s;box-shadow:0 14px 28px -12px var(--a3);letter-spacing:.01em}
            .pay:hover{box-shadow:0 18px 34px -12px var(--a3);transform:translateY(-1px)}
            .pay:active{transform:scale(.98)}
            
            .brand{display:flex;align-items:center;gap:10px;margin:6px 0 24px;font-weight:800;font-size:24px;letter-spacing:-.02em}
            .brand i{width:40px;height:40px;border-radius:12px;display:grid;place-items:center;color:#fff;background:linear-gradient(135deg,var(--a2),var(--a3));box-shadow:0 10px 24px -10px var(--a3)}
            .secure{display:flex;justify-content:center;align-items:center;gap:6px;color:var(--sub);font-size:12.5px;margin-top:22px}
            
            dialog{border:0;padding:0;margin:auto auto 0;width:100%;max-width:500px;background:var(--sheet);color:var(--fg);border-radius:32px 32px 0 0;animation:up .4s cubic-bezier(.2,.8,.2,1);box-shadow:0 -30px 80px -24px rgba(0,0,0,.4)}
            dialog::backdrop{background:rgba(0,0,0,.5);backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px)}
            dialog form{padding:12px 22px calc(26px + env(safe-area-inset-bottom))}
            dialog form:before{content:'';display:block;width:42px;height:5px;border-radius:99px;background:var(--line);margin:0 auto 14px}
            
            .bar{display:grid;grid-template-columns:1fr auto 1fr;align-items:center;margin-bottom:6px}
            .bar b{font-size:17px}
            .bar .ghost{justify-self:start;padding:7px 14px}
            .amt{display:flex;justify-content:center;align-items:baseline;gap:6px;padding:18px 0 12px}
            .amt span{font-size:40px;color:var(--sub);font-weight:700}
            .amt input{font:inherit;font-size:56px;font-weight:800;width:6.5ch;border:0;background:transparent;color:inherit;outline:0;letter-spacing:-.03em;-moz-appearance:textfield;text-align:left}
            .amt input::-webkit-inner-spin-button,.amt input::-webkit-outer-spin-button{-webkit-appearance:none;margin:0}
            .hint{text-align:center;color:var(--sub);font-size:13px;margin:0 0 14px}
            .chips{display:flex;gap:8px;justify-content:center;flex-wrap:wrap}
            .chips button{border:1px solid var(--line);background:var(--field);color:inherit;border-radius:999px;padding:9px 16px;font-size:14.5px;font-weight:600;cursor:pointer;transition:transform .15s,background .2s}
            .chips button:hover{background:rgba(201,162,39,.2)}
            .chips button:active{transform:scale(.94)}
            
            .toast{position:fixed;top:16px;left:50%;transform:translateX(-50%);display:flex;align-items:center;gap:10px;background:rgba(36,29,12,.94);color:#fff;padding:13px 22px;border-radius:999px;font-size:15px;font-weight:600;z-index:99;border:1px solid rgba(230,199,102,.3);box-shadow:0 16px 40px rgba(60,40,5,.4);backdrop-filter:blur(16px);-webkit-backdrop-filter:blur(16px);animation:drop .55s cubic-bezier(.2,.9,.3,1.2);max-width:calc(100% - 32px)}
            .toast.ok svg{color:#6fdca0}
            .toast.err svg{color:#ff8fa3}
            .toast.out{opacity:0;transform:translate(-50%,-30px);transition:all .4s}
            
            /* Floating Mic Button */
            .mic-fab{position:fixed;bottom:24px;right:24px;width:60px;height:60px;border-radius:50%;background:linear-gradient(135deg,var(--a2),var(--a3));color:#fff;display:grid;place-items:center;border:2px solid rgba(255,255,255,.6);box-shadow:0 12px 30px var(--shadow);cursor:pointer;z-index:90;transition:transform .2s,box-shadow .2s}
            .mic-fab:hover{transform:scale(1.08)}
            .mic-fab.active{animation:mic-pulse 1.2s infinite ease-in-out;background:linear-gradient(135deg,#e11d48,#be123c)}
            @keyframes mic-pulse{0%,100%{box-shadow:0 0 0 0 rgba(225,29,72,.6)}50%{box-shadow:0 0 0 16px rgba(225,29,72,0)}}

            /* Receipt Card Styling */
            .receipt-body{background:#fff;border-radius:22px;padding:24px;color:#222;box-shadow:0 10px 30px rgba(0,0,0,.08);margin:12px 0 18px;font-family:monospace,sans-serif}
            .receipt-head{text-align:center;border-bottom:2px dashed #ddd;padding-bottom:14px;margin-bottom:14px}
            .receipt-head h3{margin:0;font-size:20px;font-weight:800;letter-spacing:.05em}
            .receipt-row{display:flex;justify-content:space-between;margin:8px 0;font-size:13.5px;color:#444}
            .receipt-row.bold{font-weight:700;font-size:16px;color:#000;border-top:1px solid #eee;border-bottom:1px solid #eee;padding:8px 0;margin-top:12px}

            /* Admin Table & Inspector */
            .admin-grid{display:grid;grid-template-columns:1fr 1fr 1fr;gap:14px;margin-bottom:24px}
            .tbl-wrap{background:var(--glass);border:1px solid var(--glass-b);border-radius:20px;overflow:hidden;backdrop-filter:blur(16px);-webkit-backdrop-filter:blur(16px)}
            table{width:100%;border-collapse:collapse;text-align:left;font-size:14px}
            th,td{padding:14px 16px;border-bottom:1px solid var(--line)}
            th{background:rgba(201,162,39,.1);font-weight:700;color:var(--fg)}
            tr:last-child td{border:0}
            .badge{padding:4px 10px;border-radius:99px;font-size:12px;font-weight:700}
            .badge.active{background:rgba(27,127,75,.15);color:var(--pos)}
            .badge.locked{background:rgba(179,38,62,.15);color:var(--neg)}
            .btn-sm{padding:5px 12px;font-size:12px;border-radius:99px;border:0;background:linear-gradient(135deg,var(--a2),var(--a3));color:#fff;font-weight:700;cursor:pointer}

            @keyframes fall{to{transform:translateY(105vh) rotate(720deg);opacity:0}}
            @keyframes drop{from{transform:translate(-50%,-90px);opacity:0}}
            @keyframes up{from{transform:translateY(100%)}}
            @media(max-width:480px){.big{font-size:38px}.cnum{font-size:15px}.admin-grid{grid-template-columns:1fr}}
            @media(prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}}
            """;

    static final String JS = """
            (function(){
              // Load saved theme preference
              var savedTheme = localStorage.getItem('cashpoint_theme') || 'gold';
              if(savedTheme !== 'gold') document.body.setAttribute('data-theme', savedTheme);

              var card=document.querySelector('.card');
              if(card){
                var sc=card.parentElement;
                sc.addEventListener('pointermove',function(e){
                  var r=card.getBoundingClientRect();
                  var x=(e.clientX-r.left)/r.width,y=(e.clientY-r.top)/r.height;
                  card.style.setProperty('--ry',((x-.5)*16)+'deg');
                  card.style.setProperty('--rx',((.5-y)*16)+'deg');
                  card.style.setProperty('--mx',(x*100)+'%');
                  card.style.setProperty('--my',(y*100)+'%');
                });
                sc.addEventListener('pointerleave',function(){
                  card.style.setProperty('--rx','0deg');card.style.setProperty('--ry','0deg');
                });
              }
              var big=document.getElementById('bigbal');
              if(big){
                var v=parseFloat(big.getAttribute('data-v'));
                var from=parseFloat(sessionStorage.getItem('lastbal')||'0');
                if(isNaN(from))from=0;
                var t0=null,dur=900;
                function fmt(n){
                  var s=n.toLocaleString('en-US',{minimumFractionDigits:2,maximumFractionDigits:2}).split('.');
                  big.innerHTML='\\u20B9'+s[0]+'<span>.'+s[1]+'</span>';
                }
                function step(ts){
                  if(!t0)t0=ts;
                  var p=Math.min((ts-t0)/dur,1),e=1-Math.pow(1-p,4);
                  fmt(from+(v-from)*e);
                  if(p<1)requestAnimationFrame(step);
                }
                requestAnimationFrame(step);
                sessionStorage.setItem('lastbal',String(v));
                if(sessionStorage.getItem('hide')==='1')big.classList.add('hide');
                var eye=document.getElementById('eye');
                if(eye)eye.addEventListener('click',function(){
                  var h=big.classList.toggle('hide');
                  sessionStorage.setItem('hide',h?'1':'0');
                });
              }

              var t=document.getElementById('toast');
              if(t){
                var txt = t.querySelector('span') ? t.querySelector('span').innerText : '';
                if(txt && localStorage.getItem('voice_tts') !== '0'){
                  speakText(txt);
                }
                setTimeout(function(){t.classList.add('out')},3400);
                setTimeout(function(){t.remove()},3900);
                if(t.classList.contains('ok')&&t.getAttribute('data-party')==='1'){
                  var cols=['#c9a227','#e6c766','#f0d58a','#8a6a12','#fff3c4'];
                  for(var i=0;i<30;i++){
                    var c=document.createElement('i');c.className='confetti';
                    c.style.left=(Math.random()*100)+'vw';
                    c.style.background=cols[i%cols.length];
                    c.style.animationDelay=(Math.random()*.4)+'s';
                    c.style.animationDuration=(1.2+Math.random()*1.2)+'s';
                    document.body.appendChild(c);
                    setTimeout(function(el){el.remove()}.bind(null,c),3000);
                  }
                }
              }
              if(window.history.replaceState)window.history.replaceState(null,'',location.pathname==='/action'?'/':location.pathname);
              document.querySelectorAll('[data-voice-setting]').forEach(function(control){
                control.checked = localStorage.getItem(control.getAttribute('data-voice-setting')) !== '0';
              });
            })();

            // Web Speech Voice Engine
            var recognition = null;
            var isListening = false;

            function initVoice() {
              var SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
              if (!SpeechRecognition) return;
              recognition = new SpeechRecognition();
              recognition.continuous = false;
              recognition.interimResults = false;
              recognition.lang = 'en-US';

              recognition.onstart = function() {
                isListening = true;
                updateMicUI(true);
                speakText("Listening");
              };

              recognition.onend = function() {
                isListening = false;
                updateMicUI(false);
              };

              recognition.onresult = function(event) {
                var transcript = event.results[0][0].transcript.toLowerCase().trim();
                handleVoiceCommand(transcript);
              };
            }

            function toggleVoice() {
              if (localStorage.getItem('voice_cmd') === '0') {
                showToast("Voice commands disabled in Settings", false, false);
                return;
              }
              if (!recognition) initVoice();
              if (!recognition) {
                alert("Speech recognition is not supported in this browser. Please use Chrome, Edge, or Safari.");
                return;
              }
              if (isListening) {
                try { recognition.stop(); } catch(e){}
              } else {
                try { recognition.start(); } catch(e){}
              }
            }

            function updateMicUI(active) {
              var fab = document.getElementById('mic-fab');
              if (fab) {
                if (active) fab.classList.add('active');
                else fab.classList.remove('active');
              }
            }

            function speakText(text) {
              if (localStorage.getItem('voice_tts') === '0') return;
              if ('speechSynthesis' in window) {
                window.speechSynthesis.cancel();
                var clean = conciseAnnouncement(text).replace(/₹/g, " rupees ").replace(/TXN-[\\w-]+/g, "");
                var u = new SpeechSynthesisUtterance(clean);
                u.rate = 1.0;
                u.pitch = 1.0;
                window.speechSynthesis.speak(u);
              }
            }

            function conciseAnnouncement(text) {
              var match = text.match(/^Deposited\\s+(₹[\\d,.]+)/);
              if (match) return "Deposited " + match[1];
              match = text.match(/^Withdrawn\\s+(₹[\\d,.]+)/);
              if (match) return "Withdrawn " + match[1];
              match = text.match(/^Sent\\s+(₹[\\d,.]+)/);
              if (match) return "Sent " + match[1];
              match = text.match(/^ATM Machine Vault successfully refilled with\\s+(₹[\\d,.]+)/);
              if (match) return "Vault refilled with " + match[1];
              if (text.startsWith("ATM PIN successfully updated")) return "PIN successfully updated";
              return text;
            }

            function handleVoiceCommand(cmd) {
              showToast("🎙️ Voice Command: \\\"" + cmd + "\\\"", true, false);

              if (cmd.includes("deposit")) {
                var match = cmd.match(/\\d+/);
                document.getElementById('deposit').showModal();
                if (match) document.querySelector('#deposit [name=amount]').value = match[0];
                speakText("Opening cash deposit");
              } else if (cmd.includes("withdraw")) {
                var match = cmd.match(/\\d+/);
                document.getElementById('withdraw').showModal();
                if (match) document.querySelector('#withdraw [name=amount]').value = match[0];
                speakText("Opening cash withdrawal");
              } else if (cmd.includes("send") || cmd.includes("transfer")) {
                var match = cmd.match(/\\d+/);
                document.getElementById('transfer').showModal();
                if (match) document.querySelector('#transfer [name=amount]').value = match[0];
                speakText("Opening transfer money");
              } else if (cmd.includes("pin")) {
                document.getElementById('pin').showModal();
                speakText("Opening PIN change");
              } else if (cmd.includes("hide") || cmd.includes("show balance")) {
                var eye = document.getElementById('eye');
                if (eye) eye.click();
              } else if (cmd.includes("balance")) {
                var big = document.getElementById('bigbal');
                if (big) {
                  var v = big.getAttribute('data-v');
                  speakText("Your total available balance is " + v + " rupees");
                }
              } else if (cmd.includes("setting") || cmd.includes("option")) {
                document.getElementById('settings-dialog').showModal();
                speakText("Opening settings");
              } else if (cmd.includes("db") || cmd.includes("database") || cmd.includes("jdbc")) {
                openDbInspector();
                speakText("Opening JDBC database inspector");
              } else if (cmd.includes("help") || cmd.includes("command")) {
                document.getElementById('voice-help-dialog').showModal();
                speakText("Opening voice command help");
              } else if (cmd.includes("sign out") || cmd.includes("logout")) {
                speakText("Signing out");
                var f = document.querySelector('form[action="/logout"]');
                if(f) f.submit();
              } else {
                speakText("Command not recognized. Try saying deposit, withdraw, or check balance.");
              }
            }

            function setTheme(theme) {
              if (theme === 'gold') document.body.removeAttribute('data-theme');
              else document.body.setAttribute('data-theme', theme);
              localStorage.setItem('cashpoint_theme', theme);
            }

            function updateVoiceSetting(key, enabled) {
              localStorage.setItem(key, enabled ? '1' : '0');
            }

            function openDbInspector() {
              fetch('/db-info')
                .then(function(res){ return res.json(); })
                .then(function(data){
                  document.getElementById('db-url').innerText = data.dbUrl;
                  document.getElementById('db-driver').innerText = data.driver;
                  document.getElementById('db-path').innerText = data.dbPath;
                  document.getElementById('db-accs').innerText = data.accountsCount;
                  document.getElementById('db-txns').innerText = data.transactionsCount;
                  document.getElementById('db-vault').innerText = '₹' + data.vaultCash.toLocaleString('en-US',{minimumFractionDigits:2});
                  document.getElementById('db-status').innerText = data.status;
                  document.getElementById('db-inspector-dialog').showModal();
                }).catch(function(err){
                  alert("Failed to connect to JDBC database inspector: " + err);
                });
            }

            function switchTab(mode){
              document.getElementById('tab-login').classList.toggle('act', mode==='login');
              document.getElementById('tab-reg').classList.toggle('act', mode==='reg');
              document.getElementById('form-login').style.display = mode==='login' ? 'block' : 'none';
              document.getElementById('form-reg').style.display = mode==='reg' ? 'block' : 'none';
            }

            function lookupRecipient(input){
              var val = input.value.trim();
              var info = document.getElementById('rec-info');
              if(!info) return;
              if(val.length >= 4){
                fetch('/lookup?acc=' + encodeURIComponent(val))
                  .then(function(res){ return res.json(); })
                  .then(function(data){
                    if(data.found){
                      info.style.display = 'block';
                      info.style.color = 'var(--pos)';
                      info.textContent = '✓ Verified Recipient: ' + data.name + ' (' + data.no + ')';
                    } else {
                      info.style.display = 'block';
                      info.style.color = 'var(--neg)';
                      info.textContent = '✕ Recipient account not found';
                    }
                  }).catch(function(){ info.style.display = 'none'; });
              } else {
                info.style.display = 'none';
              }
            }

            function showReceipt(id, type, amt, bal, desc, time, accNo){
              document.getElementById('r-id').innerText = id;
              document.getElementById('r-type').innerText = type;
              document.getElementById('r-amt').innerText = amt;
              document.getElementById('r-bal').innerText = bal;
              document.getElementById('r-desc').innerText = desc;
              document.getElementById('r-time').innerText = time;
              document.getElementById('r-acc').innerText = accNo;
              document.getElementById('receipt-dialog').showModal();
            }

            function showToast(msg, ok, party) {
              var old = document.getElementById('toast');
              if (old) old.remove();
              var div = document.createElement('div');
              div.id = 'toast';
              div.className = 'toast ' + (ok ? 'ok' : 'err');
              div.innerHTML = (ok ? "<svg viewBox='0 0 24 24' width=20 height=20 fill=none stroke=currentColor stroke-width=2 stroke-linecap=round stroke-linejoin=round><path d='M5 13l4 4L19 7'/></svg>" : "<svg viewBox='0 0 24 24' width=20 height=20 fill=none stroke=currentColor stroke-width=2 stroke-linecap=round stroke-linejoin=round><circle cx='12' cy='12' r='9'/><path d='M12 7.5v5M12 16v.5'/></svg>") + "<span>" + msg + "</span>";
              document.body.appendChild(div);
              if (ok && party) {
                var cols=['#c9a227','#e6c766','#f0d58a','#8a6a12','#fff3c4'];
                for(var i=0;i<30;i++){
                  var c=document.createElement('i');c.className='confetti';
                  c.style.left=(Math.random()*100)+'vw';
                  c.style.background=cols[i%cols.length];
                  c.style.animationDelay=(Math.random()*.4)+'s';
                  c.style.animationDuration=(1.2+Math.random()*1.2)+'s';
                  document.body.appendChild(c);
                  setTimeout(function(el){el.remove()}.bind(null,c),3000);
                }
              }
              setTimeout(function(){div.classList.add('out')},3400);
              setTimeout(function(){div.remove()},3900);
            }
            """;

    static final String I_DOWN = "<path d='M12 5v14M5 12l7 7 7-7'/>";
    static final String I_UP = "<path d='M12 19V5M5 12l7-7 7 7'/>";
    static final String I_SEND = "<path d='M7 17L17 7M8 7h9v9'/>";
    static final String I_LOCK = "<rect x='5' y='11' width='14' height='9' rx='2'/><path d='M8 11V8a4 4 0 018 0v3'/>";
    static final String I_WAVE = "<path d='M8.5 8.5a5 5 0 010 7M12 6a9 9 0 010 12M15.5 3.5a13 13 0 010 17'/>";
    static final String I_OK = "<path d='M5 13l4 4L19 7'/>";
    static final String I_ERR = "<circle cx='12' cy='12' r='9'/><path d='M12 7.5v5M12 16v.5'/>";
    static final String I_EYE = "<path d='M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7z'/><circle cx='12' cy='12' r='3'/>";
    static final String I_BOLT = "<path d='M13 2L4 14h7l-1 8 9-12h-7z'/>";
    static final String I_EMPTY = "<rect x='3' y='5' width='18' height='14' rx='3'/><path d='M3 10h18'/>";
    static final String I_GEAR = "<path d='M12 15a3 3 0 100-6 3 3 0 000 6z'/><path d='M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-2 2 2 2 0 01-2-2v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83 0 2 2 0 010-2.83l.06-.06a1.65 1.65 0 00.33-1.82 1.65 1.65 0 00-1.51-1H3a2 2 0 01-2-2 2 2 0 012-2h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 010-2.83 2 2 0 012.83 0l.06.06a1.65 1.65 0 001.82.33H9a1.65 1.65 0 001-1.51V3a2 2 0 012-2 2 2 0 012 2v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 0 2 2 0 010 2.83l-.06.06a1.65 1.65 0 00-.33 1.82V9a1.65 1.65 0 001.51 1H21a2 2 0 012 2 2 2 0 01-2 2h-.09a1.65 1.65 0 00-1.51 1z'/>";
    static final String I_MIC = "<path d='M12 1a3 3 0 00-3 3v8a3 3 0 006 0V4a3 3 0 00-3-3z'/><path d='M19 10v2a7 7 0 01-14 0v-2M12 19v4M8 23h8'/>";

    static final String AMT = "<div class=amt><span>\u20B9</span><input name=amount type=number step=100 min=100 inputmode=decimal placeholder=0 required autofocus></div>";

    static String money(double a) {
        return "\u20B9" + String.format(Locale.US, "%,.2f", a);
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String escJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    static String escJsArgument(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("\\", "\\\\").replace("'", "\\'")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    static String svg(String path, int size) {
        return "<svg viewBox='0 0 24 24' width=" + size + " height=" + size
                + " fill=none stroke=currentColor stroke-width=2 stroke-linecap=round stroke-linejoin=round>" + path + "</svg>";
    }

    static String page(String body, boolean isAdmin) {
        return "<!DOCTYPE html><html lang=en><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1,viewport-fit=cover'>"
                + "<meta name=theme-color content='#f4e8c3'>"
                + "<link rel=preconnect href='https://fonts.googleapis.com'><link rel=preconnect href='https://fonts.gstatic.com' crossorigin>"
                + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap' rel=stylesheet>"
                + "<title>Cashpoint - ATM Management System</title><style>" + CSS + "</style></head><body>"
                + "<div class=aurora><i></i><i></i><i></i></div>"
                + "<div class='" + (isAdmin ? "wrap-admin" : "wrap") + "'>" + body + "</div>"
                
                // Floating Mic Button
                + "<button id=mic-fab class=mic-fab onclick='toggleVoice()' title='Voice Command Assistant'>" + svg(I_MIC, 26) + "</button>"

                // Settings Dialog
                + "<dialog id=settings-dialog onclick=\"if(event.target===this)this.close()\"><div style='padding:12px 22px calc(24px + env(safe-area-inset-bottom))'>"
                + "<div class=bar><button type=button class=ghost onclick=\"this.closest('dialog').close()\">Close</button><b>ATM Settings & Options</b><span></span></div>"
                + "<div style='margin-top:16px'>"
                
                + "<div style='display:flex;justify-content:space-between;align-items:center;padding:14px 0;border-bottom:1px solid var(--line)'>"
                + "<div><b>🎙️ Voice Commands Engine</b><br><small style='color:var(--sub)'>Listen to spoken voice commands</small></div>"
                + "<input data-voice-setting=voice_cmd type=checkbox checked onchange=\"updateVoiceSetting('voice_cmd', this.checked)\" style='width:20px;height:20px'></div>"

                + "<div style='display:flex;justify-content:space-between;align-items:center;padding:14px 0;border-bottom:1px solid var(--line)'>"
                + "<div><b>🔊 Text-to-Speech Announcements</b><br><small style='color:var(--sub)'>Read transactions & balance aloud</small></div>"
                + "<input data-voice-setting=voice_tts type=checkbox checked onchange=\"updateVoiceSetting('voice_tts', this.checked)\" style='width:20px;height:20px'></div>"

                + "<div style='padding:14px 0;border-bottom:1px solid var(--line)'>"
                + "<b>🎨 App Color Theme</b><br><small style='color:var(--sub);margin-bottom:8px;display:block'>Select visual accent palette</small>"
                + "<div class=group><select onchange=\"setTheme(this.value)\">"
                + "<option value=gold>Luxury Gold (Default)</option>"
                + "<option value=emerald>Dark Emerald</option>"
                + "<option value=sapphire>Midnight Sapphire</option>"
                + "<option value=rose>Rose Gold</option>"
                + "</select></div></div>"

                + "<div style='padding:16px 0 0'>"
                + "<button type=button class=pay onclick=\"openDbInspector();this.closest('dialog').close()\">🗄️ Inspect JDBC SQLite Database</button>"
                + "</div>"

                + "</div></div></dialog>"

                // JDBC Inspector Modal Dialog
                + "<dialog id=db-inspector-dialog onclick=\"if(event.target===this)this.close()\"><div style='padding:20px'>"
                + "<div class=bar><button type=button class=ghost onclick=\"this.closest('dialog').close()\">Close</button><b>🗄️ JDBC Database Inspector</b><span></span></div>"
                + "<div style='background:rgba(0,0,0,.04);border-radius:18px;padding:18px;margin:14px 0;font-size:13.5px'>"
                + "<div style='margin-bottom:8px'><b>Connection Status:</b> <span id=db-status style='color:var(--pos);font-weight:700'></span></div>"
                + "<div style='margin-bottom:8px'><b>JDBC Driver:</b> <span id=db-driver style='font-family:monospace'></span></div>"
                + "<div style='margin-bottom:8px'><b>Connection URL:</b> <span id=db-url style='font-family:monospace'></span></div>"
                + "<div style='margin-bottom:8px'><b>Database File:</b> <span id=db-path style='font-family:monospace;font-size:12px;word-break:break-all'></span></div>"
                + "<hr style='border:0;border-top:1px solid var(--line);margin:12px 0'>"
                + "<div style='display:grid;grid-template-columns:1fr 1fr 1fr;gap:10px;text-align:center'>"
                + "<div><small style='color:var(--sub)'>Accounts</small><br><b id=db-accs style='font-size:18px'></b></div>"
                + "<div><small style='color:var(--sub)'>Transactions</small><br><b id=db-txns style='font-size:18px'></b></div>"
                + "<div><small style='color:var(--sub)'>ATM Vault</small><br><b id=db-vault style='font-size:16px'></b></div>"
                + "</div></div>"
                + "<p style='font-size:12.5px;color:var(--sub);margin:0;line-height:1.5'><b>How JDBC works in Cashpoint:</b><br>"
                + "All data is persisted in SQLite using standard <code>java.sql.*</code> JDBC statements in <code>Cashpoint.java</code> (lines 85–120). Transactions use atomic commit & rollback (<code>setAutoCommit(false)</code>).</p>"
                + "</div></dialog>"

                // Voice Command Help Modal
                + "<dialog id=voice-help-dialog onclick=\"if(event.target===this)this.close()\"><div style='padding:20px'>"
                + "<div class=bar><button type=button class=ghost onclick=\"this.closest('dialog').close()\">Close</button><b>🎙️ Available Voice Commands</b><span></span></div>"
                + "<ul style='padding-left:20px;font-size:14px;color:var(--fg);line-height:1.8'>"
                + "<li><b>\"Deposit [amount]\"</b> &mdash; Opens cash deposit screen</li>"
                + "<li><b>\"Withdraw [amount]\"</b> &mdash; Opens ATM withdrawal screen</li>"
                + "<li><b>\"Send [amount]\"</b> &mdash; Opens transfer screen</li>"
                + "<li><b>\"Check balance\"</b> &mdash; Reads available balance aloud</li>"
                + "<li><b>\"Change PIN\"</b> &mdash; Opens PIN modification dialog</li>"
                + "<li><b>\"Database\" / \"JDBC\"</b> &mdash; Opens JDBC Inspector</li>"
                + "<li><b>\"Settings\"</b> &mdash; Opens app preferences</li>"
                + "<li><b>\"Sign out\"</b> &mdash; Logs out of account</li>"
                + "</ul></div></dialog>"

                + "<script>" + JS + "</script></body></html>";
    }

    static String toast(String msg, boolean ok, boolean party) {
        if (msg == null || msg.isEmpty()) return "";
        return "<div id=toast data-party=" + (party ? 1 : 0) + " class='toast " + (ok ? "ok" : "err") + "'>" + svg(ok ? I_OK : I_ERR, 20) + "<span>" + esc(msg) + "</span></div>";
    }

    static String card(String name, String cardNo) {
        return "<div class=scene><div class=card><div class=ct><b>Cashpoint</b>" + svg(I_WAVE, 26) + "</div>"
                + "<div class=chiprow><div class=chip></div></div>"
                + "<div class=cnum>" + cardNo + "</div>"
                + "<div class=cb><span>" + esc(name) + "</span><span>ATM Debit</span></div></div></div>";
    }

    static String chips() {
        StringBuilder s = new StringBuilder("<div class=chips>");
        for (int v : new int[]{500, 1000, 2000, 5000})
            s.append("<button type=button onclick=\"this.form.amount.value=").append(v).append("\">\u20B9")
                    .append(String.format(Locale.US, "%,d", v)).append("</button>");
        return s.append("</div>").toString();
    }

    static String act(String id, String label, String icon) {
        return "<button class=act onclick=\"document.getElementById('" + id + "').showModal()\"><i>" + svg(icon, 26) + "</i><span>" + label + "</span></button>";
    }

    static String sheet(String type, String title, String label, String body) {
        return "<dialog id=" + type + " onclick=\"if(event.target===this)this.close()\"><form method=post action=/action>"
                + "<div class=bar><button type=button class=ghost onclick=\"this.closest('dialog').close()\">Cancel</button><b>" + title + "</b><span></span></div>"
                + "<input type=hidden name=type value=" + type + ">" + body + "<button class=pay>" + label + "</button></form></dialog>";
    }

    static String loginPage(String msg, boolean isSuccess) {
        return page(toast(msg, isSuccess, isSuccess)
                + "<div class=welcome-tag>" + svg(I_BOLT, 14) + "Welcome to Cashpoint ATM</div>"
                + "<div class=top><div class=brand><i>" + svg(I_BOLT, 20) + "</i>Cashpoint</div>"
                + "<div class=top-actions><button class=jdbc-pill onclick='openDbInspector()'><i></i>JDBC Connected</button>"
                + "<button class=ghost onclick=\"document.getElementById('settings-dialog').showModal()\">" + svg(I_GEAR, 18) + "</button></div></div>"
                
                + card("Welcome to Cashpoint", "\u2022\u2022\u2022\u2022 \u2022\u2022\u2022\u2022 \u2022\u2022\u2022\u2022 \u2022\u2022\u2022\u2022")
                + "<h1 class=sp>Money, <span class=grad>made simple.</span></h1><p class=sub>Sign in to your ATM account or create a new account.</p>"
                
                + "<div class=tabs><button id=tab-login class='tab act' onclick=\"switchTab('login')\">Sign In</button>"
                + "<button id=tab-reg class='tab' onclick=\"switchTab('reg')\">Open New Account</button></div>"
                
                // Login Form
                + "<form id=form-login method=post action=/login><div class=group>"
                + "<input name=acc placeholder='Account Number or 16-Digit Card Number' inputmode=numeric required autofocus>"
                + "<input name=pin type=password inputmode=numeric placeholder='4-Digit PIN' required></div>"
                + "<button class=pay>Sign In</button></form>"
                
                // Registration Form
                + "<form id=form-reg method=post action=/register style='display:none'><div class=group>"
                + "<input name=name placeholder='Full Name (e.g. Aravind Sharma)' required>"
                + "<input name=pin type=password inputmode=numeric maxlength=4 placeholder='Create 4-Digit PIN' required>"
                + "<input name=deposit type=number step=100 min=0 placeholder='Initial Deposit Amount (\u20B9 optional)'></div>"
                + "<button class=pay>Open ATM Account</button></form>"

                + "<div class=secure>" + svg(I_LOCK, 14) + "JDBC Encrypted SQLite Storage &bull; Voice Command Enabled</div>", false);
    }

    static String dashboard(Account a, String msg, boolean ok) {
        List<TransactionRecord> history = getTransactions(a.no, 15);
        StringBuilder rows = new StringBuilder();
        double in = 0, out = 0;

        for (TransactionRecord r : history) {
            if (r.amount > 0) in += r.amount;
            else out += -r.amount;
        }

        for (int i = 0; i < history.size(); i++) {
            TransactionRecord r = history.get(i);
            double d = r.amount;
            String cls = d > 0 ? "in" : d < 0 ? "out" : "nil";
            rows.append("<div class=row onclick=\"showReceipt('").append(escJsArgument(r.id)).append("','").append(escJsArgument(r.type)).append("','")
                    .append(escJsArgument(money(r.amount))).append("','").append(escJsArgument(money(r.balanceAfter))).append("','")
                    .append(escJsArgument(r.description)).append("','").append(escJsArgument(r.timestamp)).append("','").append(escJsArgument(a.no)).append("')\" style='animation-delay:").append(i * 40).append("ms'>")
                    .append("<i class='ic ").append(cls).append("'>")
                    .append(svg(d > 0 ? I_DOWN : d < 0 ? I_UP : I_LOCK, 18)).append("</i>")
                    .append("<div class=t><b>").append(esc(r.description)).append("</b><small>").append(esc(r.timestamp)).append(" &bull; ").append(r.id).append("</small></div>")
                    .append("<div class='v").append(d > 0 ? " pos" : "").append("'>").append(d > 0 ? "+" : "\u2212").append(money(Math.abs(d)))
                    .append("<small>Receipt &#x2197;</small></div></div>");
        }

        String first = a.name.split(" ")[0];
        boolean party = ok && msg != null && (msg.startsWith("Deposited") || msg.startsWith("Sent") || msg.startsWith("Withdrawn"));
        double dailyWithdrawn = LocalDate.now().toString().equals(a.lastWithdrawDate) ? a.dailyWithdrawn : 0.0;
        double limitPct = Math.min(100, (dailyWithdrawn / 50000.0) * 100);
        AtmVault vault = getAtmVault();

        return page(toast(msg, ok, party)
                + "<div class=top><div class=who><div class=av>" + esc(first.substring(0, 1).toUpperCase()) + "</div>"
                + "<div><small>Account #" + esc(a.no) + "</small><b>" + esc(a.name) + "</b></div></div>"
                + "<div class=top-actions><button class=jdbc-pill onclick='openDbInspector()'><i></i>JDBC Status</button>"
                + "<button class=ghost onclick=\"document.getElementById('settings-dialog').showModal()\">" + svg(I_GEAR, 18) + "</button>"
                + "<form method=post action=/logout style='margin:0'><button class=ghost>Sign Out</button></form></div></div>"
                
                + card(esc(a.name), a.cardNo)
                
                // Terminal Info Card
                + "<div class=terminal-info-card><div><small>ATM Terminal & Network</small><b>ATM-BLR-0402 &bull; Bengaluru Main</b></div>"
                + "<div style='text-align:right'><small>Vault Cash</small><b style='color:var(--pos)'>" + money(vault.totalCash) + "</b></div></div>"

                + "<div class=bal><div><small>Available Balance</small><div class=big id=bigbal data-v='" + String.format(Locale.US, "%.2f", a.balance) + "'>"
                + money(a.balance) + "</div></div>"
                + "<button type=button class=eye id=eye aria-label='Toggle balance'>" + svg(I_EYE, 20) + "</button></div>"
                
                + "<div class=limit-bar><small><span>Daily Withdrawal Quota Used</span><span>" + money(dailyWithdrawn) + " / \u20B950,000</span></small>"
                + "<div class=track><div class=fill style='width:" + limitPct + "%'></div></div></div>"

                + "<div class=stats>"
                + "<div class=stat><small><i class=dot style='background:var(--pos)'></i>Money In</small><b>" + money(in) + "</b></div>"
                + "<div class=stat><small><i class=dot style='background:var(--neg)'></i>Money Out</small><b>" + money(out) + "</b></div></div>"

                // Voice Command Cheat Sheet Panel
                + "<div class=voice-panel><div class=voice-panel-head><span>🎙️ Voice Commands Cheat Sheet</span><small onclick=\"document.getElementById('voice-help-dialog').showModal()\" style='cursor:pointer;color:var(--blue)'>View All &rarr;</small></div>"
                + "<div class=voice-chips>"
                + "<button type=button class=vchip onclick='handleVoiceCommand(\"deposit 2000\")'>🎙️ \"Deposit 2000\"</button>"
                + "<button type=button class=vchip onclick='handleVoiceCommand(\"withdraw 500\")'>🎙️ \"Withdraw 500\"</button>"
                + "<button type=button class=vchip onclick='handleVoiceCommand(\"check balance\")'>🎙️ \"Check Balance\"</button>"
                + "<button type=button class=vchip onclick='handleVoiceCommand(\"send money\")'>🎙️ \"Send Money\"</button>"
                + "<button type=button class=vchip onclick='handleVoiceCommand(\"database\")'>🎙️ \"JDBC DB\"</button>"
                + "</div></div>"

                + "<div class=acts>" + act("deposit", "Deposit", I_DOWN) + act("withdraw", "Withdraw", I_UP)
                + act("transfer", "Send", I_SEND) + act("pin", "PIN", I_LOCK) + "</div>"

                + "<div class=head><h2>ATM Activity & Statements</h2><small>" + history.size() + " shown</small></div><div class=list>"
                + (rows.length() == 0 ? "<div class=empty>" + svg(I_EMPTY, 34) + "No transactions yet.<br>Make a cash deposit to get started.</div>" : rows.toString()) + "</div>"

                + sheet("deposit", "Cash Deposit", "Deposit Cash", AMT + chips())
                + sheet("withdraw", "ATM Cash Withdrawal", "Withdraw Cash", AMT + "<p class=hint>Single limit \u20B920,000 &bull; Multiples of \u20B9100</p>" + chips())
                + sheet("transfer", "Send Money", "Transfer Now",
                        "<div class=group><input name=to placeholder='Recipient Account Number' inputmode=numeric required autofocus oninput='lookupRecipient(this)'>"
                                + "<div id=rec-info class=recipient-info></div></div>" + AMT)
                + sheet("pin", "Change ATM PIN", "Update PIN",
                        "<div class=group><input name=old type=password inputmode=numeric placeholder='Current 4-Digit PIN' required autofocus>"
                                + "<input name=new type=password inputmode=numeric maxlength=4 placeholder='New 4-Digit PIN' required></div>")

                // Receipt Modal Dialog
                + "<dialog id=receipt-dialog onclick=\"if(event.target===this)this.close()\"><div style='padding:20px;text-align:center'>"
                + "<div class=receipt-body>"
                + "<div class=receipt-head><h3>CASHPOINT ATM</h3><small>Terminal: ATM-BLR-0402</small></div>"
                + "<div class=receipt-row><span>Txn Reference:</span><b id=r-id></b></div>"
                + "<div class=receipt-row><span>Date & Time:</span><span id=r-time></span></div>"
                + "<div class=receipt-row><span>Account Number:</span><span id=r-acc></span></div>"
                + "<div class=receipt-row><span>Transaction Type:</span><b id=r-type></b></div>"
                + "<div class=receipt-row><span>Description:</span><span id=r-desc></span></div>"
                + "<div class=receipt-row bold><span>Amount:</span><span id=r-amt></span></div>"
                + "<div class=receipt-row><span>Balance After:</span><span id=r-bal></span></div>"
                + "</div>"
                + "<div style='display:flex;gap:10px;justify-content:center'>"
                + "<button type=button class=ghost onclick=\"window.print()\">Print Receipt</button>"
                + "<button type=button class=ghost onclick=\"document.getElementById('receipt-dialog').close()\">Close</button>"
                + "</div></div></dialog>", false);
    }

    static String adminDashboard(String msg, boolean ok) {
        AtmVault vault = getAtmVault();
        List<Account> accountsList = getAllAccounts();
        List<TransactionRecord> globalTxns = getTransactions(null, 20);

        double totalDeposits = 0;
        int lockedCount = 0;
        for (Account a : accountsList) {
            totalDeposits += a.balance;
            if (a.locked) lockedCount++;
        }

        StringBuilder accRows = new StringBuilder();
        for (Account a : accountsList) {
            accRows.append("<tr>")
                    .append("<td><b>").append(esc(a.no)).append("</b></td>")
                    .append("<td>").append(esc(a.name)).append("</td>")
                    .append("<td><small>").append(a.cardNo).append("</small></td>")
                    .append("<td><b>").append(money(a.balance)).append("</b></td>")
                    .append("<td><span class='badge ").append(a.locked ? "locked" : "active").append("'>").append(a.locked ? "Locked" : "Active").append("</span></td>")
                    .append("<td>");
            if (a.locked) {
                accRows.append("<form method=post action=/action style='margin:0'><input type=hidden name=type value=unlock><input type=hidden name=target value='").append(a.no).append("'><button class=btn-sm>Unlock</button></form>");
            } else {
                accRows.append("&mdash;");
            }
            accRows.append("</td></tr>");
        }

        StringBuilder txnRows = new StringBuilder();
        for (TransactionRecord r : globalTxns) {
            txnRows.append("<tr>")
                    .append("<td><small>").append(r.timestamp).append("</small></td>")
                    .append("<td><b>").append(r.accountNo).append("</b></td>")
                    .append("<td>").append(r.type).append("</td>")
                    .append("<td>").append(money(r.amount)).append("</td>")
                    .append("<td>").append(money(r.balanceAfter)).append("</td>")
                    .append("</tr>");
        }

        return page(toast(msg, ok, false)
                + "<div class=top><div class=who><div class=av style='background:linear-gradient(135deg,#b3263e,#7a1829)'>A</div>"
                + "<div><small>ATM Manager Portal</small><b>System Administrator</b></div></div>"
                + "<div class=top-actions><button class=jdbc-pill onclick='openDbInspector()'><i></i>JDBC Status</button>"
                + "<button class=ghost onclick=\"document.getElementById('settings-dialog').showModal()\">" + svg(I_GEAR, 18) + "</button>"
                + "<form method=post action=/logout style='margin:0'><button class=ghost>Sign Out</button></form></div></div>"

                + "<div class=admin-grid>"
                + "<div class=stat style='grid-column:span 3;background:linear-gradient(135deg,#3a2f14,#120e05);color:#f8edc8'>"
                + "<small style='color:#d9c58c'>ATM Machine Vault Reserve</small>"
                + "<b style='font-size:36px'>" + money(vault.totalCash) + "</b>"
                + "<small style='color:#a89665;margin-top:6px'>Last Refilled: " + vault.lastRefilledAt + "</small>"
                + "<button class=ghost style='margin-top:14px;color:#fff;border-color:rgba(255,255,255,.3)' onclick=\"document.getElementById('refill-dialog').showModal()\">Refill Machine Vault</button>"
                + "</div>"

                + "<div class=stat><small>Total Accounts</small><b>" + accountsList.size() + "</b></div>"
                + "<div class=stat><small>Total User Deposits</small><b>" + money(totalDeposits) + "</b></div>"
                + "<div class=stat><small>Locked Accounts</small><b style='color:" + (lockedCount > 0 ? "var(--neg)" : "inherit") + "'>" + lockedCount + "</b></div>"
                + "</div>"

                + "<div class=head><h2>Registered Customer Accounts</h2></div>"
                + "<div class=tbl-wrap style='margin-bottom:30px'><table><thead><tr><th>Account #</th><th>Name</th><th>Card #</th><th>Balance</th><th>Status</th><th>Action</th></tr></thead><tbody>"
                + (accRows.length() == 0 ? "<tr><td colspan=6 style='text-align:center;padding:24px'>No customer accounts registered yet.</td></tr>" : accRows.toString())
                + "</tbody></table></div>"

                + "<div class=head><h2>System Audit Log</h2></div>"
                + "<div class=tbl-wrap><table><thead><tr><th>Timestamp</th><th>Account</th><th>Type</th><th>Amount</th><th>Balance After</th></tr></thead><tbody>"
                + (txnRows.length() == 0 ? "<tr><td colspan=5 style='text-align:center;padding:24px'>No transactions logged.</td></tr>" : txnRows.toString())
                + "</tbody></table></div>"

                // Refill Dialog Sheet
                + "<dialog id=refill-dialog onclick=\"if(event.target===this)this.close()\"><form method=post action=/action>"
                + "<div class=bar><button type=button class=ghost onclick=\"this.closest('dialog').close()\">Cancel</button><b>Refill ATM Vault</b><span></span></div>"
                + "<input type=hidden name=type value=refill>" + AMT + "<button class=pay>Refill Vault Cash</button></form></dialog>", true);
    }

    static Map<String, String> form(HttpExchange e) throws IOException {
        String body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> m = new HashMap<>();
        for (String p : body.split("&")) {
            if (p.isEmpty()) continue;
            String[] kv = p.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return m;
    }

    static String sid(HttpExchange e) {
        String c = e.getRequestHeaders().getFirst("Cookie");
        if (c == null) return null;
        for (String p : c.split(";")) {
            p = p.trim();
            if (p.startsWith("sid=")) return p.substring(4);
        }
        return null;
    }

    static Account current(HttpExchange e) {
        String id = sid(e);
        if (id == null) return null;
        synchronized (LOCK) {
            String no = sessions.get(id);
            return no == null ? null : getAccount(no);
        }
    }

    static void send(HttpExchange e, String html) throws IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        e.sendResponseHeaders(200, b.length);
        try (OutputStream o = e.getResponseBody()) {
            o.write(b);
        }
    }

    static void sendJson(HttpExchange e, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        e.sendResponseHeaders(200, b.length);
        try (OutputStream o = e.getResponseBody()) {
            o.write(b);
        }
    }

    static void redirect(HttpExchange e, String to) throws IOException {
        e.getResponseHeaders().set("Location", to);
        e.sendResponseHeaders(302, -1);
        e.close();
    }

    static double amount(Map<String, String> f) throws Exception {
        double a;
        try {
            a = Double.parseDouble(f.getOrDefault("amount", ""));
        } catch (NumberFormatException ex) {
            throw new Exception("Invalid amount entered");
        }
        validateMoney(a, false);
        return a;
    }

    public static void main(String[] args) throws Exception {
        // Initialize SQLite Database Tables via JDBC
        initDatabase();

        int port = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 3000;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // Home Endpoint
        server.createContext("/", e -> {
            if (!e.getRequestURI().getPath().equals("/")) {
                e.sendResponseHeaders(404, -1);
                e.close();
                return;
            }
            Account a = current(e);
            if (a == null) {
                send(e, loginPage(null, false));
            } else if (a.isAdmin) {
                send(e, adminDashboard(null, true));
            } else {
                send(e, dashboard(a, null, true));
            }
        });

        // JDBC Database Info Inspector Endpoint
        server.createContext("/db-info", e -> {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                int accCount = 0;
                ResultSet rsAcc = stmt.executeQuery("SELECT COUNT(*) FROM accounts WHERE is_admin = 0");
                if (rsAcc.next()) accCount = rsAcc.getInt(1);

                int txnCount = 0;
                ResultSet rsTxn = stmt.executeQuery("SELECT COUNT(*) FROM transactions");
                if (rsTxn.next()) txnCount = rsTxn.getInt(1);

                double vaultCash = getAtmVault().totalCash;
                String dbPath = new File("atm.db").getAbsolutePath();

                String json = String.format(Locale.US,
                    "{\"connected\":true,\"dbUrl\":\"%s\",\"driver\":\"org.sqlite.JDBC\",\"dbPath\":\"%s\",\"accountsCount\":%d,\"transactionsCount\":%d,\"vaultCash\":%.2f,\"status\":\"ACTIVE / CONNECTED (WAL Mode)\"}",
                    DB_URL, escJson(dbPath), accCount, txnCount, vaultCash);
                sendJson(e, json);
            } catch (Exception ex) {
                sendJson(e, "{\"connected\":false,\"error\":\"" + escJson(ex.getMessage()) + "\"}");
            }
        });

        // Login Endpoint
        server.createContext("/login", e -> {
            if (!e.getRequestMethod().equals("POST")) {
                redirect(e, "/");
                return;
            }
            Map<String, String> f = form(e);
            String inputAcc = f.getOrDefault("acc", "").trim();
            String inputPin = f.getOrDefault("pin", "").trim();
            String msg;

            synchronized (LOCK) {
                Account a = getAccount(inputAcc);
                if (a == null) {
                    msg = "Account number or Card number not found";
                } else if (a.locked) {
                    msg = "Account locked due to 3 wrong PIN attempts. Contact Admin.";
                } else if (!a.pin.equals(inputPin)) {
                    recordFailedAttempt(a.no);
                    int remaining = 3 - (a.fails + 1);
                    msg = remaining <= 0 ? "Account locked due to 3 wrong attempts" : "Incorrect PIN. Attempts remaining: " + remaining;
                } else {
                    resetFailedAttempts(a.no);
                    String id = UUID.randomUUID().toString();
                    sessions.put(id, a.no);
                    e.getResponseHeaders().add("Set-Cookie", "sid=" + id + "; Path=/; HttpOnly; SameSite=Lax");
                    redirect(e, "/");
                    return;
                }
            }
            send(e, loginPage(msg, false));
        });

        // Register New Account Endpoint
        server.createContext("/register", e -> {
            if (!e.getRequestMethod().equals("POST")) {
                redirect(e, "/");
                return;
            }
            Map<String, String> f = form(e);
            String name = f.getOrDefault("name", "").trim();
            String pin = f.getOrDefault("pin", "").trim();
            try {
                String depStr = f.getOrDefault("deposit", "").trim();
                double dep = depStr.isEmpty() ? 0.0 : Double.parseDouble(depStr);
                Account newAcc = registerAccount(name, pin, dep);
                String id = UUID.randomUUID().toString();
                sessions.put(id, newAcc.no);
                e.getResponseHeaders().add("Set-Cookie", "sid=" + id + "; Path=/; HttpOnly; SameSite=Lax");
                redirect(e, "/");
            } catch (Exception ex) {
                send(e, loginPage("Registration failed: " + ex.getMessage(), false));
            }
        });

        // Lookup Recipient Endpoint (AJAX)
        server.createContext("/lookup", e -> {
            Account requester = current(e);
            if (!"GET".equals(e.getRequestMethod()) || requester == null || requester.isAdmin) {
                sendJson(e, "{\"found\":false}");
                return;
            }
            String query = e.getRequestURI().getQuery();
            String accNo = "";
            if (query != null && query.contains("acc=")) {
                for (String p : query.split("&")) {
                    if (p.startsWith("acc=")) accNo = URLDecoder.decode(p.substring(4), StandardCharsets.UTF_8);
                }
            }
            Account target = getAccount(accNo.trim());
            if (target != null && !target.isAdmin) {
                sendJson(e, "{\"found\":true,\"no\":\"" + escJson(target.no) + "\",\"name\":\"" + escJson(target.name) + "\"}");
            } else {
                sendJson(e, "{\"found\":false}");
            }
        });

        // Actions Endpoint (Deposit, Withdraw, Transfer, PIN Change, Admin Refill/Unlock)
        server.createContext("/action", e -> {
            Account a = current(e);
            if (a == null || !e.getRequestMethod().equals("POST")) {
                redirect(e, "/");
                return;
            }
            if (a.locked && !a.isAdmin) {
                send(e, dashboard(a, "Account is locked. Contact Admin.", false));
                return;
            }
            Map<String, String> f = form(e);
            String msg = "";
            boolean ok = true;

            synchronized (LOCK) {
                try {
                    switch (f.getOrDefault("type", "")) {
                        case "deposit" -> {
                            double v = amount(f);
                            msg = depositCash(a, v);
                        }
                        case "withdraw" -> {
                            double v = amount(f);
                            msg = withdrawCash(a, v);
                        }
                        case "transfer" -> {
                            double v = amount(f);
                            String to = f.getOrDefault("to", "");
                            msg = transferMoney(a, to, v);
                        }
                        case "pin" -> {
                            String oldP = f.getOrDefault("old", "");
                            String newP = f.getOrDefault("new", "");
                            changePin(a, oldP, newP);
                            msg = "ATM PIN successfully updated";
                        }
                        case "refill" -> {
                            if (!a.isAdmin) throw new Exception("Unauthorized");
                            double v = amount(f);
                            refillAtmVault(v);
                            msg = "ATM Machine Vault successfully refilled with " + money(v);
                        }
                        case "unlock" -> {
                            if (!a.isAdmin) throw new Exception("Unauthorized");
                            String targetAcc = f.getOrDefault("target", "");
                            unlockAccount(targetAcc);
                            msg = "Account #" + targetAcc + " has been unlocked.";
                        }
                        default -> throw new Exception("Unknown transaction request");
                    }
                } catch (Exception ex) {
                    msg = ex.getMessage();
                    ok = false;
                }

                Account updated = getAccount(a.no);
                if (updated != null && updated.isAdmin) {
                    send(e, adminDashboard(msg, ok));
                } else if (updated != null) {
                    send(e, dashboard(updated, msg, ok));
                } else {
                    redirect(e, "/");
                }
            }
        });

        // Logout Endpoint
        server.createContext("/logout", e -> {
            String id = sid(e);
            if (id != null) synchronized (LOCK) {
                sessions.remove(id);
            }
            redirect(e, "/");
        });

        server.start();
        System.out.println("=================================================");
        System.out.println(" Cashpoint ATM Management System Running!");
        System.out.println(" Database: SQLite (" + DB_URL + ")");
        System.out.println(" Web Portal: http://localhost:" + port);
        System.out.println(" Admin Login: Acc 'admin' / PIN '9999'");
        System.out.println("=================================================");
    }
}
