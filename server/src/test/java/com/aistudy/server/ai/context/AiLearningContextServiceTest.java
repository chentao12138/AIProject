package com.aistudy.server.ai.context;

import com.aistudy.server.ai.config.AiProperties;
import com.aistudy.server.common.problem.ApiErrorCodes;
import com.aistudy.server.common.problem.ApiException;
import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.service.KnowledgePointService;
import com.aistudy.server.search.dto.SearchPageResponse;
import com.aistudy.server.search.dto.SearchResult;
import com.aistudy.server.search.type.SearchEntityType;
import com.aistudy.server.search.service.SearchService;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.service.ContentBlockService;
import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.service.SourceService;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.service.SourcePageService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI-003 / C03 — learning context assembly: automatic retrieval goes through
 * SearchService only, while a learner's ID selection is validated against the
 * LearningSpace and wins the precedence slot.
 */
class AiLearningContextServiceTest {

    private static final Long SPACE_ID = 42L;
    private static final String OWNER = "owner-sub";

    private SearchService searchService;
    private ContentBlockService contentBlockService;
    private SourcePageService sourcePageService;
    private SourceService sourceService;
    private KnowledgePointService knowledgePointService;
    private AiProperties properties;
    private AiLearningContextService service;

    @BeforeEach
    void setUp() {
        searchService = mock(SearchService.class);
        contentBlockService = mock(ContentBlockService.class);
        sourcePageService = mock(SourcePageService.class);
        sourceService = mock(SourceService.class);
        knowledgePointService = mock(KnowledgePointService.class);
        properties = new AiProperties();
        service = new AiLearningContextService(searchService, properties,
                contentBlockService, sourcePageService, sourceService, knowledgePointService);
    }

    @Test
    void blankMessageDoesNotCallSearch() {
        assertTrue(service.assemble("owner", "user", 1L, "   ").isEmpty());
        assertTrue(service.assemble("owner", "user", 1L, null).isEmpty());
    }

    @Test
    void searchServiceIsCalledWithSpaceUserAndBoundedSize() {
        when(searchService.search(anyString(), anyString(), anyLong(), anyString(),
                isNull(), anyInt(), anyInt()))
                .thenReturn(new SearchPageResponse(List.of(), 0, 8, 0, 1));

        service.assemble("owner-sub", "user-sub", 42L, "Explain 事务隔离");

        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> space = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> size = ArgumentCaptor.forClass(Integer.class);
        verify(searchService).search(owner.capture(), user.capture(), space.capture(),
                query.capture(), isNull(), eq(0), size.capture());
        assertEquals("owner-sub", owner.getValue());
        assertEquals("user-sub", user.getValue());
        assertEquals(42L, space.getValue());
        assertEquals("Explain 事务隔离", query.getValue());
        assertEquals(8, size.getValue());
    }

    @Test
    void convertsSafeSearchResultFieldsOnly() {
        SearchResult source = new SearchResult(
                SearchEntityType.SOURCE, 1L, "数据库教程", "snippet-source",
                60, 10L, null, null, null, null, null);
        SearchResult question = new SearchResult(
                SearchEntityType.QUESTION, 2L, "什么是 ACID?", "snippet-q",
                40, null, null, null, null, null, 2L);
        when(searchService.search(anyString(), anyString(), anyLong(), anyString(),
                isNull(), anyInt(), anyInt()))
                .thenReturn(new SearchPageResponse(List.of(source, question), 0, 8, 2, 1));

        List<AiContextItem> items = service.assemble("o", "u", 1L, "ACID 数据库");
        assertEquals(2, items.size());
        assertEquals("SOURCE", items.get(0).type());
        assertEquals(1L, items.get(0).entityId());
        assertEquals("数据库教程", items.get(0).title());
        assertEquals("snippet-source", items.get(0).snippet());
        assertEquals("QUESTION", items.get(1).type());
        assertEquals(2L, items.get(1).entityId());
    }

    /** C04 — an automatically retrieved block already knows its whole route. */
    @Test
    void searchHitCarriesStructuredLocatorAndHumanLabel() {
        SearchResult block = new SearchResult(
                SearchEntityType.CONTENT_BLOCK, 7L, "索引结构", "B+ 树叶子节点相连",
                20, 10L, 11L, 20L, 3, null, null);
        when(searchService.search(anyString(), anyString(), anyLong(), anyString(),
                isNull(), anyInt(), anyInt()))
                .thenReturn(new SearchPageResponse(List.of(block), 0, 8, 1, 1));

        AiContextItem item = service.assemble("o", "u", 1L, "索引").get(0);
        assertEquals(7L, item.contentBlockId());
        assertEquals(20L, item.sourcePageId());
        assertEquals(10L, item.sourceId());
        assertEquals("P3", item.locator());
        assertFalse(item.learnerSelected());
    }

