package com.aistudy.server.ai;

import com.aistudy.server.ai.content.AiContentStructureService;
import com.aistudy.server.ai.provider.AiChatRequest;
import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiProvider;
import com.aistudy.server.ai.service.AiUsageRecordService;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.service.SourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the F-18 isolation rule and the fail-loudly behaviour of the AI
 * content-structuring stage.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>Live published content is never rewritten in place: the run throws and
 *       writes nothing, because Search/AI/provenance already cite those rows.</li>
 *   <li>A draft/under-review revision may be classified in place.</li>
 *   <li>A model response that alters block text or invents a block type is
 *       refused before any write, so "structuring" cannot edit teaching
 *       material.</li>
 *   <li>Every provider attempt lands in the usage ledger, successful or not.</li>
 * </ul>
 *
 * <p>This is a Mockito test on purpose: the rule is about which calls are made
 * and in which order, so no database is involved and the guard runs on every
 * build.
 */
class AiContentStructureIsolationTest {

    private static final String SUBJECT = "owner-subject";
    private static final Long SPACE_ID = 7L;
    private static final Long SOURCE_ID = 11L;
    private static final Long REVISION_ID = 99L;

    private final AiProvider provider = mock(AiProvider.class);
    private final ContentBlockMapper contentBlockMapper = mock(ContentBlockMapper.class);
    private final SourceService sourceService = mock(SourceService.class);
    private final AiUsageRecordService usageRecordService = mock(AiUsageRecordService.class);
    private final com.aistudy.server.source.outline.service.SourceOutlineNodeService outlineService =
            mock(com.aistudy.server.source.outline.service.SourceOutlineNodeService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AiContentStructureService service;

    @BeforeEach
    void setUp() {
        service = new AiContentStructureService(
                provider, contentBlockMapper, sourceService, usageRecordService,
                outlineService, objectMapper);
        when(sourceService.getMine(SUBJECT, SPACE_ID, SOURCE_ID)).thenReturn(new Source());
        when(contentBlockMapper.selectBySpaceSourceOwner(SPACE_ID, SOURCE_ID, SUBJECT))
                .thenReturn(List.of(draftBlock("Chapter 1"), draftBlock("Body text")));
    }

    private ContentBlock draftBlock(String text) {
        ContentBlock block = new ContentBlock();
        block.setId(1L);
        block.setSpaceId(SPACE_ID);
        block.setSourceId(SOURCE_ID);
        block.setNormalizedText(text);
        block.setBlockType("PARAGRAPH");
        return block;
    }

    private Source publishedSource() {
        Source source = new Source();
        source.setId(SOURCE_ID);
        source.setSpaceId(SPACE_ID);
        source.setStatus("PUBLISHED");
        source.setCurrentExtractionRevisionId(REVISION_ID);
        return source;
    }

    private void answerWith(String json) {
        when(provider.chat(any(AiChatRequest.class), anyString()))
                .thenReturn(new AiChatResponse(json, "stepfun", "model-x", null));
    }

    /** A published source's live rows must not be touched, even to re-classify. */
    @Test
    void refusesToStructurePublishedContent() {
        when(sourceService.getMine(SUBJECT, SPACE_ID, SOURCE_ID)).thenReturn(publishedSource());
        List<ContentBlock> live = List.of(
                withRevision(draftBlock("Chapter 1")), withRevision(draftBlock("Body text")));
        when(contentBlockMapper.selectBySpaceSourceOwner(SPACE_ID, SOURCE_ID, SUBJECT))
                .thenReturn(live);

        assertThrows(IllegalStateException.class,
                () -> service.structureBlocks(SUBJECT, SPACE_ID, SOURCE_ID));

        verify(contentBlockMapper, never()).updateById(any(ContentBlock.class));
        // No provider call was made at all: refusing live content is decided
        // before spending tokens.
        verify(provider, never()).chat(any(AiChatRequest.class), anyString());
    }

    /**
     * F-07: structuring also proposes the outline tree, through the owning
     * service (the AI module may not write source_outline_node itself) and only
     * for blocks that belong to an extraction revision.
     */
    @Test
    void proposesOutlineTreeFromHeadings() {
        List<ContentBlock> stamped = List.of(
                withRevision(draftBlock("Chapter 1")), withRevision(draftBlock("Body text")));
        when(contentBlockMapper.selectBySpaceSourceOwner(SPACE_ID, SOURCE_ID, SUBJECT))
                .thenReturn(stamped);
        answerWith("[{\"type\":\"HEADING\",\"text\":\"Chapter 1\",\"level\":1},"
                + "{\"type\":\"PARAGRAPH\",\"text\":\"Body text\"}]");

        assertEquals(2, service.structureBlocks(SUBJECT, SPACE_ID, SOURCE_ID));

        verify(outlineService).replaceProposedTree(eq(SUBJECT), eq(SPACE_ID), eq(SOURCE_ID),
                eq(REVISION_ID), eq(java.util.List.of(
                        new com.aistudy.server.source.outline.service.SourceOutlineNodeService
                                .ProposedHeading("Chapter 1", 1))));
    }

    /** Blocks with no revision cannot be attributed to a proposal, so none is made. */
    @Test
    void noOutlineProposalWithoutARevision() {
        answerWith("[{\"type\":\"HEADING\",\"text\":\"Chapter 1\"},"
                + "{\"type\":\"PARAGRAPH\",\"text\":\"Body text\"}]");

        service.structureBlocks(SUBJECT, SPACE_ID, SOURCE_ID);

        verify(outlineService, never()).replaceProposedTree(any(), any(), any(), any(), any());
    }

    /** The published refusal happens before any write, proposal included. */
    @Test
    void publishedRefusalMakesNoProposal() {
        when(sourceService.getMine(SUBJECT, SPACE_ID, SOURCE_ID)).thenReturn(publishedSource());
        when(contentBlockMapper.selectBySpaceSourceOwner(SPACE_ID, SOURCE_ID, SUBJECT))
                .thenReturn(List.of(withRevision(draftBlock("Chapter 1"))));

        assertThrows(IllegalStateException.class,
                () -> service.structureBlocks(SUBJECT, SPACE_ID, SOURCE_ID));
        verify(outlineService, never()).replaceProposedTree(any(), any(), any(), any(), any());
    }

    /** The draft revision the ingestion pipeline leaves behind is structurable. */
    @Test
    void structuresDraftRevisionAndBooksTheLedger() {
        answerWith("[{\"type\":\"HEADING\",\"text\":\"Chapter 1\"},"
                + "{\"type\":\"PARAGRAPH\",\"text\":\"Body text\"}]");

        int updated = service.structureBlocks(SUBJECT, SPACE_ID, SOURCE_ID);

        assertEquals(2, updated);
        verify(contentBlockMapper, times(2)).updateById(any(ContentBlock.class));
        verify(usageRecordService).recordProviderCall(eq(SUBJECT), eq(SPACE_ID),
                eq(AiUsageRecordService.PURPOSE_CONTENT_STRUCTURE),
                any(AiChatResponse.class), anyInt(), isNull());
    }

    /** A model that rewrites the text is refused instead of stored. */
    @Test
    void refusesResponseThatAltersBlockText() {
        answerWith("[{\"type\":\"HEADING\",\"text\":\"Chapter One (improved)\"},"
                + "{\"type\":\"PARAGRAPH\",\"text\":\"Body text\"}]");

        assertThrows(IllegalStateException.class,
                () -> service.structureBlocks(SUBJECT, SPACE_ID, SOURCE_ID));

        verify(contentBlockMapper, never()).updateById(any(ContentBlock.class));
    }

    /** An unknown block type cannot enter the content vocabulary. */
    @Test
    void refusesUnknownBlockType() {
        answerWith("[{\"type\":\"POEM\",\"text\":\"Chapter 1\"},"
                + "{\"type\":\"PARAGRAPH\",\"text\":\"Body text\"}]");

        assertThrows(IllegalStateException.class,
                () -> service.structureBlocks(SUBJECT, SPACE_ID, SOURCE_ID));

        verify(contentBlockMapper, never()).updateById(any(ContentBlock.class));
    }

    /** An unusable payload is still billed to the ledger as a failure. */
    @Test
    void booksFailedProviderCall() {
        answerWith("not json at all");

        assertThrows(IllegalStateException.class,
                () -> service.structureBlocks(SUBJECT, SPACE_ID, SOURCE_ID));

        verify(usageRecordService).recordProviderCall(eq(SUBJECT), eq(SPACE_ID),
                eq(AiUsageRecordService.PURPOSE_CONTENT_STRUCTURE),
                any(AiChatResponse.class), anyInt(), eq("AI_RESPONSE_INVALID"));
        verify(contentBlockMapper, never()).updateById(any(ContentBlock.class));
    }

    /** A source the caller does not own is refused before anything is read. */
    @Test
    void refusesForeignSource() {
        when(sourceService.getMine(anyString(), anyLong(), anyLong())).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> service.structureBlocks(SUBJECT, SPACE_ID, 555L));

        verify(contentBlockMapper, never())
                .selectBySpaceSourceOwner(anyLong(), anyLong(), anyString());
    }

    private ContentBlock withRevision(ContentBlock block) {
        block.setExtractionRevisionId(REVISION_ID);
        return block;
    }
}
