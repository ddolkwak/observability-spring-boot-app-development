package com.example.spring_app;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hibernate.resource.jdbc.spi.StatementInspector;

public class SqlCaptureInspector implements StatementInspector {

    private static final List<String> SQL = new CopyOnWriteArrayList<>();

    public SqlCaptureInspector() {
    }

    @Override
    public String inspect(String sql) {
        SQL.add(sql);
        return sql;
    }

    public static void clear() {
        SQL.clear();
    }

    public static List<String> statements() {
        return List.copyOf(SQL);
    }
}