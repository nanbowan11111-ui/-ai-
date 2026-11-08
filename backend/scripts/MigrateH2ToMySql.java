import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

/** One-time transfer of the existing H2 ledger into an empty MySQL database. */
public class MigrateH2ToMySql {
    private record Table(String name, String columns, String orderBy) {}

    // 外键要求先复制主表，再复制明细；固定列名也避免把未来新增列静默遗漏。
    private static final List<Table> TABLES = List.of(
        new Table("material", "code,name,specification,unit,safe_stock,category,stock", "code"),
        new Table("receipt", "number,received_on,supplier,department,operator,created_at", "number"),
        new Table("issue_request", "id,applicant,status,reject_reason,reviewer,created_at,reviewed_at", "id"),
        new Table("issue_item", "request_id,material_code,quantity", "request_id,material_code"),
        new Table("movement", "id,occurred_at,kind,document_number,material_code,quantity,balance,operator", "id")
    );

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("无需命令行参数；请通过环境变量配置连接");
        String password = System.getenv("DB_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("DB_PASSWORD 未设置");
        String sourceUrl = env("MIGRATE_SOURCE_URL", "jdbc:h2:file:./data/office;IFEXISTS=TRUE");
        String targetUrl = env("DB_URL", "jdbc:mysql://127.0.0.1:3306/office_ledger?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        String targetUser = env("DB_USERNAME", "office_app");

        try (Connection source = DriverManager.getConnection(sourceUrl, "sa", "");
             Connection target = DriverManager.getConnection(targetUrl, targetUser, password)) {
            if (!"MySQL".equalsIgnoreCase(target.getMetaData().getDatabaseProductName()))
                throw new IllegalStateException("目标连接不是 MySQL");
            try (Statement statement = target.createStatement()) {
                statement.execute("SET time_zone = '+08:00'");
            }
            createSchema(target);
            for (Table table : TABLES) {
                if (count(target, table.name()) != 0)
                    throw new IllegalStateException("目标表 " + table.name() + " 已有数据，迁移已取消");
            }
            // MySQL 默认 TIMESTAMP(0) 会四舍五入，先确保目标可保存 H2 的微秒。
            ensureTimestampPrecision(target);

            target.setAutoCommit(false);
            try {
                for (Table table : TABLES) {
                    int copied = copy(source, target, table);
                    System.out.println(table.name() + ": " + copied + " rows");
                }
                // 逐字段核对所有行，发现差异时整批回滚，不留下半迁移数据。
                for (Table table : TABLES) verify(source, target, table);
                target.commit();
            } catch (Exception error) {
                target.rollback();
                throw error;
            }
            System.out.println("迁移完成：5 张表逐行校验通过");
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void createSchema(Connection target) throws Exception {
        String schema = Files.readString(Path.of("backend/src/main/resources/schema.sql"));
        try (Statement statement = target.createStatement()) {
            for (String command : schema.split(";")) {
                if (!command.isBlank()) statement.execute(command.trim());
            }
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void ensureTimestampPrecision(Connection target) throws SQLException {
        try (Statement statement = target.createStatement()) {
            statement.execute("ALTER TABLE receipt MODIFY created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6)");
            statement.execute("ALTER TABLE issue_request MODIFY created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6)");
            statement.execute("ALTER TABLE issue_request MODIFY reviewed_at TIMESTAMP(6)");
            statement.execute("ALTER TABLE movement MODIFY occurred_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6)");
        }
    }

    private static int copy(Connection source, Connection target, Table table) throws SQLException {
        String select = "SELECT " + table.columns() + " FROM " + table.name() + " ORDER BY " + table.orderBy();
        int columnCount = table.columns().split(",").length;
        String placeholders = String.join(",", java.util.Collections.nCopies(columnCount, "?"));
        String insert = "INSERT INTO " + table.name() + " (" + table.columns() + ") VALUES (" + placeholders + ")";
        int copied = 0;
        try (Statement read = source.createStatement(); ResultSet rows = read.executeQuery(select);
             PreparedStatement write = target.prepareStatement(insert)) {
            while (rows.next()) {
                for (int column = 1; column <= columnCount; column++) write.setObject(column, rows.getObject(column));
                write.executeUpdate();
                copied++;
            }
        }
        return copied;
    }

    private static void verify(Connection source, Connection target, Table table) throws SQLException {
        String query = "SELECT " + table.columns() + " FROM " + table.name() + " ORDER BY " + table.orderBy();
        try (Statement sourceStatement = source.createStatement(); Statement targetStatement = target.createStatement();
             ResultSet before = sourceStatement.executeQuery(query); ResultSet after = targetStatement.executeQuery(query)) {
            ResultSetMetaData metadata = before.getMetaData();
            int row = 0;
            while (before.next()) {
                row++;
                if (!after.next()) throw new IllegalStateException(table.name() + " 少于源表记录数");
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    Object original = normalized(before.getObject(column));
                    Object migrated = normalized(after.getObject(column));
                    if (!Objects.equals(original, migrated))
                        throw new IllegalStateException(table.name() + " 第 " + row + " 行 " + metadata.getColumnName(column) + " 不一致：源=" + original + "，目标=" + migrated);
                }
            }
            if (after.next()) throw new IllegalStateException(table.name() + " 多于源表记录数");
        }
    }

    private static Object normalized(Object value) {
        if (value instanceof Timestamp time) return time.toLocalDateTime();
        if (value instanceof Number number) return new BigDecimal(number.toString()).stripTrailingZeros();
        return value;
    }
}
