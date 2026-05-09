package com.minecraftdb.sql.ast;

public class TransactionStatement extends Statement {
    public enum TxnType { BEGIN, COMMIT, ROLLBACK }

    public final TxnType txnType;

    public TransactionStatement(TxnType txnType) {
        this.txnType = txnType;
    }

    @Override
    public Type getType() {
        return switch (txnType) {
            case BEGIN -> Type.BEGIN;
            case COMMIT -> Type.COMMIT;
            case ROLLBACK -> Type.ROLLBACK;
        };
    }
}
