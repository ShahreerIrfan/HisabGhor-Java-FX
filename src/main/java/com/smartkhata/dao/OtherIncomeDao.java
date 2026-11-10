package com.smartkhata.dao;

import com.smartkhata.model.entity.OtherIncome;
import com.smartkhata.model.vo.Money;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OtherIncomeDao {

    OtherIncome save(Connection conn, OtherIncome income) throws SQLException;

    Optional<OtherIncome> findById(Connection conn, long id) throws SQLException;

    List<OtherIncome> search(Connection conn, long businessId, String query,
                            LocalDate startDate, LocalDate endDate) throws SQLException;

    Money getTotalOtherIncome(Connection conn, long businessId, LocalDate startDate, LocalDate endDate) throws SQLException;

    void voidOtherIncome(Connection conn, long incomeId, long businessId, String reason,
                         LocalDateTime reversedAt) throws SQLException;

    void deleteVoided(Connection conn, long incomeId, long businessId) throws SQLException;
}

