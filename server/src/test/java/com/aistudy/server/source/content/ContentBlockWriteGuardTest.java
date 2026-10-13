package com.aistudy.server.source.content;

import com.aistudy.server.common.problem.ApiException;
import com.aistudy.server.source.content.dto.UpdateContentBlockRequest;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.content.service.ContentBlockService;
import com.aistudy.server.source.content.service.PublishedRevisionRule;
import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.service.SourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-26 — the two guards around an in-place block correction, as a plain unit
 * test so they run without MySQL (the same scenarios against real rows are in
 * {@code ContentBlockWriteIntegrationTest}).
 *
 * <p>What is being pinned is that a write is *not* issued: both failures must
 * leave the row untouched, because a partially applied content edit is worse
 * than a refused one — provenance rows already cite that block id.
 */
class ContentBlockWriteGuardTest {

    private static final String OWNER = "owner-1";
    private static final Long SPACE_ID = 7L;
    private static final Long PATH_SOURCE_ID = 11L;
    private static final Long BLOCK_ID = 99L;
    private static final Long CURRENT_REVISION_ID = 500L;

    private ContentBlockMapper contentBlockMapper;
    private SourceService sourceService;
    private ContentBlockService service;

    @BeforeEach
    void setUp() {
        contentBlockMapper = mock(ContentBlockMapper.class);
        sourceService = mock(SourceService.class);
        service = new ContentBlockService(contentBlockMapper, sourceService);
    }

    private Source source(String status, Long currentRevisionId) {
        Source source = new Source();
        source.setId(PATH_SOURCE_ID);
        source.setSpaceId(SPACE_ID);
        source.setStatus(status);
        source.setCurrentExtractionRevisionId(currentRevisionId);
        return source;
    }

    private ContentBlock block(Long revisionId) {
        ContentBlock block = new ContentBlock();
        block.setId(BLOCK_ID);
        block.setSpaceId(SPACE_ID);
        block.setSourceId(PATH_SOURCE_ID);
        block.setExtractionRevisionId(revisionId);
        block.setBlockType("PARAGRAPH");
        block.setNormalizedText("original text");
        return block;
    }

    private UpdateContentBlockRequest request(String text) {
        return new UpdateContentBlockRequest(text, null, null);
    }

    /**
     * The defect: {@code selectByIdSpaceOwner} proved only space + owner, so a
     * block of another source in the SAME space was editable through this
     * route. The lookup must carry the path source id, and a miss must be a
     * 404 — never an update.
     */
    @Test
    void blockOfAnotherSourceInTheSameSpaceIsNotFoundAndNeverWritten() {
        when(sourceService.getMine(OWNER, SPACE_ID, PATH_SOURCE_ID))
                .thenReturn(source(SourceService.STATUS_NEEDS_REVIEW, CURRENT_REVISION_ID));
        when(contentBlockMapper.selectByIdAndSpaceSourceOwner(
                BLOCK_ID, SPACE_ID, PATH_SOURCE_ID, OWNER)).thenReturn(null);

        assertThatThrownBy(() -> service.update(OWNER, SPACE_ID, PATH_SOURCE_ID, BLOCK_ID,
                request("tampered")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status().value()).isEqualTo(404);
                    assertThat(ex.code()).isEqualTo("NOT_FOUND");
                });

