package com.aistudy.server.source.page;

import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.page.dto.ReorderPagesRequest;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import com.aistudy.server.source.service.SourceService;
import com.aistudy.server.source.page.service.SourcePageService;
import org.junit.jupiter.api.BeforeEach;import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-31 unit tests for the page-order rules: ingestion slot allocation and the
 * reorder permutation. No database — the mapper is mocked and every assertion
 * is about the orders the service asks SQL to write.
 *
 * <p>The reported defect had two halves: a reorder validated only the rows in
 * the request (so the unlisted pages kept orders that the new assignment
 * collided with), and ingestion gave every asset page order 1. Both are
 * asserted here, plus the two-phase write that keeps the intermediate state
 * unambiguous.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SourcePageOrderRulesTest {

    @Mock
    private SourcePageMapper sourcePageMapper;
    @Mock
    private SourceService sourceService;

    private SourcePageService service;

    @BeforeEach
    void wire() {
        service = new SourcePageService(sourcePageMapper, sourceService);
        when(sourceService.getMine(any(), anyLong(), anyLong())).thenReturn(new Source());
    }

    private static SourcePage page(long id, int order) {
        SourcePage page = new SourcePage();
        page.setId(id);
        page.setSpaceId(1L);
        page.setSourceId(2L);
        page.setPageOrder(order);
        page.setPageType("BODY");
        page.setOrderStatus("AUTO");
        return page;
    }

    private static ReorderPagesRequest.ReorderPageItem item(Long pageId, Integer order) {
        return new ReorderPagesRequest.ReorderPageItem(pageId, order, null, null);
    }

    /**
     * Capture of every {@code updateOrderByIdSpaceSource} call, split into the
     * parking phase (negative, illegal-by-contract values used to vacate the
     * real slots first) and the final assignment phase.
     */
    private record Written(Map<Long, Integer> parked, Map<Long, Integer> assigned, List<Object[]> rows) {
    }

    private Written captureWrites() {
        ArgumentCaptor<Long> ids = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> orders = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> types = ArgumentCaptor.forClass(String.class);
        verify(sourcePageMapper, atLeastOnce())
                .updateOrderByIdSpaceSource(ids.capture(), any(), any(), orders.capture(),
                        types.capture(), any(LocalDateTime.class));
        Map<Long, Integer> parked = new HashMap<>();
        Map<Long, Integer> assigned = new HashMap<>();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < ids.getAllValues().size(); i++) {
            Long id = ids.getAllValues().get(i);
            Integer order = orders.getAllValues().get(i);
            rows.add(new Object[]{id, order, types.getAllValues().get(i)});
            if (order < 0) {
                parked.put(id, order);
            } else {
                assigned.put(id, order);
            }
        }
        return new Written(parked, assigned, rows);
    }

    // ==================== allocation (ingestion side) ====================

    @Test
    void emptySourceStartsAtOneAndContinuesAcrossAssets() {
        when(sourcePageMapper.selectList(any())).thenReturn(List.of());
        assertEquals(1, service.allocateOrderSlots(1L, 2L, 1)[0]);

        // Second asset of the same Source: the first asset's page is already there.
        when(sourcePageMapper.selectList(any())).thenReturn(List.of(page(10, 1)));
        int[] second = service.allocateOrderSlots(1L, 2L, 2);
        assertEquals(2, second[0]);
        assertEquals(3, second[1], "multi-asset Sources continue instead of restarting at 1");
    }

    @Test
    void reExtractionReusesFreedSlots() {
        // Pages 1 and 3 still exist (a confirmed order must not move); slot 2 was
        // just deleted with the asset being re-extracted.
        when(sourcePageMapper.selectList(any()))
                .thenReturn(List.of(page(10, 1), page(12, 3), page(13, 4)));
        int[] slots = service.allocateOrderSlots(1L, 2L, 3);
        assertEquals(2, slots[0]);
        assertEquals(5, slots[1]);
        assertEquals(6, slots[2]);
    }

    @Test
    void allocationRejectsNonsensicalRequests() {
        when(sourcePageMapper.selectList(any())).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class, () -> service.allocateOrderSlots(1L, 2L, 0));
        assertThrows(IllegalArgumentException.class, () -> service.allocateOrderSlots(1L, 2L, 200_000));
    }

    // ==================== reorder (human side) ====================

    @Test
    void partialBatchRenumbersTheWholeSetWithoutCollisions() {
        List<SourcePage> visible = List.of(page(20, 1), page(21, 2), page(22, 3), page(23, 4), page(24, 5));
        when(sourcePageMapper.selectBySpaceSourceOwner(1L, 2L, "owner")).thenReturn(visible);
        when(sourcePageMapper.updateOrderByIdSpaceSource(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.batchUpdateOrder("owner", 1L, 2L,
                new ReorderPagesRequest(List.of(item(22L, 1))));

        Written written = captureWrites();
        assertEquals(1, written.assigned().get(22L), "the requested slot wins");
        // Unlisted pages keep their relative order in the leftover slots: they
        // are no longer left sitting on an order somebody else just took.
        assertEquals(2, written.assigned().get(20L));
        assertEquals(3, written.assigned().get(21L));
        assertEquals(4, written.assigned().get(23L));
        assertEquals(5, written.assigned().get(24L));
        assertEquals(5, written.assigned().size(), "every visible page is placed");
        assertTrue(written.assigned().values().stream().distinct().count() == 5,
                "the result is a permutation, not a set of collisions");
    }

    @Test
    void writesAreParkedFirstSoNoTwoPagesShareAnOrderMidRequest() {
        List<SourcePage> visible = List.of(page(30, 1), page(31, 2), page(32, 3));
        when(sourcePageMapper.selectBySpaceSourceOwner(1L, 2L, "owner")).thenReturn(visible);
        when(sourcePageMapper.updateOrderByIdSpaceSource(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // A straight swap: without parking, page 31 would briefly hold order 1
        // while page 30 still holds it.
        service.batchUpdateOrder("owner", 1L, 2L,
                new ReorderPagesRequest(List.of(item(30L, 2), item(31L, 1))));

        Written written = captureWrites();
        assertEquals(3, written.parked().size(), "all rows vacate their slots first");
        assertTrue(written.parked().values().stream().allMatch(v -> v < 0));
        assertTrue(written.parked().values().stream().distinct().count() == 3);
        // The parking phase strictly precedes the assignment phase.
        int firstAssigned = -1;
        int lastParked = -1;
        for (int i = 0; i < written.rows().size(); i++) {
            int order = (Integer) written.rows().get(i)[1];
            if (order < 0) {
                lastParked = i;
            } else if (firstAssigned == -1) {
                firstAssigned = i;
            }
        }
        assertTrue(lastParked < firstAssigned, "park phase must finish before assignment starts");
        assertEquals(1, written.assigned().get(31L));
        assertEquals(2, written.assigned().get(30L));
        assertEquals(3, written.assigned().get(32L));
    }

    @Test
    void explicitPageTypeIsAppliedAndNullFallsBackToTheStoredType() {
        List<SourcePage> visible = List.of(page(40, 1), page(41, 2));
        when(sourcePageMapper.selectBySpaceSourceOwner(1L, 2L, "owner")).thenReturn(visible);
        when(sourcePageMapper.updateOrderByIdSpaceSource(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // A typed override plus an item that omits pageType: writing null into a
        // NOT NULL column used to be possible on this path.
        ReorderPagesRequest.ReorderPageItem override =
                new ReorderPagesRequest.ReorderPageItem(40L, 2, "IMAGE", 7);
        service.batchUpdateOrder("owner", 1L, 2L,
                new ReorderPagesRequest(List.of(override, item(41L, 1))));

        Written written = captureWrites();
        assertTrue(written.rows().stream().allMatch(row -> row[2] != null),
                "page_type must never be written as null");
        assertEquals("IMAGE", finalType(written, 40L));
        assertEquals("BODY", finalType(written, 41L), "stored type is kept when the request omits it");
        // printedPageNumber was accepted by the DTO and silently dropped; it now
        // reaches the row.
        ArgumentCaptor<SourcePage> patch = ArgumentCaptor.forClass(SourcePage.class);
        verify(sourcePageMapper, times(1)).updateById(patch.capture());
        assertEquals(7, patch.getValue().getPrintedPageNumber().intValue());
        assertEquals(40L, patch.getValue().getId().longValue());
    }

    private static String finalType(Written written, long pageId) {
        String type = null;
        for (Object[] row : written.rows()) {
            if (pageId == (Long) row[0] && (Integer) row[1] > 0) {
                type = (String) row[2];
            }
        }
        return type;
    }

    @Test
    void onlyExplicitlyPlacedPagesBecomeConfirmed() {
        List<SourcePage> visible = List.of(page(50, 1), page(51, 2), page(52, 3));
        when(sourcePageMapper.selectBySpaceSourceOwner(1L, 2L, "owner")).thenReturn(visible);
        when(sourcePageMapper.updateOrderByIdSpaceSource(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.batchUpdateOrder("owner", 1L, 2L, new ReorderPagesRequest(List.of(item(52L, 1))));

        verify(sourcePageMapper, times(1))
                .confirmOrderByIdSpaceSource(eq(52L), eq(1L), eq(2L), any(LocalDateTime.class));
        verify(sourcePageMapper, times(1)).confirmOrderByIdSpaceSource(any(), any(), any(), any());
    }

    @Test
    void invalidRequestsAreRejectedAndChangeNothing() {
        List<SourcePage> visible = List.of(page(60, 1), page(61, 2));
        when(sourcePageMapper.selectBySpaceSourceOwner(1L, 2L, "owner")).thenReturn(visible);

        assertThrows(ResponseStatusException.class,
                () -> service.batchUpdateOrder("owner", 1L, 2L, new ReorderPagesRequest(List.of())));
        assertThrows(ResponseStatusException.class,
                () -> service.batchUpdateOrder("owner", 1L, 2L, null));
        // duplicate order
        ResponseStatusException duplicate = assertThrows(ResponseStatusException.class,
                () -> service.batchUpdateOrder("owner", 1L, 2L,
                        new ReorderPagesRequest(List.of(item(60L, 1), item(61L, 1)))));
        assertTrue(duplicate.getMessage().contains("pageOrder"), duplicate.getMessage());
        // order outside 1..N — the case that used to be accepted with just ">= 1"
        assertThrows(ResponseStatusException.class,
                () -> service.batchUpdateOrder("owner", 1L, 2L,
                        new ReorderPagesRequest(List.of(item(60L, 9)))));
        // pageId that belongs to another source
        ResponseStatusException foreign = assertThrows(ResponseStatusException.class,
                () -> service.batchUpdateOrder("owner", 1L, 2L,
                        new ReorderPagesRequest(List.of(item(999L, 1)))));
        assertTrue(foreign.getMessage().contains("does not belong"), foreign.getMessage());
        verify(sourcePageMapper, times(0))
                .updateOrderByIdSpaceSource(any(), any(), any(), any(), any(), any());
    }

    @Test
    void unknownSourceIsFourOhFour() {
        when(sourceService.getMine(any(), anyLong(), anyLong())).thenReturn(null);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.batchUpdateOrder("owner", 1L, 2L,
                        new ReorderPagesRequest(List.of(item(1L, 1)))));
        assertTrue(error.getMessage().contains("404"), error.getMessage());
    }

    @Test
    void flexibleItemsWithoutAnOrderFillTheFreeSlots() {
        List<SourcePage> visible = List.of(page(70, 1), page(71, 2), page(72, 3));
        when(sourcePageMapper.selectBySpaceSourceOwner(1L, 2L, "owner")).thenReturn(visible);
        when(sourcePageMapper.updateOrderByIdSpaceSource(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.batchUpdateOrder("owner", 1L, 2L,
                new ReorderPagesRequest(List.of(item(72L, 1), item(70L, null))));

        Written written = captureWrites();
        assertEquals(1, written.assigned().get(72L));
        assertEquals(2, written.assigned().get(70L));
        assertEquals(3, written.assigned().get(71L));
    }
}
