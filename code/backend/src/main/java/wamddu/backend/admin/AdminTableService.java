package wamddu.backend.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import wamddu.backend.event.service.EventListCache;
import wamddu.backend.global.exception.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.sql.Types;
import org.springframework.dao.DataIntegrityViolationException;

@Service
@RequiredArgsConstructor
public class AdminTableService {
    // Only application tables may be used as SQL identifiers.
    private static final List<String> TABLES = List.of(
            "users", "events", "event_directors", "tickets", "ticket_price_history", "orders", "payments");
    private final JdbcTemplate jdbc;
    private final EventListCache eventListCache;

    public List<String> tables() {
        return jdbc.execute((ConnectionCallback<List<String>>) connection -> {
            var existing = new java.util.HashSet<String>();
            try (var tables = connection.getMetaData().getTables(
                    connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    existing.add(tables.getString("TABLE_NAME").toLowerCase(java.util.Locale.ROOT));
                }
            }
            return TABLES.stream().filter(existing::contains).toList();
        });
    }

    public record CreateField(String name, String type, boolean required) {}
    public record TablePage(String table, List<String> columns, List<List<String>> rows,
                            long totalRows, int page, int size, List<CreateField> createFields) {
        public TablePage(String table, List<String> columns, List<List<String>> rows, long totalRows, int page, int size) {
            this(table, columns, rows, totalRows, page, size, List.of());
        }

        /** Copy for a demo administrator: members' contact details and names are masked, the rest is unchanged. */
        public TablePage maskPersonalData() {
            if (!table.equals("users")) {
                return this;
            }
            List<List<String>> masked = rows.stream().map(row -> {
                var values = new ArrayList<>(row);
                for (int i = 0; i < columns.size(); i++) {
                    if (values.get(i) != null) {
                        values.set(i, mask(columns.get(i).toLowerCase(java.util.Locale.ROOT), values.get(i)));
                    }
                }
                return (List<String>) values;
            }).toList();
            return new TablePage(table, columns, masked, totalRows, page, size, createFields);
        }

        private static String mask(String column, String value) {
            return switch (column) {
                case "email" -> {
                    int at = value.indexOf('@');
                    yield at < 0 ? "***" : value.substring(0, Math.min(2, at)) + "***" + value.substring(at);
                }
                case "phonenumber" -> value.length() <= 4 ? "***"
                        : "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
                case "username" -> value.isEmpty() ? value
                        : value.substring(0, value.offsetByCodePoints(0, 1)) + "**";
                case "customer_key" -> "***";
                default -> value;
            };
        }
    }

    private List<CreateField> createFields(String table) {
        if (table.equals("orders") || table.equals("payments")) return List.of();
        return jdbc.execute((ConnectionCallback<List<CreateField>>) connection -> {
            var fields = new ArrayList<CreateField>();
            try (var rs = connection.getMetaData().getColumns(connection.getCatalog(), connection.getSchema(), table, "%")) {
                while (rs.next()) {
                    if (!table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) continue;
                    String name = rs.getString("COLUMN_NAME");
                    if (name.equalsIgnoreCase("id") || "YES".equals(rs.getString("IS_AUTOINCREMENT"))) continue;
                    if (table.equals("users") && (name.equalsIgnoreCase("customer_key") || name.equalsIgnoreCase("email_verified_at"))) continue;
                    if (table.equals("tickets") && name.equalsIgnoreCase("sold_ticket")) continue;
                    String type = switch (rs.getInt("DATA_TYPE")) {
                        case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.DECIMAL, Types.FLOAT, Types.REAL, Types.DOUBLE -> "number";
                        case Types.DATE -> "date";
                        case Types.TIMESTAMP -> "datetime-local";
                        case Types.TIME -> "time";
                        default -> "text";
                    };
                    boolean required = rs.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNoNulls && rs.getString("COLUMN_DEF") == null;
                    if (table.equals("users") && name.equals("password")) type = "password";
                    fields.add(new CreateField(name, type, required));
                }
            }
            return fields;
        });
    }

    private static void validateUserChoices(String table, Map<String, String> values) {
        if (!table.equals("users")) return;
        if (values.containsKey("role") && !"ADMIN".equals(values.get("role")) && !"USER".equals(values.get("role"))) {
            throw invalidUpdate("role은 ADMIN 또는 USER를 선택해 주세요.");
        }
        if (values.containsKey("status") && !"ACTIVE".equals(values.get("status")) && !"INACTIVE".equals(values.get("status"))) {
            throw invalidUpdate("status는 ACTIVE 또는 INACTIVE를 선택해 주세요.");
        }
    }