        verify(contentBlockMapper, never()).updateById(any(ContentBlock.class));
    }

    /**
     * Defence in depth: even if a future mapper read loosens its predicate, the
     * loaded row's own {@code sourceId} is compared with the path before any
     * write, so a foreign-source row cannot slip through on a mapper bug.
     */
    @Test
    void rowCarryingAnotherSourceIdIsRefusedEvenWhenTheReadReturnedIt() {
        when(sourceService.getMine(OWNER, SPACE_ID, PATH_SOURCE_ID))
                .thenReturn(source(SourceService.STATUS_NEEDS_REVIEW, CURRENT_REVISION_ID));
        ContentBlock foreign = block(CURRENT_REVISION_ID);
        foreign.setSourceId(12L);
        when(contentBlockMapper.selectByIdAndSpaceSourceOwner(
                BLOCK_ID, SPACE_ID, PATH_SOURCE_ID, OWNER)).thenReturn(foreign);

        assertThatThrownBy(() -> service.update(OWNER, SPACE_ID, PATH_SOURCE_ID, BLOCK_ID,
                request("tampered")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.status().value()).isEqualTo(404));

        verify(contentBlockMapper, never()).updateById(any(ContentBlock.class));
    }

    /** A missing/unowned source is 404 with the source-specific code. */
    @Test
    void unknownSourceIsSourceNotFound() {
        when(sourceService.getMine(anyString(), anyLong(), anyLong())).thenReturn(null);

        assertThatThrownBy(() -> service.update(OWNER, SPACE_ID, PATH_SOURCE_ID, BLOCK_ID,
                request("tampered")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status().value()).isEqualTo(404);
                    assertThat(ex.code()).isEqualTo("SOURCE_NOT_FOUND");
                });
    }

    /**
     * The live-content half of F-26: a block of the revision a PUBLISHED source
     * is serving is read-only in place, with a stable code, because rewriting
     * it would change what Search/AI answers and provenance already cite.
     */
    @Test
    void editingBlockOfCurrentPublishedRevisionIsRefusedWithStableCode() {
        when(sourceService.getMine(OWNER, SPACE_ID, PATH_SOURCE_ID))
                .thenReturn(source(SourceService.STATUS_PUBLISHED, CURRENT_REVISION_ID));
        when(contentBlockMapper.selectByIdAndSpaceSourceOwner(
                BLOCK_ID, SPACE_ID, PATH_SOURCE_ID, OWNER))
                .thenReturn(block(CURRENT_REVISION_ID));

        assertThatThrownBy(() -> service.update(OWNER, SPACE_ID, PATH_SOURCE_ID, BLOCK_ID,
                request("silently rewritten answer source")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status().value()).isEqualTo(409);
                    assertThat(ex.code())
                            .isEqualTo(ContentBlockService.ERROR_PUBLISHED_REVISION_READONLY);
                    assertThat(ex.code()).isEqualTo("CONTENT_BLOCK_PUBLISHED_REVISION_READONLY");
                });

        verify(contentBlockMapper, never()).updateById(any(ContentBlock.class));
    }

    /** Legacy shape: PUBLISHED source with a NULL pointer serves its NULL rows. */
    @Test
    void legacyPublishedRowWithoutAnyRevisionPointerIsAlsoReadOnly() {
        when(sourceService.getMine(OWNER, SPACE_ID, PATH_SOURCE_ID))
                .thenReturn(source(SourceService.STATUS_PUBLISHED, null));
        when(contentBlockMapper.selectByIdAndSpaceSourceOwner(
                BLOCK_ID, SPACE_ID, PATH_SOURCE_ID, OWNER)).thenReturn(block(null));

        assertThatThrownBy(() -> service.update(OWNER, SPACE_ID, PATH_SOURCE_ID, BLOCK_ID,
                request("tampered")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.status().value()).isEqualTo(409));
    }

    /**
     * The path the correction flow needs: while the source is under review the
     * same revision is still a draft to be fixed, so the edit applies and only
     * the submitted fields move.
     */
    @Test
    void underReviewRevisionIsStillEditableAndOnlySubmittedFieldsMove() {
        when(sourceService.getMine(OWNER, SPACE_ID, PATH_SOURCE_ID))
                .thenReturn(source(SourceService.STATUS_NEEDS_REVIEW, CURRENT_REVISION_ID));
        ContentBlock stored = block(CURRENT_REVISION_ID);
        stored.setLocatorJson("{\"lineStart\":1,\"lineEnd\":2}");
        when(contentBlockMapper.selectByIdAndSpaceSourceOwner(
                BLOCK_ID, SPACE_ID, PATH_SOURCE_ID, OWNER)).thenReturn(stored);

        ContentBlock updated = service.update(OWNER, SPACE_ID, PATH_SOURCE_ID, BLOCK_ID,
                request("corrected text"));

        ArgumentCaptor<ContentBlock> written = ArgumentCaptor.forClass(ContentBlock.class);
        verify(contentBlockMapper).updateById(written.capture());
        assertThat(written.getValue().getNormalizedText()).isEqualTo("corrected text");
        assertThat(updated.getNormalizedText()).isEqualTo("corrected text");
        // locator/structured data were not in the request and stay untouched.
        assertThat(updated.getLocatorJson()).isEqualTo("{\"lineStart\":1,\"lineEnd\":2}");
        assertThat(updated.getUpdatedAt()).isNotNull();
    }

    /** A block of an older, already replaced revision is not live: editable. */
    @Test
    void supersededRevisionBlockOfPublishedSourceIsEditable() {
        when(sourceService.getMine(OWNER, SPACE_ID, PATH_SOURCE_ID))
                .thenReturn(source(SourceService.STATUS_PUBLISHED, CURRENT_REVISION_ID));
        when(contentBlockMapper.selectByIdAndSpaceSourceOwner(
                BLOCK_ID, SPACE_ID, PATH_SOURCE_ID, OWNER)).thenReturn(block(404L));

        assertThat(service.update(OWNER, SPACE_ID, PATH_SOURCE_ID, BLOCK_ID,
                request("housekeeping"))).isNotNull();
        verify(contentBlockMapper).updateById(any(ContentBlock.class));
    }

    /**
     * The rule the admin governance lane shares: live-ness is a property of
     * (status, pointer, row revision), not of who is asking, so it is asserted
     * directly here as well.
     */
    @Test
    void publishedRuleKeepsItsOwnTruthTable() {
        assertThat(PublishedRevisionRule.isLivePublishedContent(
                source(SourceService.STATUS_PUBLISHED, 5L), 5L)).isTrue();
        assertThat(PublishedRevisionRule.isLivePublishedContent(
                source(SourceService.STATUS_PUBLISHED, 5L), 6L)).isFalse();
        assertThat(PublishedRevisionRule.isLivePublishedContent(
                source(SourceService.STATUS_NEEDS_REVIEW, 5L), 5L)).isFalse();
        assertThat(PublishedRevisionRule.isLivePublishedContent(
                source(SourceService.STATUS_ARCHIVED, 5L), 5L)).isFalse();
        assertThat(PublishedRevisionRule.isLivePublishedContent(null, 5L)).isFalse();
    }

    /** listMine keeps its parent-ownership contract: null means 404 upstream. */
    @Test
    void listingWithoutAnOwnedSourceStaysNullRatherThanEmpty() {
        when(sourceService.getMine(eq(OWNER), eq(SPACE_ID), anyLong())).thenReturn(null);
        assertThat(service.listMine(OWNER, SPACE_ID, PATH_SOURCE_ID, null)).isNull();
    }
}