    @Test
    void emptySearchPageYieldsEmptyContext() {
        when(searchService.search(anyString(), anyString(), anyLong(), anyString(),
                isNull(), anyInt(), anyInt()))
                .thenReturn(new SearchPageResponse(List.of(), 0, 8, 0, 1));
        assertTrue(service.assemble("o", "u", 1L, "nothing here").isEmpty());
    }

    @Test
    void searchFailureYieldsEmptyContextWithoutThrowing() {
        when(searchService.search(anyString(), anyString(), anyLong(), anyString(),
                isNull(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("db down"));
        assertTrue(service.assemble("o", "u", 1L, "something").isEmpty());
    }

    // ==================== C03: selected context ====================

    /** A content block ID is enough: page and source are derived server-side. */
    @Test
    void selectedBlockDerivesPageAndSourceAndBodyText() {
        ContentBlock block = block(7L, SPACE_ID, 10L, 20L, "第二章 索引结构：B+ 树自顶向下查找");
        when(contentBlockService.getMine(OWNER, SPACE_ID, 7L)).thenReturn(block);
        when(sourcePageService.getMine(OWNER, SPACE_ID, 20L)).thenReturn(page(20L, SPACE_ID, 10L, 3));
        when(sourceService.getMine(OWNER, SPACE_ID, 10L)).thenReturn(source(10L, SPACE_ID, "数据库教程"));

        List<AiContextItem> items = service.resolveSelectedContext(OWNER, SPACE_ID, null, null, 7L, null);

        assertEquals(1, items.size());
        AiContextItem item = items.get(0);
        assertEquals("CONTENT_BLOCK", item.type());
        assertEquals(7L, item.entityId());
        assertEquals(7L, item.contentBlockId());
        assertEquals(20L, item.sourcePageId());
        assertEquals(10L, item.sourceId());
        assertEquals("P3", item.locator());
        assertTrue(item.learnerSelected());
        assertTrue(item.snippet().contains("B+ 树自顶向下查找"), "server must fetch the body itself");
    }

    /** A page ID is its own reference level and fixes its source. */
    @Test
    void selectedPageDerivesSourceAndCarriesExtractedText() {
        SourcePage page = page(20L, SPACE_ID, 10L, 4);
        page.setExtractedText("第四页正文");
        when(sourcePageService.getMine(OWNER, SPACE_ID, 20L)).thenReturn(page);
        when(sourceService.getMine(OWNER, SPACE_ID, 10L)).thenReturn(source(10L, SPACE_ID, "数据库教程"));

        List<AiContextItem> items = service.resolveSelectedContext(OWNER, SPACE_ID, null, 20L, null, null);

        assertEquals(1, items.size());
        assertEquals(AiLearningContextService.REFERENCE_TYPE_SOURCE_PAGE, items.get(0).type());
        assertEquals(20L, items.get(0).sourcePageId());
        assertEquals(10L, items.get(0).sourceId());
        assertNull(items.get(0).contentBlockId());
        assertEquals("P4", items.get(0).locator());
        assertEquals("第四页正文", items.get(0).snippet());
    }

    @Test
    void selectedSourcePullsBoundedBlockPreviews() {
        when(sourceService.getMine(OWNER, SPACE_ID, 10L)).thenReturn(source(10L, SPACE_ID, "数据库教程"));
        when(contentBlockService.listMine(OWNER, SPACE_ID, 10L, null))
                .thenReturn(List.of(
                        block(7L, SPACE_ID, 10L, 20L, "第一段正文"),
                        block(8L, SPACE_ID, 10L, 21L, "第二段正文")));

        List<AiContextItem> items = service.resolveSelectedContext(OWNER, SPACE_ID, 10L, null, null, null);

        assertEquals("SOURCE", items.get(0).type());
        assertEquals(10L, items.get(0).sourceId());
        assertEquals(3, items.size());
        assertEquals(7L, items.get(1).contentBlockId());
        assertEquals(10L, items.get(1).sourceId());
        assertEquals(8L, items.get(2).contentBlockId());
    }

    @Test
    void selectedKnowledgePointCarriesItsOwnBody() {
        KnowledgePoint point = new KnowledgePoint();
        point.setId(5L);
        point.setSpaceId(SPACE_ID);
        point.setTitle("B+ 树");
        point.setSummary("多层索引");
        point.setContent("插入时分裂节点");
        when(knowledgePointService.getMine(OWNER, SPACE_ID, 5L)).thenReturn(point);

        List<AiContextItem> items = service.resolveSelectedContext(OWNER, SPACE_ID, null, null, null, 5L);

        assertEquals(1, items.size());
        assertEquals("KNOWLEDGE_POINT", items.get(0).type());
        assertEquals(5L, items.get(0).entityId());
        assertTrue(items.get(0).snippet().contains("插入时分裂节点"));
    }

    /** §12: an ID from another space is a stable validation error, never a leak. */
    @Test
    void contextFromAnotherSpaceIsValidationError() {
        when(contentBlockService.getMine(OWNER, SPACE_ID, 99L)).thenReturn(block(99L, 999L, 10L, 20L, "foreign"));
        assertValidationError(() -> service.resolveSelectedContext(
                OWNER, SPACE_ID, null, null, 99L, null), "contentBlockId");

        when(sourcePageService.getMine(OWNER, SPACE_ID, 98L)).thenReturn(page(98L, 999L, 10L, 1));
        assertValidationError(() -> service.resolveSelectedContext(
                OWNER, SPACE_ID, null, 98L, null, null), "sourcePageId");

        when(sourceService.getMine(OWNER, SPACE_ID, 97L)).thenReturn(source(97L, 999L, "foreign"));
        assertValidationError(() -> service.resolveSelectedContext(
                OWNER, SPACE_ID, 97L, null, null, null), "sourceId");

        KnowledgePoint foreign = new KnowledgePoint();
        foreign.setId(96L);
        foreign.setSpaceId(999L);
        when(knowledgePointService.getMine(OWNER, SPACE_ID, 96L)).thenReturn(foreign);
        assertValidationError(() -> service.resolveSelectedContext(
                OWNER, SPACE_ID, null, null, null, 96L), "knowledgePointId");

        // an ID that does not exist at all
        assertValidationError(() -> service.resolveSelectedContext(
                OWNER, SPACE_ID, null, null, null, 1234L), "knowledgePointId");
    }

    @Test
    void softDeletedKnowledgePointIsRejected() {
        KnowledgePoint point = new KnowledgePoint();
        point.setId(5L);
        point.setSpaceId(SPACE_ID);
        point.setDeletedAt(LocalDateTime.now());
        when(knowledgePointService.getMine(OWNER, SPACE_ID, 5L)).thenReturn(point);

        assertValidationError(() -> service.resolveSelectedContext(
                OWNER, SPACE_ID, null, null, null, 5L), "knowledgePointId");
    }

    @Test
    void pageMustBelongToTheGivenSource() {
        when(sourcePageService.getMine(OWNER, SPACE_ID, 20L)).thenReturn(page(20L, SPACE_ID, 11L, 1));

        assertValidationError(() -> service.resolveSelectedContext(
                OWNER, SPACE_ID, 10L, 20L, null, null), "sourceId");
    }

    /** A block pins its own page, so a stale page ID in the same call is ignored. */
    @Test
    void blockWinsOverThePageAndSourceItWasNotToldAbout() {
        when(contentBlockService.getMine(OWNER, SPACE_ID, 7L)).thenReturn(block(7L, SPACE_ID, 10L, 20L, "正文"));
        when(sourcePageService.getMine(OWNER, SPACE_ID, 20L)).thenReturn(page(20L, SPACE_ID, 10L, 2));
        when(sourceService.getMine(OWNER, SPACE_ID, 10L)).thenReturn(source(10L, SPACE_ID, "教材"));

        List<AiContextItem> items = service.resolveSelectedContext(
                OWNER, SPACE_ID, 999L, 998L, 7L, null);

        assertEquals(1, items.size());
        assertEquals(10L, items.get(0).sourceId());
        assertEquals(20L, items.get(0).sourcePageId());
    }

    /** C03 precedence: selected material first, automatic hits fill the rest. */
    @Test
    void selectedItemsComeFirstAndDedupeAutomaticHits() {
        AiContextItem selected = new AiContextItem(
                "CONTENT_BLOCK", 7L, "选中的段落", "正文", "P3", 10L, 20L, 7L, true);
        SearchResult autoDuplicate = new SearchResult(
                SearchEntityType.CONTENT_BLOCK, 7L, "同一个块", "旧正文",
                20, 10L, null, 20L, 3, null, null);
        SearchResult autoOther = new SearchResult(
                SearchEntityType.KNOWLEDGE_POINT, 3L, "B+ 树", "分裂",
                60, null, null, null, null, 3L, null);
        when(searchService.search(anyString(), anyString(), anyLong(), anyString(),
                isNull(), anyInt(), anyInt()))
                .thenReturn(new SearchPageResponse(List.of(autoDuplicate, autoOther), 0, 8, 2, 1));

        List<AiContextItem> merged = service.assemble(
                OWNER, OWNER, SPACE_ID, "解释索引", List.of(selected));

        assertEquals(2, merged.size());
        assertEquals(7L, merged.get(0).entityId());
        assertTrue(merged.get(0).learnerSelected());
        assertEquals(3L, merged.get(1).entityId());
        assertFalse(merged.get(1).learnerSelected());
    }

    /** An empty selection behaves exactly like the automatic path. */
    @Test
    void emptySelectionDelegatesToSearch() {
        when(searchService.search(anyString(), anyString(), anyLong(), anyString(),
                isNull(), anyInt(), anyInt()))
                .thenReturn(new SearchPageResponse(List.of(), 0, 8, 0, 1));

        assertTrue(service.assemble(OWNER, OWNER, SPACE_ID, "问题", List.of()).isEmpty());
        verify(searchService).search(anyString(), anyString(), anyLong(), anyString(),
                isNull(), anyInt(), anyInt());
    }

    @Test
    void selectedOnlyIsNeverTruncatedAwayByTheBound() {
        properties.getContext().setMaxSearchResults(1);
        AiContextItem block = new AiContextItem(
                "CONTENT_BLOCK", 7L, "块", "正文", null, 10L, 20L, 7L, true);
        AiContextItem point = new AiContextItem(
                "KNOWLEDGE_POINT", 5L, "知识点", "内容", null, null, null, null, true);

        List<AiContextItem> merged = service.assemble(
                OWNER, OWNER, SPACE_ID, "问题", List.of(block, point));

        assertEquals(2, merged.size());
        verify(searchService, never()).search(anyString(), anyString(), anyLong(), anyString(),
                isNull(), anyInt(), anyInt());
    }

    /** Selected material is labelled so the model answers about it first. */
    @Test
    void renderMarksLearnerSelectedMaterial() {
        AiContextItem selected = new AiContextItem(
                "CONTENT_BLOCK", 7L, "选中的段落", "正文", "P3", 10L, 20L, 7L, true);
        AiContextItem retrieved = new AiContextItem(
                "SOURCE", 1L, "教材", "简介", null, 1L, null, null, false);

        String rendered = service.renderContextBlock(List.of(selected, retrieved));

        assertTrue(rendered.contains("[CONTENT_BLOCK · learner-selected] 选中的段落 — 正文"));
        assertTrue(rendered.contains("[SOURCE] 教材 — 简介"));
        verify(searchService, never()).search(anyString(), anyString(), anyLong(), anyString(),
                isNull(), anyInt(), anyInt());
    }

    @Test
    void renderEmptyContextIsSafePlaceholder() {
        assertEquals("(no matching learning material found)", service.renderContextBlock(List.of()));
        assertEquals("(no matching learning material found)", service.renderContextBlock(null));
    }

    @Test
    void renderBoundsTotalChars() {
        properties.getContext().setMaxContextChars(500);
        String longSnippet = "数".repeat(400);
        List<AiContextItem> items = List.of(
                new AiContextItem("CONTENT_BLOCK", 1L, "t1", longSnippet),
                new AiContextItem("CONTENT_BLOCK", 2L, "t2", longSnippet));
        String rendered = service.renderContextBlock(items);
        assertTrue(rendered.length() <= 600, "rendered context must stay bounded: " + rendered.length());
        assertTrue(rendered.contains("t1"));
    }

    @Test
    void renderDoesNotInjectCorrectnessFieldNames() {
        List<AiContextItem> items = List.of(
                new AiContextItem("QUESTION", 9L, "What is ACID?", "safe stem snippet"));
        String rendered = service.renderContextBlock(items);
        assertFalse(rendered.contains("correctOptionKey"));
        assertFalse(rendered.contains("answer_data_json"));
        assertFalse(rendered.contains("referenceAnswer"));
    }

    // ==================== fixtures ====================

    private static ContentBlock block(Long id, Long spaceId, Long sourceId, Long pageId, String text) {
        ContentBlock block = new ContentBlock();
        block.setId(id);
        block.setSpaceId(spaceId);
        block.setSourceId(sourceId);
        block.setSourcePageId(pageId);
        block.setBlockType("PARAGRAPH");
        block.setSortOrder(0);
        block.setNormalizedText(text);
        return block;
    }

    private static SourcePage page(Long id, Long spaceId, Long sourceId, Integer pageNumber) {
        SourcePage page = new SourcePage();
        page.setId(id);
        page.setSpaceId(spaceId);
        page.setSourceId(sourceId);
        page.setSourcePageNumber(pageNumber);
        return page;
    }

    private static Source source(Long id, Long spaceId, String title) {
        Source source = new Source();
        source.setId(id);
        source.setSpaceId(spaceId);
        source.setTitle(title);
        return source;
    }

    private static void assertValidationError(Runnable call, String expectedField) {
        ApiException ex = assertThrows(ApiException.class, call::run);
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals(ApiErrorCodes.VALIDATION_ERROR, ex.code());
        assertTrue(ex.detail().startsWith(expectedField),
                "detail should name " + expectedField + " but was: " + ex.detail());
    }
}