    public record RowChange(String id, Map<String, String> originalValues, Map<String, String> values) {}
    public record RowDeletion(String id, Map<String, String> originalValues) {}

    private static void requireWritableTable(String table) {
        if (table.equals("orders") || table.equals("payments")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_TABLE_READ_ONLY",
                    "주문·결제는 조회만 가능합니다. 결제 불일치 탭의 전용 처리 버튼을 사용해 주세요.");
        }
    }

    private static void requireWritableField(String table, String column) {
        if (table.equals("users") && "email_verified_at".equalsIgnoreCase(column)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_FIELD_READ_ONLY", "이메일 인증 시각은 인증 절차로만 변경할 수 있습니다.");
        }
        if (table.equals("tickets") && "sold_ticket".equalsIgnoreCase(column)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_FIELD_READ_ONLY",
                    "티켓 판매 수량은 결제 처리로만 변경할 수 있습니다.");
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TablePage save(String table, List<RowChange> changes, List<Map<String, String>> creations,
                          List<RowDeletion> deletions, int page, int size) {
        var snapshot = read(table, page, size);
        requireWritableTable(table);
        changes = changes == null ? List.of() : changes;
        creations = creations == null ? List.of() : creations;
        deletions = deletions == null ? List.of() : deletions;
        int count = changes.size() + creations.size() + deletions.size();
        if (count < 1 || count > 200 || creations.size() > 100 || deletions.size() > 100) {
            throw invalidUpdate("추가·수정·삭제할 행 개수가 올바르지 않습니다.");
        }
        var ids = new java.util.HashSet<String>();
        for (var change : changes) {
            if (change == null || !ids.add(change.id())) throw invalidUpdate("중복된 행입니다.");
        }
        try {
            for (var deletion : deletions) {
                if (deletion == null || deletion.id() == null || !ids.add(deletion.id())
                        || deletion.originalValues() == null
                        || !deletion.originalValues().keySet().equals(new java.util.HashSet<>(snapshot.columns()))) {
                    throw invalidUpdate("삭제할 행의 기존 값이 필요합니다.");
                }
                long id = Long.parseLong(deletion.id());
                var current = jdbc.query("SELECT " + snapshot.columns().stream().map(AdminTableService::quote)
                                .collect(java.util.stream.Collectors.joining(", ")) + " FROM " + quote(table) + " WHERE id = ? FOR UPDATE",
                        (rs, rowNum) -> {
                            var values = new java.util.HashMap<String, String>();
                            for (int i = 0; i < snapshot.columns().size(); i++) values.put(snapshot.columns().get(i), rs.getString(i + 1));
                            return values;
                        }, id);
                if (current.isEmpty() || !current.getFirst().equals(deletion.originalValues())) {
                    throw new ApiException(HttpStatus.CONFLICT, "ROW_CHANGED", "삭제할 행이 변경되었습니다. 변경 취소 후 새로고침해 주세요.");
                }
                if (table.equals("tickets")) {
                    String sold = current.getFirst().get("sold_ticket");
                    // READ_COMMITTED sees orders committed while waiting for the ticket lock.
                    // Order creation holds that same ticket lock; no order lock is needed here.
                    boolean linked = !jdbc.queryForList("SELECT id FROM orders WHERE ticket_id = ? LIMIT 1", Long.class, id).isEmpty();
                    if ((sold != null && Long.parseLong(sold) > 0) || linked) {
                        throw new ApiException(HttpStatus.CONFLICT, "TICKET_HAS_ORDERS",
                                "주문이 연결되었거나 판매된 티켓은 삭제할 수 없습니다.");
                    }
                }
                jdbc.update("DELETE FROM " + quote(table) + " WHERE id = ?", id);
            }
            if (!changes.isEmpty()) update(table, changes, page, size);
            Map<String, Integer> types = jdbc.query("SELECT * FROM " + quote(table) + " WHERE 1 = 0",
                    (ResultSetExtractor<Map<String, Integer>>) rs -> {
                        var result = new java.util.HashMap<String, Integer>();
                        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                            result.put(rs.getMetaData().getColumnName(i), rs.getMetaData().getColumnType(i));
                        }
                        return result;
                    });
            for (var creation : creations) {
                if (creation == null || creation.isEmpty()) throw invalidUpdate("추가할 행의 값을 입력해 주세요.");
                var values = new java.util.LinkedHashMap<>(creation);
                validateUserChoices(table, values);
                for (String column : values.keySet()) {
                    requireWritableField(table, column);
                    if (column == null || column.equalsIgnoreCase("id")
                            || (!snapshot.columns().contains(column) && !(table.equals("users") && column.equals("password")))) {
                        throw invalidUpdate("추가할 수 없는 열입니다.");
                    }
                }
                if (table.equals("tickets")) values.put("sold_ticket", "0");
                if (table.equals("users")) {
                    String password = values.get("password");
                    if (password == null || password.isBlank()) throw invalidUpdate("초기 비밀번호를 입력해 주세요.");
                    values.put("password", org.springframework.security.crypto.factory.PasswordEncoderFactories
                            .createDelegatingPasswordEncoder().encode(password));
                    values.putIfAbsent("role", "USER");
                    values.putIfAbsent("status", "ACTIVE");
                    values.put("customer_key", wamddu.backend.user.service.UserService.generateCustomerKey());
                }
                var columns = new ArrayList<>(values.keySet());
                var parameters = columns.stream().map(column -> typedValue(values.get(column), types.get(column))).toArray();
                jdbc.update("INSERT INTO " + quote(table) + " (" + columns.stream().map(AdminTableService::quote)
                        .collect(java.util.stream.Collectors.joining(", ")) + ") VALUES ("
                        + String.join(", ", java.util.Collections.nCopies(columns.size(), "?")) + ")", parameters);
            }
            long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + quote(table), Long.class);
            int lastPage = (int) Math.max(0, (total - 1) / size);
            var result = read(table, creations.isEmpty() ? Math.min(page, lastPage) : lastPage, size);
            if (table.equals("events")) eventListCache.invalidateAfterCommit();
            return result;
        } catch (DataIntegrityViolationException ex) {
            throw invalidUpdate("저장할 수 없습니다. 필수 값·중복 값·참조 ID를 확인해 주세요. 다른 데이터가 참조하는 행은 삭제할 수 없습니다.");
        } catch (IllegalArgumentException | java.time.DateTimeException ex) {
            throw invalidUpdate("숫자 또는 날짜 형식이 올바르지 않습니다.");
        }
    }

    @Transactional
    public TablePage update(String table, List<RowChange> changes, int page, int size) {
        // Validate table and pagination before performing any writes.
        var snapshot = read(table, page, size);
        requireWritableTable(table);
        if (changes == null || changes.isEmpty() || changes.size() > 100) {
            throw invalidUpdate("수정할 행은 1~100개여야 합니다.");
        }
        Map<String, Integer> types = jdbc.query("SELECT * FROM `" + table + "` WHERE 1 = 0",
                (ResultSetExtractor<Map<String, Integer>>) rs -> {
                    var result = new java.util.HashMap<String, Integer>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        result.put(rs.getMetaData().getColumnName(i), rs.getMetaData().getColumnType(i));
                    }
                    return result;
                });
        var ids = new java.util.HashSet<Long>();
        try {
            for (RowChange change : changes) {
                if (change == null || change.id() == null || change.values() == null
                        || change.values().isEmpty() || change.originalValues() == null
                        || !change.values().keySet().equals(change.originalValues().keySet())) {
                    throw invalidUpdate("수정할 값과 기존 값이 필요합니다.");
                }
                long id = Long.parseLong(change.id());
                validateUserChoices(table, change.values());
                if (id < 1 || !ids.add(id)) throw invalidUpdate("행 ID가 잘못되었거나 중복되었습니다.");
                var columns = new ArrayList<>(change.values().keySet());
                for (String column : columns) {
                    requireWritableField(table, column);
                    if (column == null || column.equalsIgnoreCase("id") || !snapshot.columns().contains(column)
                            || (table.equals("users") && column.equalsIgnoreCase("customer_key"))) {
                        throw invalidUpdate("수정할 수 없는 열입니다.");
                    }
                }
                String selection = columns.stream().map(AdminTableService::quote)
                        .collect(java.util.stream.Collectors.joining(", "));
                var currentRows = jdbc.query("SELECT " + selection + " FROM " + quote(table) + " WHERE id = ? FOR UPDATE",
                        (rs, rowNum) -> {
                            var values = new ArrayList<String>();
                            for (int i = 1; i <= columns.size(); i++) values.add(rs.getString(i));
                            return values;
                        }, id);
                if (currentRows.isEmpty()) {
                    throw new ApiException(HttpStatus.CONFLICT, "ROW_CHANGED", "행이 삭제되었습니다. 새로고침 후 다시 시도해 주세요.");
                }
                for (int i = 0; i < columns.size(); i++) {
                    if (!Objects.equals(currentRows.getFirst().get(i), change.originalValues().get(columns.get(i)))) {
                        throw new ApiException(HttpStatus.CONFLICT, "ROW_CHANGED", "다른 요청에서 값이 변경되었습니다. 변경 취소 후 새로고침해 주세요.");
                    }
                }
                var parameters = new ArrayList<Object>();
                for (String column : columns) {
                    parameters.add(typedValue(change.values().get(column), types.get(column)));
                }
                parameters.add(id);
                String assignments = columns.stream().map(column -> quote(column) + " = ?")
                        .collect(java.util.stream.Collectors.joining(", "));
                if (table.equals("users") && columns.stream().anyMatch("email"::equalsIgnoreCase)) {
                    assignments += ", email_verified_at = NULL";
                }
                jdbc.update("UPDATE " + quote(table) + " SET " + assignments + " WHERE id = ?", parameters.toArray());
            }
            var result = read(table, page, size);
            if (table.equals("events")) eventListCache.invalidateAfterCommit();
            return result;
        } catch (DataIntegrityViolationException ex) {
            throw invalidUpdate("저장할 수 없습니다. 필수 값, 중복 값, 참조 ID와 데이터 형식을 확인해 주세요.");
        } catch (IllegalArgumentException | java.time.DateTimeException ex) {
            throw invalidUpdate("숫자 또는 날짜 형식이 올바르지 않습니다.");
        }
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private static ApiException invalidUpdate(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_UPDATE", message);
    }

    private static Object typedValue(String value, int type) {
        if (value == null) return null;
        return switch (type) {
            case Types.TINYINT -> Byte.valueOf(value);
            case Types.SMALLINT -> Short.valueOf(value);
            case Types.INTEGER -> Integer.valueOf(value);
            case Types.BIGINT -> Long.valueOf(value);
            case Types.NUMERIC, Types.DECIMAL, Types.FLOAT, Types.REAL, Types.DOUBLE -> new java.math.BigDecimal(value);
            case Types.DATE -> java.sql.Date.valueOf(java.time.LocalDate.parse(value));
            case Types.TIMESTAMP -> java.sql.Timestamp.valueOf(java.time.LocalDateTime.parse(value.replace(' ', 'T')));
            case Types.TIME -> java.sql.Time.valueOf(java.time.LocalTime.parse(value));
            default -> value;
        };
    }

    @Transactional(readOnly = true)
    public TablePage read(String table, int page, int size) {
        if (!TABLES.contains(table) || !tables().contains(table)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TABLE_NOT_FOUND", "조회할 수 없는 테이블입니다.");
        }
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "페이지는 0 이상, 조회 개수는 1~100이어야 합니다.");
        }
        String tableSql = "`" + table + "`";
        List<String> columns = jdbc.query("SELECT * FROM " + tableSql + " WHERE 1 = 0",
                (ResultSetExtractor<List<String>>) rs -> {
                    var names = new ArrayList<String>();
                    var metadata = rs.getMetaData();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        String name = metadata.getColumnName(i);
                        if (!name.equalsIgnoreCase("password")) {
                            names.add(name);
                        }
                    }
                    names.sort(java.util.Comparator.comparingInt(name -> name.equalsIgnoreCase("id") ? 0 : 1));
                    return names;
                });
        String selection = columns.stream()
                .map(name -> "`" + name.replace("`", "``") + "`")
                .collect(java.util.stream.Collectors.joining(", "));
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + tableSql, Long.class);
        List<List<String>> rows = jdbc.query(
                "SELECT " + selection + " FROM " + tableSql + " ORDER BY id LIMIT ? OFFSET ?",
                (rs, rowNum) -> {
                    var values = new ArrayList<String>();
                    for (int i = 1; i <= columns.size(); i++) {
                        // Strings preserve BIGINT/DECIMAL precision and database date formatting.
                        values.add(rs.getString(i));
                    }
                    return values;
                }, size, (long) page * size);
        return new TablePage(table, columns, rows, total, page, size, createFields(table));
    }
}
