package com.aistudy.server.provenance.service;

import com.aistudy.server.provenance.dto.SourceReference;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads SourceReference projections for content-block citations.
 * Used by KP/Question/Note source APIs so clients never N+1.
 */
@Service
public class SourceReferenceService {

    private final JdbcTemplate jdbcTemplate;

    public SourceReferenceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SourceReference> byContentBlockIds(Long spaceId, List<Long> blockIds) {
        if (blockIds == null || blockIds.isEmpty()) {
            return List.of();
        }
        List<SourceReference> out = new ArrayList<>();
        for (Long blockId : blockIds) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT cb.id AS content_block_id, cb.block_type, cb.locator_json, "
                            + "cb.extraction_revision_id AS revision_id, "
                            + "sp.id AS source_page_id, sp.page_order, sp.source_page_number, "
                            + "sp.printed_page_number, "
                            + "s.id AS source_id, s.title AS source_title, "
                            + "cb.source_asset_id AS source_asset_id, "
                            + "er.version AS revision_version "
                            + "FROM content_block cb "
                            + "JOIN source_page sp ON sp.id = cb.source_page_id AND sp.space_id = cb.space_id "
                            + "JOIN source s ON s.id = cb.source_id AND s.space_id = cb.space_id "
                            + "LEFT JOIN extraction_revision er ON er.id = cb.extraction_revision_id "
                            + "WHERE cb.id = ? AND cb.space_id = ?",
                    blockId, spaceId);
            for (Map<String, Object> row : rows) {
                out.add(new SourceReference(
                        longVal(row.get("source_id")),
                        str(row.get("source_title")),
                        longVal(row.get("source_asset_id")),
                        longVal(row.get("source_page_id")),
                        intVal(row.get("page_order")),
                        intVal(row.get("source_page_number")),
                        intVal(row.get("printed_page_number")),
                        longVal(row.get("content_block_id")),
                        str(row.get("block_type")),
                        str(row.get("locator_json")),
                        longVal(row.get("revision_id")),
                        intVal(row.get("revision_version"))
                ));
            }
        }
        return out;
    }

    private static Long longVal(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    private static Integer intVal(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
