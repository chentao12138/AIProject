package com.aistudy.server.ingestion.version;

import com.aistudy.server.ingestion.version.SourceVersionCompareService.CompareResult;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/sources/{sourceId}/versions")
@SecurityRequirement(name = "bearerAuth")
public class SourceVersionCompareController {

    private final SourceVersionCompareService sourceVersionCompareService;

    public SourceVersionCompareController(SourceVersionCompareService sourceVersionCompareService) {
        this.sourceVersionCompareService = sourceVersionCompareService;
    }

    public record CompareRequest(Long fromRevisionId, Long toRevisionId) {
    }

    @PostMapping("/compare")
    public CompareResult compare(@PathVariable Long spaceId,
                                 @PathVariable Long sourceId,
                                 Authentication authentication,
                                 @RequestBody CompareRequest request) {
        return sourceVersionCompareService.compare(
                authentication.getName(), spaceId, sourceId,
                request.fromRevisionId(), request.toRevisionId());
    }
}
