package com.minecraftdb.sql;

import com.minecraftdb.sql.ast.Expression;
import com.minecraftdb.sql.ast.WhereClause;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JOIN 组合行命名空间（纯函数，可脱离 Minecraft 单测）。
 *
 * <p>列槽位布局：先按表顺序排列各表的限定列（{@code alias.col}，无别名时用表名），
 * 再追加跨表无歧义的裸列名（裸列值与其来源限定列相同）。有歧义的裸列（多表同名列）
 * 不注册——按裸名引用会解析为未知列（条件恒 false / 投影为 null），与 SQL 的歧义语义一致。
 */
public final class JoinNamespace {

    /** 参与 JOIN 的一张表：限定名（别名或表名）+ 列名列表（schema 原始大小写） */
    public record TableContext(String qualifier, List<String> schemaColumnNames) {}

    /** ON 条件中提取到的等值连接键 */
    public record EquiKey(String leftName, String rightColumn) {}

    public final List<String> columnNames;          // 显示名：限定列（按表序）+ 无歧义裸列
    public final List<String> qualifiedNames;       // 限定列（扁平，按表序）
    public final List<Integer> tableSlotOffsets;    // 每张表在其限定列区域的起始槽位
    public final List<Integer> slotOwnerTable;      // 限定列槽位 → 所属表下标
    final Map<String, Integer> qualifiedSlotLower;  // lower(限定名) → 槽位
    final List<BareEntry> bareEntries;              // 无歧义裸列
    final Map<String, Integer> bareSlotLower;       // lower(裸列) → columnNames 全列表槽位
    final Map<String, Integer> bareQualSlotLower;   // lower(裸列) → 来源限定列槽位（组合行求值用）

    private static final class BareEntry {
        final String display;      // 裸列名（取第一次出现的原始大小写）
        final int qualifiedSlot;   // 来源限定列槽位
        final int sourceTable;     // 来源表下标

        BareEntry(String display, int qualifiedSlot, int sourceTable) {
            this.display = display;
            this.qualifiedSlot = qualifiedSlot;
            this.sourceTable = sourceTable;
        }
    }

    public static JoinNamespace build(List<TableContext> tables) {
        List<String> qualified = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();
        List<Integer> owner = new ArrayList<>();
        Map<String, Integer> qualSlot = new HashMap<>();
        Map<String, Integer> bareCount = new HashMap<>();
        Map<String, String> bareFirst = new HashMap<>();
        Map<String, Integer> bareSource = new HashMap<>();

        Map<String, String> bareQualName = new HashMap<>();
        for (int t = 0; t < tables.size(); t++) {
            TableContext tc = tables.get(t);
            offsets.add(qualified.size());
            for (String col : tc.schemaColumnNames()) {
                String qualName = tc.qualifier() + "." + col;
                qualSlot.put(qualName.toLowerCase(Locale.ROOT), qualified.size());
                qualified.add(qualName);
                owner.add(t);
                String bareKey = col.toLowerCase(Locale.ROOT);
                bareCount.merge(bareKey, 1, Integer::sum);
                bareFirst.putIfAbsent(bareKey, col);
                bareSource.putIfAbsent(bareKey, t);
                bareQualName.putIfAbsent(bareKey, qualName);
            }
        }
        List<String> names = new ArrayList<>(qualified);
        List<BareEntry> bares = new ArrayList<>();
        Map<String, Integer> bareSlot = new HashMap<>();
        for (Map.Entry<String, Integer> e : bareCount.entrySet()) {
            if (e.getValue() == 1) {
                String key = e.getKey();
                int srcSlot = qualSlot.get(bareQualName.get(key));
                BareEntry be = new BareEntry(bareFirst.get(key), srcSlot, bareSource.get(key));
                bareSlot.put(key, names.size());
                names.add(be.display);
                bares.add(be);
            }
        }
        return new JoinNamespace(names, qualified, offsets, owner, qualSlot, bares, bareSlot);
    }

