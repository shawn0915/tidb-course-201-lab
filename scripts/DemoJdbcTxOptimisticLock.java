import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class DemoJdbcTxOptimisticLock {

    public static String[] connectionTags = new String[] { "Connection A", "Connection B" };
    public static List<Connection> connections = new ArrayList<Connection>();
    public static BigDecimal id = null;

    public static void printResultSetStringString(String stmtText, Connection connection) {
        int count = 0;
        System.out.println("\n/* Executing query: " + stmtText + "; */");
        try {
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(stmtText);
            System.out.println("\tRow#, " + resultSet.getMetaData().getColumnName(1) + ", "
                    + resultSet.getMetaData().getColumnName(2));
            while (resultSet.next()) {
                System.out.println("\t" + (++count) + ") " + resultSet.getString(1) + ", " + resultSet.getString(2));
            }
            resultSet.close();
            statement.close();
            System.out.println();
        } catch (SQLException e) {
            System.out.println("Error: " + e);
        }
    }

    static class RowUpdater implements Runnable {

        private int connectionNo;
        private BigDecimal rowid;
        private boolean retryCommit;
        private int wait;
        private int waitBefore1stCommit;
        private int waitBefore2ndCommit;

        public RowUpdater(int connectionNo, BigDecimal rowid, boolean retryCommit, int wait, int waitBefore1stCommit,
                int waitBefore2ndCommit) {
            this.connectionNo = connectionNo;
            this.rowid = rowid;
            this.retryCommit = retryCommit;
            this.wait = wait;
            this.waitBefore1stCommit = waitBefore1stCommit;
            this.waitBefore2ndCommit = waitBefore2ndCommit;
        }

        @Override
        public void run() {
            System.out.println(connectionTags[this.connectionNo] + " session started");
            Connection c = connections.get(this.connectionNo);
            try {
                Statement s = c.createStatement();
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e2) {
                    e2.printStackTrace();
                }
                System.out.println(connectionTags[this.connectionNo] + " session: " + "BEGIN OPTIMISTIC");
                s.executeUpdate("BEGIN OPTIMISTIC");
                System.out.println(connectionTags[this.connectionNo] + " session: "
                        + "UPDATE test_tx_optimistic SET name = '" + connectionTags[this.connectionNo]
                        + "' WHERE id = " + rowid);
                s.executeUpdate("UPDATE test_tx_optimistic SET name = '" + connectionTags[this.connectionNo]
                        + "' WHERE id = " + rowid);
                try {
                    Thread.sleep(this.waitBefore1stCommit);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                System.out.println(connectionTags[this.connectionNo] + " session: " + "Commit");
                c.commit();

            } catch (SQLException e) {
                System.out.println(connectionTags[this.connectionNo] + " ErrorCode: " + e.getErrorCode());
                System.out.println(connectionTags[this.connectionNo] + " SQLState: " + e.getSQLState());
                System.out.println(connectionTags[this.connectionNo] + " Error: " + e);
                if (e.getErrorCode() == 9007) {
                    System.out.println("< Session in " + connectionTags[this.connectionNo]
                            + " raised the exception !!!".toUpperCase() + " >");
                    if (this.retryCommit) {
                        try {
                            Thread.sleep(this.waitBefore2ndCommit);
                        } catch (InterruptedException ie) {
                            ie.printStackTrace();
                        }
                        try {
                            System.out.println(connectionTags[this.connectionNo] + " session: " + "Commit");
                            Statement s = c.createStatement();
                            s.executeUpdate("UPDATE test_tx_optimistic SET name = '" + connectionTags[this.connectionNo]
                                    + "' WHERE id = " + rowid);
                            c.commit();
                        } catch (SQLException e1) {
                            e1.printStackTrace();
                        }
                    }
                }
            } finally {
                System.out.println(connectionTags[this.connectionNo] + " session: " + "Checking result");
                printResultSetStringString("select id, name from test_tx_optimistic", c);
            }
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Please run this demo by providing two arguments: cloud|local no-retry|retry");
            return;
        }

        String target = args[0];
        String tidbCloudHost = System.getenv().get("TIDB_CLOUD_HOST");
        String tidbOpHost = System.getenv().get("TIDB_HOST") == null ? "127.0.0.1" : System.getenv().get("TIDB_HOST");
        String dbCloudUsername = System.getenv().get("TIDB_CLOUD_USERNAME") == null ? "root"
                : System.getenv().get("TIDB_CLOUD_USERNAME");
        String dbOpUsername = System.getenv().get("TIDB_USERNAME") == null ? "root"
                : System.getenv().get("TIDB_USERNAME");
        String dbCloudPassword = System.getenv().get("TIDB_CLOUD_PASSWORD") == null ? ""
                : System.getenv().get("TIDB_CLOUD_PASSWORD");
        String dbOpPassword = System.getenv().get("TIDB_PASSWORD") == null ? ""
                : System.getenv().get("TIDB_PASSWORD");
        String dbCloudPort = System.getenv().get("TIDB_CLOUD_PORT") == null ? "4000"
                : System.getenv().get("TIDB_CLOUD_PORT");
        String dbOpPort = System.getenv().get("TIDB_PORT") == null ? "4000"
                : System.getenv().get("TIDB_PORT");
        String tidbHost = null;
        String dbUsername = null;
        String dbPassword = null;
        String port = null;
        String securityOption = null;
        if (target.equalsIgnoreCase("cloud")) {
            tidbHost = tidbCloudHost;
            dbUsername = dbCloudUsername;
            dbPassword = dbCloudPassword;
            port = dbCloudPort;
            securityOption = "&sslMode=VERIFY_IDENTITY&enabledTLSProtocols=TLSv1.3";
        } else {
            tidbHost = tidbOpHost;
            dbUsername = dbOpUsername;
            dbPassword = dbOpPassword;
            port = dbOpPort;
            securityOption = "";
        }
        System.out.println("TiDB endpoint: " + tidbHost);
        System.out.println("TiDB username: " + dbUsername);
        System.out.println("Default TiDB server port: " + port);
        System.out.println("Security options: " + securityOption);

        boolean retryCommit = args[1].equalsIgnoreCase("retry") ? true : false;
        try {
            for (int i = 0; i < 2; i++) {
                connections.add(DriverManager.getConnection(
                        "jdbc:mysql://" + tidbHost
                                + ":" + port
                                + "/test?"
                                + securityOption,
                        dbUsername, dbPassword));
            }
            System.out.println("Connection established.");
            Statement s = connections.get(0).createStatement();
            s.executeUpdate("DROP TABLE IF EXISTS test_tx_optimistic");
            s.executeUpdate("CREATE TABLE test_tx_optimistic (id BIGINT PRIMARY KEY AUTO_RANDOM, name char(20))");
            s.executeUpdate("INSERT INTO test_tx_optimistic (name) VALUES ('INIT') ", Statement.RETURN_GENERATED_KEYS);
            ResultSet rs = s.getGeneratedKeys();
            rs.first();
            id = rs.getBigDecimal(1);
            rs.close();
            s.close();

            for (Connection c : connections) {
                c.setAutoCommit(false);
            }

            new Thread(new DemoJdbcTxOptimisticLock.RowUpdater(0, id, retryCommit, 1, 6000, 1000)).start();
            new Thread(new DemoJdbcTxOptimisticLock.RowUpdater(1, id, retryCommit, 1000, 2000, 9000)).start();

        } catch (SQLException e) {
            e.printStackTrace();
            System.out.println("Main Block ErrorCode: " + e.getErrorCode());
            System.out.println("Main Block SQLState: " + e.getSQLState());
            System.out.println("Main Block Error: " + e);
        }
    }
}
