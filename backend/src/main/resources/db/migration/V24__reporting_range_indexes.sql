-- T36 (ADR-026 D5/D6): range indexes for the outstanding report's period reads.
--
-- The collection-rate cohort reads every installment due in one calendar month across the portfolio. The
-- baseline index idx_installment_contract_due (contract_id, due_date) leads with contract_id, so it cannot
-- serve a due_date range on its own and the read would scan every installment.
CREATE INDEX idx_installment_due_date ON installment (due_date);

-- Settlement cash sums cash_received over an executed_at range. idx_settlement_contract_executed
-- (contract_id, executed_at) leads with contract_id for the same reason.
CREATE INDEX idx_settlement_executed_at ON settlement (executed_at);
