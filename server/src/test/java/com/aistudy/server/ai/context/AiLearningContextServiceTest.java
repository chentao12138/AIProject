package com.aistudy.server.ai.context;

import com.aistudy.server.ai.config.AiProperties;
import com.aistudy.server.search.dto.SearchPageResponse;
import com.aistudy.server.search.dto.SearchResult;
import com.aistudy.server.search.type.SearchEntityType;
import com.aistudy.server.search.service.SearchService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI-003 — learning context assembly uses SearchService only.
 */
class AiLearningContextServiceTest {

    private SearchService searchService;
    private AiProperties properties;
    private AiLearningContextService service;

    @BeforeEach
    void setUp() {
        searchService = mock(SearchService.class);
        properties = new AiProperties();
        service = new AiLearningContextService(searchService, properties);
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
}
