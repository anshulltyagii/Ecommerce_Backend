package com.ecommerce.repository.impl;

import com.ecommerce.model.ReturnRequest;
import com.ecommerce.repository.ReturnRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ReturnRepositoryImpl implements ReturnRepository {

    @Autowired
    private JdbcTemplate jdbc;

    @Override
    public void save(ReturnRequest req) {
        String sql = "INSERT INTO return_requests (order_id, reason, status, created_at) VALUES (?, ?, 'REQUESTED', NOW())";
        jdbc.update(sql, req.getOrderId(), req.getReason());
    }

    @Override
    public List<ReturnRequest> findByUserId(Long userId) {
        String sql = """
            SELECT rr.* FROM return_requests rr
            JOIN orders o ON rr.order_id = o.id
            WHERE o.user_id = ?
            ORDER BY rr.created_at DESC
        """;
        return jdbc.query(sql, rowMapper, userId);
    }

    @Override
    public boolean existsByOrderId(Long orderId) {
        String sql = "SELECT COUNT(*) FROM return_requests WHERE order_id = ?";
        Integer count = jdbc.queryForObject(sql, Integer.class, orderId);
        return count != null && count > 0;
    }

    private final RowMapper<ReturnRequest> rowMapper = (rs, rowNum) -> {
        ReturnRequest rr = new ReturnRequest();
        rr.setId(rs.getLong("id"));
        rr.setOrderId(rs.getLong("order_id"));
        rr.setReason(rs.getString("reason"));
        rr.setStatus(rs.getString("status"));
        rr.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return rr;
    };
}