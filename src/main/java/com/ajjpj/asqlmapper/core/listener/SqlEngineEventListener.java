package com.ajjpj.asqlmapper.core.listener;

import com.ajjpj.acollections.immutable.AVector;
import com.ajjpj.asqlmapper.core.SqlSnippet;


public interface SqlEngineEventListener {
    default void onBeforeQuery (SqlSnippet sql, Class<?> rowClass) {}
    default void onAfterQueryExecution() {}
    default void onAfterQueryIteration(int numRows) {}
    default void onBeforeInsert (SqlSnippet sql, Class<?> pkCls, AVector<String> columnNames) {}
    default void onAfterInsert (Object result) {}
    default void onBeforeUpdate (SqlSnippet sql) {}
    default void onAfterUpdate (int result) {}

    default void onFailed(Throwable th) {}

}