    private JoinNamespace(List<String> columnNames, List<String> qualifiedNames,
                          List<Integer> tableSlotOffsets, List<Integer> slotOwnerTable,
                          Map<String, Integer> qualifiedSlotLower, List<BareEntry> bareEntries,
                          Map<String, Integer> bareSlotLower) {
        this.columnNames = columnNames;
        this.qualifiedNames = qualifiedNames;
        this.tableSlotOffsets = tableSlotOffsets;
        this.slotOwnerTable = slotOwnerTable;
        this.qualifiedSlotLower = qualifiedSlotLower;
        this.bareEntries = bareEntries;
        this.bareSlotLower = bareSlotLower;
        this.bareQualSlotLower = new HashMap<>();
        for (BareEntry be : bareEntries) {
            this.bareQualSlotLower.put(be.display.toLowerCase(Locale.ROOT), be.qualifiedSlot);
        }
    }

    /** 名称解析：限定名（大小写不敏感）或无歧义裸列 → 显示名；否则 null */
    public String resolve(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (qualifiedSlotLower.containsKey(lower)) {
            return columnNames.get(qualifiedSlotLower.get(lower));
        }
        if (bareSlotLower.containsKey(lower)) {
            return columnNames.get(bareSlotLower.get(lower));
        }
        return null;
    }

    /** 名称是否可解析 */
    public boolean exists(String name) {
        return resolve(name) != null;
    }

    /** 限定列槽位（仅限定区域；裸列槽位不入此表） */
    int qualifiedSlot(String qualifiedName) {
        return qualifiedSlotLower.get(qualifiedName.toLowerCase(Locale.ROOT));
    }

    /**
     * 组合行解析器：qualifiedValues 与 qualifiedNames 对齐。
     * 裸列名转发到其来源限定列。
     */
    public Expression.Resolver resolverFor(List<Object> qualifiedValues) {
        return new Expression.Resolver() {
            @Override
            public Object value(String name) {
                Integer idx = slotFor(name);
                return idx != null && idx < qualifiedValues.size() ? qualifiedValues.get(idx) : null;
            }

            @Override
            public boolean exists(String name) {
                return slotFor(name) != null;
            }
        };
    }

    private Integer slotFor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        Integer idx = qualifiedSlotLower.get(lower);
        if (idx != null) return idx;
        return bareQualSlotLower.get(lower); // 裸列 → 来源限定列槽位（组合行值区域）
    }

    /**
     * 从 ON 条件的 AND 合取项中提取等值连接键：
     * 一侧解析到左表集合（下标 &lt; leftTableCount）的限定列，另一侧是右表（rightTableIndex）的列。
     * 两种书写方向都接受（a.x = b.y 或 b.y = a.x）。找不到返回 null（退化为嵌套循环全比较）。
     */
    public EquiKey findEqui(WhereClause on, int leftTableCount, int rightTableIndex) {
        for (WhereClause.Node node : on.splitConjuncts()) {
            if (!(node instanceof WhereClause.OpCondition op) || !"=".equals(op.getOperator())) continue;
            if (!(op.getOperand() instanceof Expression.ColumnRef a)) continue;
            if (!(op.getValue() instanceof Expression.ColumnRef b)) continue;
            String aResolved = resolve(a.name);
            String bResolved = resolve(b.name);
            if (aResolved == null || bResolved == null) continue;

            int aSlot = qualifiedSlot(aResolved);
            int bSlot = qualifiedSlot(bResolved);
            if (aSlot < 0 || bSlot < 0) continue;

            int aTable = slotOwnerTable.get(aSlot);
            int bTable = slotOwnerTable.get(bSlot);
            // a 在左侧表集合、b 在右表
            if (aTable < leftTableCount && bTable == rightTableIndex) {
                return new EquiKey(aResolved, rightColumnName(bSlot));
            }
            // b 在左侧表集合、a 在右表
            if (bTable < leftTableCount && aTable == rightTableIndex) {
                return new EquiKey(bResolved, rightColumnName(aSlot));
            }
        }
        return null;
    }

    /** 由限定槽位反推该表的 schema 列名（限定名 = qualifier + "." + 列名） */
    private String rightColumnName(int slot) {
        String qual = qualifiedNames.get(slot);
        return qual.substring(qual.indexOf('.') + 1);
    }
}
