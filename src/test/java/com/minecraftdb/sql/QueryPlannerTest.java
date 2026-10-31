package com.minecraftdb.sql;

import com.minecraftdb.sql.ast.ColumnDefinition;
import com.minecraftdb.sql.ast.SelectStatement;
import com.minecraftdb.sql.ast.WhereClause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 查询计划器：索引选择与键归一化（纯函数） */
class QueryPlannerTest {

    private static final List<ColumnDefinition> SCHEMA = List.of(
            new ColumnDefinition("id", "INT"),
            new ColumnDefinition("name", "VARCHAR(50)"),
            new ColumnDefinition("score", "DOUBLE"),
            new ColumnDefinition("active", "BOOLEAN"),
            new ColumnDefinition("created", "DATE"));

    private static final Set<String> INDEXED = Set.of("id", "name", "score", "created");

    private WhereClause where(String condition) {
        SelectStatement stmt = (SelectStatement) new SqlParser(
                new SqlLexer("SELECT * FROM t WHERE " + condition).tokenize()).parse();
        return stmt.whereClause;
    }

    @Test
    @DisplayName("等值条件命中索引列 → INDEX SEEK")
    void indexSeek() {
        QueryPlanner.Plan plan = QueryPlanner.pickPlan(INDEXED, SCHEMA, where("id = 5"));
        assertTrue(plan.indexUsed());
        assertEquals("id", plan.column());
        assertEquals(5L, plan.key());
    }

    @Test
    @DisplayName("无索引列等值 → 全表扫描")
    void fullScan() {
        QueryPlanner.Plan plan = QueryPlanner.pickPlan(INDEXED, SCHEMA, where("active = true"));
        assertFalse(plan.indexUsed());
    }

    @Test
    @DisplayName("非等值运算符不走索引")
    void rangeNoIndex() {
        assertFalse(QueryPlanner.pickPlan(INDEXED, SCHEMA, where("id > 5")).indexUsed());
        assertFalse(QueryPlanner.pickPlan(INDEXED, SCHEMA, where("id != 5")).indexUsed());
    }

    @Test
    @DisplayName("AND 合取中的等值项可用：WHERE a = 1 AND b > 2")
    void andConjunct() {
        QueryPlanner.Plan plan = QueryPlanner.pickPlan(INDEXED, SCHEMA, where("id = 1 AND score > 2.0"));
        assertTrue(plan.indexUsed());
        assertEquals("id", plan.column());
    }

    @Test
    @DisplayName("OR 不拆分：无法安全使用单列索引")
    void orNoIndex() {
        assertFalse(QueryPlanner.pickPlan(INDEXED, SCHEMA, where("id = 1 OR score = 2.0")).indexUsed());
    }

    @Test
    @DisplayName("无 WHERE → 全表扫描")
    void noWhere() {
        assertFalse(QueryPlanner.pickPlan(INDEXED, SCHEMA, null).indexUsed());
    }

    @Test
    @DisplayName("右侧为列引用而非常量 → 全表扫描")
    void columnRhs() {
        assertFalse(QueryPlanner.pickPlan(INDEXED, SCHEMA, where("id = score")).indexUsed());
    }

    // === IndexKey 归一化 ===

    @Test
    @DisplayName("数值列：Integer/Long/Double 归一化到同一键")
    void numericKey() {
        Object i = QueryPlanner.IndexKey.keyFor(SCHEMA.get(0), 25);
        Object l = QueryPlanner.IndexKey.keyFor(SCHEMA.get(0), 25L);
        Object d = QueryPlanner.IndexKey.keyFor(SCHEMA.get(0), 25.0);
        assertEquals(i, l);
        assertEquals(i, d);
    }

    @Test
    @DisplayName("布尔列：true/'true'/1 同键，'yes' 不可索引")
    void booleanKey() {
        Object t1 = QueryPlanner.IndexKey.keyFor(SCHEMA.get(3), true);
        assertEquals(t1, QueryPlanner.IndexKey.keyFor(SCHEMA.get(3), "true"));
        assertEquals(t1, QueryPlanner.IndexKey.keyFor(SCHEMA.get(3), 1));
        assertEquals("b:false", QueryPlanner.IndexKey.keyFor(SCHEMA.get(3), false));
        assertNull(QueryPlanner.IndexKey.keyFor(SCHEMA.get(3), "yes"));
    }

    @Test
    @DisplayName("字符串列：数值字面量按字符串键匹配存储值")
    void stringKey() {
        // VARCHAR 列存 "25"，WHERE name = 25 → 两侧都归一化为 "s:25"
        assertEquals(QueryPlanner.IndexKey.keyFor(SCHEMA.get(1), "25"),
                QueryPlanner.IndexKey.keyFor(SCHEMA.get(1), 25));
    }

    @Test
    @DisplayName("数值列上不可解析的字符串返回 null")
    void unparseableNumeric() {
        assertNull(QueryPlanner.IndexKey.keyFor(SCHEMA.get(0), "abc"));
    }

    @Test
    @DisplayName("null 值不可索引")
    void nullKey() {
        assertNull(QueryPlanner.IndexKey.keyFor(SCHEMA.get(0), null));
    }
}
