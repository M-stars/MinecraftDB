package com.minecraftdb.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** CSV 解析（RFC 4180）：引号、逗号、换行、双引号转义（纯函数） */
class CsvTest {

    @Test
    @DisplayName("简单表格：逗号分隔，多行")
    void simple() {
        List<List<String>> r = DataPort.parseCsv("id,name,age\n1,Alice,25\n2,Bob,30\n");
        assertEquals(3, r.size());
        assertEquals(List.of("id", "name", "age"), r.get(0));
        assertEquals(List.of("1", "Alice", "25"), r.get(1));
        assertEquals(List.of("2", "Bob", "30"), r.get(2));
    }

    @Test
    @DisplayName("引号单元格：内含逗号/换行")
    void quoted() {
        List<List<String>> r = DataPort.parseCsv(
                "name,note\n\"Doe, John\",\"line1\nline2\"\n");
        assertEquals(2, r.size());
        assertEquals(List.of("name", "note"), r.get(0));
        assertEquals(List.of("Doe, John", "line1\nline2"), r.get(1));
    }

    @Test
    @DisplayName("双引号转义：\"\" → \"")
    void escapedQuote() {
        List<List<String>> r = DataPort.parseCsv("a,b\n\"he said \"\"hi\"\"\",x\n");
        assertEquals(List.of("he said \"hi\"", "x"), r.get(1));
    }

    @Test
    @DisplayName("CRLF 换行与末尾无换行")
    void crlfAndEof() {
        List<List<String>> r = DataPort.parseCsv("a,b\r\n1,2\r\n3,4");
        assertEquals(3, r.size());
        assertEquals(List.of("3", "4"), r.get(2));
    }

    @Test
    @DisplayName("空行跳过，空单元格保留")
    void emptyCells() {
        List<List<String>> r = DataPort.parseCsv("a,b,c\n,,\n1,,x\n");
        assertEquals(List.of("", "", ""), r.get(1));
        assertEquals(List.of("1", "", "x"), r.get(2));
    }

    @Test
    @DisplayName("空输入返回空列表")
    void emptyInput() {
        assertTrue(DataPort.parseCsv(null).isEmpty());
        assertTrue(DataPort.parseCsv("").isEmpty());
        assertTrue(DataPort.parseCsv("\n\n").isEmpty());
    }
}
