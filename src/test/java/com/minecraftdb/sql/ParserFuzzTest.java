package com.minecraftdb.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 解析器/词法器模糊测试：随机与畸形的输入只允许两种结局——
 * 干净地解析成功，或抛出 ParseException。任何其他 RuntimeException
 * （NPE、数组越界、栈溢出）都是缺陷。随机流固定种子保证可复现。
 */
class ParserFuzzTest {

    private com.minecraftdb.sql.ast.Statement parse(String sql) {
        return new SqlParser(new SqlLexer(sql).tokenize()).parse();
    }

    @Test
    @DisplayName("随机字符流 5000 条：只允许 ParseException 或成功")
    void randomGarbage() {
        String alphabet = "SELECT INSERT UPDATE DELETE CREATE TABLE FROM WHERE ( ) , ; ' 123 abc "
                + "= != < > <= >= + - * / % . * _ %\\n\\t-- /* */\"`~!@#$^&|{}[]:?";
        Random random = new Random(42);
        String[] parts = alphabet.split(" ");

        for (int i = 0; i < 5000; i++) {
            int segCount = 1 + random.nextInt(12);
            StringBuilder sb = new StringBuilder();
            for (int s = 0; s < segCount; s++) {
                String seg = parts[random.nextInt(parts.length)];
                // 随机截断片段制造半截 token
                if (!seg.isEmpty() && random.nextInt(4) == 0) {
                    seg = seg.substring(0, random.nextInt(seg.length()) + 1);
                }
                sb.append(seg).append(random.nextInt(3) == 0 ? "" : " ");
            }
            String sql = sb.toString();
            try {
                parse(sql);
            } catch (SqlParser.ParseException expected) {
                // 合法结局
            } catch (StackOverflowError so) {
                fail("解析器栈溢出（递归失控）: " + sql);
            } catch (RuntimeException | Error e) {
                if (e instanceof OutOfMemoryError) throw (OutOfMemoryError) e;
                fail("非受控异常 [" + e.getClass().getSimpleName() + "]: " + e.getMessage()
                        + "\n输入: " + sql);
            }
        }
    }

    @Test
    @DisplayName("结构化畸形 SQL：报 ParseException 而非崩溃")
    void structuredMalformed() {
        String[] malformed = {
                "SELECT",
                "SELECT FROM",
                "SELECT * FROM",
                "SELECT * FROM WHERE",
                "SELECT a b c d",
                "INSERT INTO",
                "INSERT INTO t",
                "INSERT INTO t VALUES",
                "INSERT INTO t VALUES (",
                "INSERT INTO t VALUES (1,)",
                "UPDATE SET",
                "UPDATE t SET",
                "UPDATE t SET = 5",
                "UPDATE t SET a =",
                "DELETE FROM t WHERE",
                "DELETE WHERE a = 1",
                "CREATE",
                "CREATE TABLE",
                "CREATE TABLE t",
                "CREATE TABLE t (",
                "CREATE TABLE t (a",
                "CREATE TABLE t (a INT",
                "CREATE TABLE t (a INT,)",
                "CREATE TABLE t (a INT, PRIMARY KEY)",
                "CREATE TABLE t (a INT, PRIMARY KEY ()",
                "CREATE INDEX ON t (a)",
                "DROP TABLE IF",
                "ALTER TABLE t SET",
                "TRUNCATE TABLE (",
                "SHOW",
                "SHOW EVERYTHING",
                "DESC",
                "EXPLAIN",
                "EXPLAIN SHOW TABLES",
                "BEGIN BEGIN",
                "COMMIT ROLLBACK COMMIT",
                "SELECT a FROM t WHERE a >",
                "SELECT a FROM t WHERE > 5",
                "SELECT a FROM t WHERE a BETWEEN",
                "SELECT a FROM t WHERE a BETWEEN 1 AND",
                "SELECT a FROM t WHERE a IN ()",
                "SELECT a FROM t WHERE a IN (,)",
                "SELECT a FROM t GROUP BY",
                "SELECT a FROM t GROUP BY HAVING",
                "SELECT a FROM t HAVING COUNT(*) >",
                "SELECT a FROM t ORDER BY DESC",
                "SELECT a FROM t LIMIT",
                "SELECT a FROM t LIMIT 'x'",
                "SELECT a FROM t JOIN b",
                "SELECT a FROM t JOIN b ON",
                "SELECT a FROM t LEFT JOIN",
                "SELECT () FROM t",
                "SELECT (() FROM t",
                "SELECT ))) FROM t",
                "SELECT UPPER( FROM t",
                "SELECT UPPER() FROM t",
                "SELECT FOO( FROM t",
                "SELECT a FROM t WHERE a = 'unterminated",
                "SELECT a FROM t WHERE a = /* unterminated",
                "SELECT COUNT(*) FROM t WHERE x = (SELECT",
                "SELECT a FROM t WHERE a = (SELECT x FROM",
                ";",
                ";;;",
                "  ",
                "SELECT a FROM t WHERE 1 = 1 AND AND",
                "SELECT - FROM t",
                "SELECT * FROM t WHERE a % % 2",
        };
        for (String sql : malformed) {
            try {
                parse(sql);
                // 解析成功也可接受（部分语句语法上恰好合法）
            } catch (SqlParser.ParseException expected) {
                // 合法结局
            } catch (StackOverflowError so) {
                fail("栈溢出: " + sql);
            } catch (RuntimeException | Error e) {
                fail("非受控异常 [" + e.getClass().getSimpleName() + "]: " + e.getMessage() + "\n输入: " + sql);
            }
        }
    }

    @Test
    @DisplayName("深度嵌套括号在合理深度内不栈溢出")
    void deepNesting() {
        // 500 层括号：递归下降应能承受；极端深度允许 ParseException/StackOverflow 之外不崩溃
        StringBuilder sb = new StringBuilder("SELECT a FROM t WHERE ");
        int depth = 500;
        for (int i = 0; i < depth; i++) sb.append('(');
        sb.append("a = 1");
        for (int i = 0; i < depth; i++) sb.append(')');
        assertDoesNotThrow(() -> {
            try {
                parse(sb.toString());
            } catch (SqlParser.ParseException ignored) {
                // 超出栈容量时 ParseException 可接受
            }
        });
    }

    @Test
    @DisplayName("正常语句在模糊器覆盖的字符环境中仍可解析")
    void sanityAfterFuzz() {
        com.minecraftdb.sql.ast.Statement stmt =
                parse("SELECT * FROM t WHERE id = 1");
        assertTrue(stmt instanceof com.minecraftdb.sql.ast.SelectStatement);
        assertThrows(SqlParser.ParseException.class, () -> parse("TOTAL GARBAGE"));
    }
}
